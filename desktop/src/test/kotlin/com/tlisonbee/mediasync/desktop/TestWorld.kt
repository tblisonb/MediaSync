package com.tlisonbee.mediasync.desktop

import com.tlisonbee.mediasync.api.DeviceKind
import com.tlisonbee.mediasync.api.PairRequest
import com.tlisonbee.mediasync.server.RepoDir
import com.tlisonbee.mediasync.server.Repository
import com.tlisonbee.mediasync.server.Setup
import com.tlisonbee.mediasync.server.mediaSyncModule
import com.tlisonbee.mediasync.server.newPairingCode
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import kotlinx.coroutines.runBlocking
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name

/** A real server on loopback plus any number of desktop clients, each with its own folders. */
class TestWorld : AutoCloseable {
    val root: Path = Files.createTempDirectory("mediasync-desktop-test")
    private val repoDir = RepoDir(root.resolve("server"))
    val repo: Repository
    private val server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>
    val url: String

    init {
        Setup.create(repoDir, "correct horse battery")
        repo = Repository(repoDir)
        server = embeddedServer(Netty, host = "127.0.0.1", port = 0) { mediaSyncModule(repo) }.start(wait = false)
        url = "http://127.0.0.1:" + runBlocking { server.engine.resolvedConnectors().first().port }
    }

    fun client(name: String): TestClient {
        val pairing = ApiClient(url, null).pair(PairRequest(repo.newPairingCode(), name, DeviceKind.DESKTOP))
        val home = root.resolve(name)
        val music = Files.createDirectories(home.resolve("Music"))
        val dirs = Dirs(home.resolve("config"), home.resolve("state"), home.resolve("cache"), home.resolve("run"))
        val config = DesktopConfig(url, pairing.deviceId, name, listOf(FolderConfig("music/", music.toString())))
        val api = ApiClient(url, pairing.token)
        api.putSubscription(config.subscription())
        val index = Index(dirs.index)
        // A minute ahead, so files the test just wrote count as settled.
        val engine = SyncEngine(config, api, index, dirs, log = {}, clock = { System.currentTimeMillis() + 60_000 })
        return TestClient(name, music, index, engine, api)
    }

    override fun close() {
        server.stop(0, 0)
        repo.close()
        root.toFile().deleteRecursively()
    }
}

class TestClient(val name: String, val music: Path, val index: Index, val engine: SyncEngine, val api: ApiClient) {
    fun sync(allowDeletes: Boolean = false): SyncReport = engine.sync(allowDeletes)

    fun file(relative: String): Path = music.resolve(relative)

    fun files(): List<String> = Files.walk(music).use { s ->
        s.filter { Files.isRegularFile(it) }.map { music.relativize(it).joinToString("/") { p -> p.name } }.sorted().toList()
    }
}

object Fixtures {
    private val dir: Path = Path.of(System.getProperty("mediasync.fixtures"))

    /**
     * A copy of a fixture with its own media (one byte of the last audio frame changed, which no
     * parser minds) and the given tags, set the way another tag editor would: everything else the
     * ripper left (comments, encoder fields) stays.
     */
    fun track(target: Path, tags: Map<FieldKey, String>, variant: Int, fixture: String = "tone.flac"): Path {
        val bytes = Files.readAllBytes(dir.resolve(fixture))
        // Past the end of FLAC's frames would be nothing; for MP3, stay clear of the ID3v1 tag at the end.
        val at = if (fixture.endsWith(".mp3")) bytes.size - 128 - 40 else bytes.size - 5
        bytes[at] = (bytes[at].toInt() xor (variant + 1)).toByte()
        Files.createDirectories(target.parent)
        Files.write(target, bytes)
        retag(target, tags)
        return target
    }

    /** Changes tags in place like Kid3 or Picard would, leaving other fields alone. */
    fun retag(file: Path, tags: Map<FieldKey, String>) {
        val audio = AudioFileIO.read(file.toFile())
        val tag = audio.tagOrCreateAndSetDefault
        tags.forEach { (key, value) -> tag.setField(key, value) }
        audio.commit()
        bumpMtime(file)
    }

    fun copy(name: String, target: Path): Path {
        Files.createDirectories(target.parent)
        return Files.copy(dir.resolve(name), target)
    }

    /** Filesystem timestamps can be coarse; make sure an edit is seen as one. */
    fun bumpMtime(file: Path) {
        val now = System.currentTimeMillis()
        Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(maxOf(now, Files.getLastModifiedTime(file).toMillis() + 1000)))
    }

    fun album(artist: String, album: String, vararg titles: String) = titles.mapIndexed { i, title ->
        mapOf(FieldKey.ARTIST to artist, FieldKey.ALBUM_ARTIST to artist, FieldKey.ALBUM to album, FieldKey.TITLE to title, FieldKey.TRACK to "${i + 1}", FieldKey.DISC_NO to "1")
    }
}
