package com.tlisonbee.mediasync.server

import com.tlisonbee.mediasync.library.Platform
import com.tlisonbee.mediasync.library.Platforms
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.sql.Connection
import java.sql.DriverManager
import java.text.Normalizer
import java.time.Duration
import java.util.zip.ZipInputStream
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants
import javax.xml.stream.XMLStreamReader

/**
 * The game databases, in one SQLite file (gamedb/index.db) the server only reads:
 *
 * - The No-Intro and Redump lists from the libretro database (github.com/libretro/libretro-database,
 *   CC BY-SA 4.0), the ones RetroArch uses: every known good dump of a cartridge or disc, with its
 *   name ("Shadow of the Colossus (USA)"), checksums and, for discs, serial.
 * - The LaunchBox games database (gamesdb.launchbox-app.com): each game's title, release date,
 *   publisher, developer, genres and player count, matched to a dump by title and platform.
 *
 * `mediasync-server fetch-games-db` downloads both and builds the file, from a service of its own
 * (deploy/mediasync-gamedb.service): the server itself never leaves loopback. It runs weekly, and
 * when the web UI asks (by leaving [GameDbDir.request]).
 */
class GameDbDir(val root: Path) {
    val index: Path = root.resolve("index.db")

    /** Left by the web UI to ask for a download now; a systemd path unit watches for it. */
    val request: Path = root.resolve("update-requested")

    /** Why the last download failed, if it did; removed by one that works. */
    val error: Path = root.resolve("last-error.txt")
}

/** A dump in a No-Intro or Redump list. */
data class Dump(val platform: String, val release: String, val file: String, val serial: String?, val region: String?)

/** A game in the LaunchBox database. */
data class GameMeta(
    val platform: String,
    val title: String,
    val year: String?,
    val publisher: String?,
    val developer: String?,
    val genres: List<String>,
    val players: String?,
)

/** Reads the index; [open] is null until one has been downloaded. */
class GameIndex private constructor(private val conn: Connection) : AutoCloseable {
    val builtAt: Long? get() = info("built_at")?.toLongOrNull()
    val dumps: Long get() = count("dump")
    val games: Long get() = count("meta")

    fun info(key: String): String? = one("SELECT value FROM info WHERE key = ?", key) { it.getString(1) }

    fun bySha1(sha1: String): List<Dump> = all("SELECT * FROM dump WHERE sha1 = ?", sha1.lowercase()) { it.dump() }

    fun bySerial(serial: String, platforms: Collection<String>): Dump? =
        all("SELECT * FROM dump WHERE serial = ?", normalizeSerial(serial)) { it.dump() }.firstOrNull { it.platform in platforms }

    /** The game with [title] (in any of its spellings) on [platform]. */
    fun meta(platform: String, title: String): GameMeta? =
        one("SELECT m.* FROM meta_key k JOIN meta m ON m.id = k.id WHERE k.platform = ? AND k.key = ?", platform, titleKey(title)) { it.meta() }

    /** Games on [platform] whose title contains [text], for choosing one by hand. */
    fun search(platform: String, text: String, limit: Int = 30): List<GameMeta> {
        val key = titleKey(text).ifEmpty { return emptyList() }
        return all(
            "SELECT DISTINCT m.* FROM meta_key k JOIN meta m ON m.id = k.id WHERE k.platform = ? AND k.key LIKE ? ORDER BY length(m.title), m.title LIMIT ?",
            platform, "%$key%", limit,
        ) { it.meta() }
    }

    override fun close() = conn.close()

    private fun java.sql.ResultSet.dump() = Dump(getString("platform"), getString("release"), getString("file"), getString("serial"), getString("region"))

    private fun java.sql.ResultSet.meta() = GameMeta(
        getString("platform"), getString("title"), getString("year"), getString("publisher"), getString("developer"),
        getString("genres")?.split(';')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty(), getString("players"),
    )

    private fun count(table: String) = one("SELECT count(*) FROM $table") { it.getLong(1) } ?: 0

    private fun <T> all(sql: String, vararg args: Any?, map: (java.sql.ResultSet) -> T): List<T> = synchronized(conn) {
        conn.prepareStatement(sql).use { st ->
            args.forEachIndexed { i, a -> st.setObject(i + 1, a) }
            st.executeQuery().use { rs -> buildList { while (rs.next()) add(map(rs)) } }
        }
    }

    private fun <T> one(sql: String, vararg args: Any?, map: (java.sql.ResultSet) -> T): T? = all(sql, *args, map = map).firstOrNull()

    companion object {
        fun open(dir: GameDbDir): GameIndex? {
            if (!Files.isRegularFile(dir.index)) return null
            val readOnly = org.sqlite.SQLiteConfig().apply { setReadOnly(true) }
            return GameIndex(readOnly.createConnection("jdbc:sqlite:${dir.index}"))
        }

        /**
         * A title as the databases are matched on: no case, accents, punctuation or leading "The",
         * and "&" as "and". "Metal Gear Solid 3: Snake Eater" (LaunchBox) and "Metal Gear Solid
         * 3 - Snake Eater" (Redump) are the same; so are "Legend of Zelda, The" and "The Legend of Zelda".
         */
        fun titleKey(title: String): String {
            var s = Normalizer.normalize(title.lowercase(), Normalizer.Form.NFKD).replace(Regex("\\p{M}+"), "")
            s = s.replace("&amp;", "&").replace("&", " and ").replace(Regex(""",\s*the\b"""), " ").replace(Regex("""^\s*(the|a)\s+"""), "")
            return s.filter { it.isLetterOrDigit() }
        }

        /** "SLUS_203.12" (as a disc's SYSTEM.CNF has it) and "SLUS-20312" (as the lists do) are one serial. */
        fun normalizeSerial(serial: String): String = serial.uppercase().replace("_", "-").replace(".", "").replace(" ", "").trim()
    }
}

/** Downloads the databases and builds the index (the fetch-games-db command). */
object GameDbFetch {
    private const val LIBRETRO = "https://raw.githubusercontent.com/libretro/libretro-database/master/metadat"
    private const val LAUNCHBOX = "https://gamesdb.launchbox-app.com/Metadata.zip"

    fun run(dir: GameDbDir, env: Map<String, String> = System.getenv()): Int {
        Files.createDirectories(dir.root)
        val libretro = env["MEDIASYNC_GAMEDB_LIBRETRO"] ?: LIBRETRO
        val launchBox = env["MEDIASYNC_GAMEDB_LAUNCHBOX"] ?: LAUNCHBOX
        val work = Files.createTempDirectory(dir.root, ".fetch-")
        try {
            val lists = Platforms.ALL.associateWith { p ->
                download("$libretro/${p.listFolder}/${p.list.replace(" ", "%20")}.dat", work.resolve("${p.id}.dat"))
            }
            val zip = download(launchBox, work.resolve("launchbox.zip"))
            val built = work.resolve("index.db")
            build(built, lists, zip)
            Files.move(built, dir.index, REPLACE_EXISTING, ATOMIC_MOVE)
            Files.deleteIfExists(dir.error)
            println("The game databases are up to date: ${dir.index}")
            return 0
        } catch (e: Exception) {
            Files.writeString(dir.error, "${java.time.Instant.now()}: ${e.message ?: e.javaClass.simpleName}\n")
            System.err.println("Couldn't update the game databases: ${e.message}")
            return 1
        } finally {
            Files.deleteIfExists(dir.request)
            work.toFile().deleteRecursively()
        }
    }

    private val http by lazy { HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(30)).build() }

    private fun download(url: String, to: Path): Path {
        if (url.startsWith("file:")) return Files.copy(Path.of(URI(url)), to)
        val response = http.send(HttpRequest.newBuilder(URI(url)).timeout(Duration.ofMinutes(10)).build(), HttpResponse.BodyHandlers.ofFile(to))
        if (response.statusCode() != 200) throw java.io.IOException("$url: HTTP ${response.statusCode()}")
        return to
    }

    /** Builds an index from the lists ([lists], by platform) and LaunchBox's Metadata.zip. */
    fun build(out: Path, lists: Map<Platform, Path>, launchBoxZip: Path?) {
        Files.deleteIfExists(out)
        DriverManager.getConnection("jdbc:sqlite:$out").use { conn ->
            conn.createStatement().use { st ->
                st.execute("CREATE TABLE info (key TEXT PRIMARY KEY, value TEXT)")
                st.execute("CREATE TABLE dump (platform TEXT, release TEXT, file TEXT, serial TEXT, region TEXT, size INTEGER, sha1 TEXT)")
                st.execute("CREATE TABLE meta (id INTEGER PRIMARY KEY, platform TEXT, title TEXT, year TEXT, publisher TEXT, developer TEXT, genres TEXT, players TEXT)")
                st.execute("CREATE TABLE meta_key (platform TEXT, key TEXT, id INTEGER, PRIMARY KEY (platform, key))")
            }
            conn.autoCommit = false
            val dump = conn.prepareStatement("INSERT INTO dump VALUES (?, ?, ?, ?, ?, ?, ?)")
            for ((platform, file) in lists) {
                Files.newInputStream(file).use { input ->
                    DatFile.read(input) { d, size, sha1 ->
                        val serials = d.serial?.split(',')?.map(GameIndex::normalizeSerial)?.filter { it.isNotEmpty() }.orEmpty().ifEmpty { listOf(null) }
                        for (serial in serials) {
                            listOf(platform.id, d.release, d.file, serial, d.region, size, sha1?.lowercase()).forEachIndexed { i, v -> dump.setObject(i + 1, v) }
                            dump.addBatch()
                        }
                    }
                }
                dump.executeBatch()
            }
            if (launchBoxZip != null) LaunchBox.read(launchBoxZip, conn)
            conn.createStatement().use { st ->
                st.execute("CREATE INDEX dump_sha1 ON dump (sha1)")
                st.execute("CREATE INDEX dump_serial ON dump (serial)")
            }
            conn.prepareStatement("INSERT INTO info VALUES ('built_at', ?)").use { it.setString(1, System.currentTimeMillis().toString()); it.executeUpdate() }
            conn.commit()
        }
    }
}

/**
 * The clrmamepro format the libretro lists use:
 * `game ( name "…" region "…" serial "…" rom ( name "….iso" size 123 crc … sha1 … ) )`.
 */
object DatFile {
    private val TOKEN = Regex(""""((?:[^"\\]|\\.)*)"|([()])|([^\s()"]+)""")

    fun read(input: InputStream, onRom: (Dump, Long?, String?) -> Unit) {
        val text = input.bufferedReader().readText()
        val tokens = TOKEN.findAll(text).map { m -> m.groupValues[1].takeIf { m.groups[1] != null }?.let { Token(it, true) } ?: Token(m.value, false) }.iterator()
        while (tokens.hasNext()) {
            val t = tokens.next()
            if (!t.quoted && t.text == "game") {
                if (tokens.hasNext() && tokens.next().text == "(") readGame(tokens, onRom)
            }
        }
    }

    private data class Token(val text: String, val quoted: Boolean)

    private fun readGame(tokens: Iterator<Token>, onRom: (Dump, Long?, String?) -> Unit) {
        val game = mutableMapOf<String, String>()
        val roms = mutableListOf<Map<String, String>>()
        while (tokens.hasNext()) {
            val key = tokens.next()
            if (!key.quoted && key.text == ")") break
            if (!tokens.hasNext()) break
            val value = tokens.next()
            if (!value.quoted && value.text == "(") {
                val group = readGroup(tokens)
                if (key.text == "rom") roms += group
            } else {
                game.putIfAbsent(key.text, value.text)
            }
        }
        val release = game["name"] ?: return
        for (rom in roms) {
            val file = rom["name"] ?: continue
            onRom(Dump("", release, file, rom["serial"] ?: game["serial"], game["region"]), rom["size"]?.toLongOrNull(), rom["sha1"])
        }
    }

    private fun readGroup(tokens: Iterator<Token>): Map<String, String> {
        val group = mutableMapOf<String, String>()
        while (tokens.hasNext()) {
            val key = tokens.next()
            if (!key.quoted && key.text == ")") break
            if (!tokens.hasNext()) break
            val value = tokens.next()
            if (!value.quoted && value.text == "(") readGroup(tokens) else group.putIfAbsent(key.text, value.text)
        }
        return group
    }
}

/** Reads Metadata.xml out of LaunchBox's Metadata.zip into the index, for the platforms MediaSync knows. */
internal object LaunchBox {
    private val platforms = Platforms.ALL.associateBy { it.launchBox }

    fun read(zip: Path, conn: Connection) {
        val meta = conn.prepareStatement("INSERT INTO meta VALUES (?, ?, ?, ?, ?, ?, ?, ?)")
        val key = conn.prepareStatement("INSERT OR IGNORE INTO meta_key VALUES (?, ?, ?)")
        val platformOf = HashMap<Long, String>()
        ZipInputStream(Files.newInputStream(zip)).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                if (entry.name != "Metadata.xml") continue
                val xml = XMLInputFactory.newFactory().apply {
                    setProperty(XMLInputFactory.SUPPORT_DTD, false)
                    setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
                }.createXMLStreamReader(input)
                while (xml.hasNext()) {
                    if (xml.next() != XMLStreamConstants.START_ELEMENT) continue
                    when (xml.localName) {
                        "Game" -> {
                            val f = fields(xml, "Game")
                            val platform = platforms[f["Platform"]]?.id ?: continue
                            val id = f["DatabaseID"]?.toLongOrNull() ?: continue
                            val name = f["Name"] ?: continue
                            val year = (f["ReleaseDate"] ?: f["ReleaseYear"])?.take(4)?.takeIf { it.all(Char::isDigit) && it.length == 4 }
                            listOf(id, platform, name, year, f["Publisher"], f["Developer"], f["Genres"], f["MaxPlayers"]?.takeIf { it != "0" })
                                .forEachIndexed { i, v -> meta.setObject(i + 1, v) }
                            meta.addBatch()
                            platformOf[id] = platform
                            addKey(key, platform, name, id)
                        }
                        "GameAlternateName" -> {
                            val f = fields(xml, "GameAlternateName")
                            val id = f["DatabaseID"]?.toLongOrNull() ?: continue
                            val platform = platformOf[id] ?: continue
                            addKey(key, platform, f["AlternateName"] ?: continue, id)
                        }
                    }
                }
                meta.executeBatch()
                key.executeBatch()
                return
            }
        }
        throw java.io.IOException("$zip has no Metadata.xml")
    }

    private fun addKey(st: java.sql.PreparedStatement, platform: String, title: String, id: Long) {
        val key = GameIndex.titleKey(title).ifEmpty { return }
        st.setString(1, platform)
        st.setString(2, key)
        st.setLong(3, id)
        st.addBatch()
    }

    /** The text of each child element of the current [element]. */
    private fun fields(xml: XMLStreamReader, element: String): Map<String, String> {
        val out = HashMap<String, String>()
        while (xml.hasNext()) {
            when (xml.next()) {
                XMLStreamConstants.START_ELEMENT -> {
                    val name = xml.localName
                    out[name] = xml.elementText.trim()
                }
                XMLStreamConstants.END_ELEMENT -> if (xml.localName == element) return out
            }
        }
        return out
    }
}
