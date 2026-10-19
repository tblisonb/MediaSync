package com.tlisonbee.mediasync.server

import com.tlisonbee.mediasync.api.AssetKind
import com.tlisonbee.mediasync.api.PushRequest
import com.tlisonbee.mediasync.api.PushOutcome
import com.tlisonbee.mediasync.api.Tags
import com.tlisonbee.mediasync.library.Platforms
import com.tlisonbee.mediasync.library.RepoPaths
import com.tlisonbee.mediasync.tags.TagField
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Games: disc images, ROMs and their other files, in games/. Each file is filed in its game's folder,
 * games/{platform}/{title} ({region})/, under the name it came with (RepoPaths.game).
 *
 * Identification fills in a game's details from the game databases (GameDb.kt): first by the
 * file's SHA-1, which finds a good dump in the No-Intro or Redump list; failing that, for a
 * PlayStation disc, by the serial its SYSTEM.CNF names; failing that, by its file name. The dump's
 * name gives the title, region and disc; LaunchBox adds the year, publisher, developer, genres and
 * player count. Files with no match of their own (a .cue, a disc's other tracks) take the game of
 * the file beside them they're named after. It runs after a push brings games in, and from the
 * web UI; each run is one revision.
 */

/** How a game file was identified, as game_ident keeps it. */
object How {
    const val CHECKSUM = "checksum"
    const val SERIAL = "serial"
    const val SIBLING = "sibling"
    const val NAME = "name"
    const val UNKNOWN = "unknown"

    val MATCHED = setOf(CHECKSUM, SERIAL, SIBLING)
}

/** An identification run's progress, as the web UI shows it. */
class GameJob(val total: Int) {
    @Volatile var done = 0
    @Volatile var rev: Long? = null
    @Volatile var finished = false
    @Volatile var matched = 0
    val failed: MutableList<String> = CopyOnWriteArrayList()

    /** More games arrived while this ran: a run for them follows. */
    @Volatile var again = false
}

/** What a disc's or cartridge's file name says, before any database has seen it. */
data class GameGuess(val title: String, val region: String?, val disc: String?)

object GameNames {
    private val GROUP = Regex("""\s*[(\[]([^)\]]*)[)\]]""")
    private val DISC = Regex("""(?i)^(?:disc|disk|cd)\s*(\d+)""")

    /** Region names as the lists write them, and GoodTools' one-letter codes. */
    private val REGIONS = setOf(
        "World", "USA", "Europe", "Japan", "Asia", "Australia", "Korea", "Brazil", "Canada", "China", "France", "Germany",
        "Italy", "Spain", "UK", "Netherlands", "Sweden", "Russia", "Taiwan", "Hong Kong", "Scandinavia", "Latin America",
    )
    private val CODES = mapOf("U" to "USA", "E" to "Europe", "J" to "Japan", "W" to "World", "UE" to "USA, Europe", "JU" to "Japan, USA")

    /** "Final Fantasy X (USA) (Disc 1).iso", "zelda_(U)_[!].sfc", "SLUS_203.12.Shadow of the Colossus.iso". */
    fun guess(fileName: String): GameGuess {
        var name = fileName.replace('\\', '/').substringAfterLast('/').let { n ->
            if (n.substringAfterLast('.', "").length in 1..4) n.substringBeforeLast('.') else n
        }
        // Open PS2 Loader names images "SLUS_203.12.Title".
        name = name.replace(Regex("""^[A-Z]{4}[_-]\d{3}\.\d{2}\."""), "")
        if (' ' !in name) name = name.replace('_', ' ').replace(Regex("""(?<=\w)\.(?=\w)"""), " ")
        var region: String? = null
        var disc: String? = null
        for (m in GROUP.findAll(name)) {
            val inside = m.groupValues[1].trim()
            DISC.find(inside)?.let { disc = it.groupValues[1].trimStart('0').ifEmpty { "0" } }
            if (region == null) {
                region = CODES[inside] ?: inside.takeIf { g -> g.split(',').map { it.trim() }.all { it in REGIONS } }
            }
        }
        val title = name.replace(GROUP, "").replace(Regex("""\s+"""), " ").trim(' ', '-', '_').ifEmpty { name }
        return GameGuess(title, region, disc)
    }

    /** The title in a list's name for a dump: "Shadow of the Colossus" in "Shadow of the Colossus (USA)". */
    fun titleOf(release: String): String = release.substringBefore(" (").trim()

    /** The first parentheses of a list's name are its region: "Europe, Australia". */
    fun regionOf(release: String): String? = Regex("""\(([^)]*)\)""").find(release)?.groupValues?.get(1)

    fun discOf(release: String): String? = Regex("""\((?:Disc|Disk) (\d+)\)""").find(release)?.groupValues?.get(1)
}

/** A new file in games/ is a game file, whatever the device called it: its first guess of details. */
fun Repository.gameImport(path: String, sourcePath: String?): Pair<String?, Tags> {
    val from = sourcePath ?: path
    val guess = GameNames.guess(from)
    val platform = Platforms.guess(from) ?: Platforms.guess(path)
    val tags = buildMap {
        put(TagField.TITLE, listOf(guess.title))
        guess.region?.let { put(TagField.REGION, listOf(it)) }
        guess.disc?.let { put(TagField.DISC_NUMBER, listOf(it)) }
    }
    return platform?.id to policy.filter(AssetKind.GAME, tags)
}

/** Live game files, all or under [folder]. */
fun Repository.liveGames(folder: String = RepoPaths.GAMES.trimEnd('/')): List<Version> =
    db.read { liveUnder(folder) }.filter { it.kind == AssetKind.GAME }

/** How each game file was identified; files not yet looked at are absent. */
fun Repository.gameIdents(): Map<String, String> = db.query("SELECT asset_id, how FROM game_ident") { it.getString(1) to it.getString(2) }.toMap()

val Repository.gameDb get() = GameDbDir(dir.root.resolve("gamedb"))

/** Asks the downloader service for fresh databases (a systemd path unit watches for the file). */
fun Repository.requestGameDbUpdate() {
    Files.createDirectories(gameDb.root)
    Files.writeString(gameDb.request, System.currentTimeMillis().toString())
}

/**
 * After a push: identifies the game files it brought, in the background. One run at a time; what
 * arrives meanwhile gets the next.
 */
fun Repository.identifyArrivals(request: PushRequest, results: List<PushOutcome>) {
    val arrived = request.changes.zip(results).any { (change, outcome) -> outcome == PushOutcome.CREATED && change.path.startsWith(RepoPaths.GAMES) }
    if (arrived) startIdentifying(webDeviceId())
}

/**
 * Identifies the game files not yet looked at, or with [assetIds] those again (from the web UI);
 * returns the run, or the one already going.
 */
fun Repository.startIdentifying(deviceId: String, assetIds: Collection<String>? = null): GameJob {
    if (assetIds != null) db.tx { assetIds.forEach { db.exec("DELETE FROM game_ident WHERE asset_id = ?", it) } }
    val running = gameJob.get()
    if (running != null && !running.finished) {
        running.again = true
        return running
    }
    val seen = gameIdents().keys
    val files = liveGames().filter { it.assetId !in seen }
    val job = GameJob(files.size)
    if (!gameJob.compareAndSet(running, job)) return gameJob.get()!!
    inBackground("game-identification") {
        try {
            job.rev = identifyGames(deviceId, files, job)
        } catch (e: Exception) {
            job.failed += "identification stopped: ${e.message ?: e.javaClass.simpleName}"
        } finally {
            job.finished = true
            if (job.again) startIdentifying(deviceId)
        }
    }
    return job
}

/** What a game file's contents say: its SHA-1 (also without a copier header) and a disc's serial. */
data class GameCheck(val sha1: String, val bareSha1: String?, val serial: String?, val ps2: Boolean)

fun Repository.gameCheck(mediaHash: String): GameCheck? {
    db.one("SELECT * FROM game_check WHERE media_hash = ?", mediaHash) {
        GameCheck(it.getString("sha1"), it.getString("bare_sha1"), it.getString("serial"), it.getInt("ps2") != 0)
    }?.let { return it }
    val media = media(mediaHash) ?: return null
    val file = dir.blob(media.baseBlob)
    if (!Files.isRegularFile(file)) return null
    val check = GameChecks.of(file, media.format)
    db.exec(
        "INSERT OR REPLACE INTO game_check (media_hash, sha1, bare_sha1, serial, ps2) VALUES (?, ?, ?, ?, ?)",
        mediaHash, check.sha1, check.bareSha1, check.serial, if (check.ps2) 1 else 0,
    )
    return check
}

object GameChecks {
    /** Disc image formats a SYSTEM.CNF can be read from. */
    private val IMAGES = setOf("iso", "bin", "img")

    fun of(file: Path, format: String): GameCheck {
        val size = Files.size(file)
        // Some cartridge dumps carry a header the lists leave out: iNES's 16 bytes, a copier's 512.
        val header = when {
            format == "nes" && startsWith(file, byteArrayOf(0x4E, 0x45, 0x53, 0x1A)) -> 16L
            format in setOf("smc", "sfc", "swc", "fig") && size % 1024 == 512L -> 512L
            else -> 0L
        }
        val boot = if (format in IMAGES && size >= 1_000_000) IsoSerial.read(file) else null
        return GameCheck(sha1(file, 0), if (header > 0) sha1(file, header) else null, boot?.serial, boot?.ps2 == true)
    }

    private fun startsWith(file: Path, prefix: ByteArray): Boolean =
        Files.newInputStream(file).use { it.readNBytes(prefix.size).contentEquals(prefix) }

    private fun sha1(file: Path, skip: Long): String {
        val digest = MessageDigest.getInstance("SHA-1")
        Files.newInputStream(file).use { input ->
            input.skipNBytes(skip)
            val buf = ByteArray(1 shl 20)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

/** One file's identification. [tags] replace the file's own; fields it doesn't name are kept. */
private class Found(val how: String, val platform: String?, val tags: Tags, val release: String? = null)

/** Identifies [files] and writes what it learned as one revision. */
internal fun Repository.identifyGames(deviceId: String, files: List<Version>, job: GameJob): Long? {
    val index = GameIndex.open(gameDb)
    try {
        val found = LinkedHashMap<String, Found>()
        for (file in files) {
            try {
                found[file.assetId] = identify(file, index)
                if (found[file.assetId]!!.how in How.MATCHED) job.matched++
            } catch (e: Exception) {
                job.failed += "${RepoPaths.name(file.path)}: ${e.message ?: e.javaClass.simpleName}"
            }
            job.done++
        }
        fromSiblings(found, files, job)
        for (entry in found.entries) if (entry.value.how == How.UNKNOWN) entry.setValue(byName(files.first { it.assetId == entry.key }, entry.value, index))
        if (found.isEmpty()) return null
        val games = found.values.filter { it.how != How.UNKNOWN }.map { it.platform to it.tags[TagField.TITLE] }.toSet().size
        return write {
            val scope = RevisionScope(this, deviceId, "Identified ${if (games == 1) "a game" else "$games games"}")
            for ((id, f) in found) {
                val before = files.first { it.assetId == id }
                val head = headVersion(id)?.takeIf { it.rev == before.rev && !it.deleted } ?: continue
                // What's found replaces every detail of the game the file was taken for before.
                val kept = if (f.tags.isEmpty()) head.tags else head.tags - TagField.GAME.toSet()
                scope.write(head.copy(category = f.platform ?: head.category, tags = policy.filter(AssetKind.GAME, kept + f.tags)))
                db.exec("INSERT OR REPLACE INTO game_ident (asset_id, how, at) VALUES (?, ?, ?)", id, f.how, clock())
            }
            scope.finish()
        }
    } finally {
        index?.close()
    }
}

private fun Repository.identify(file: Version, index: GameIndex?): Found {
    if (index == null) return Found(How.UNKNOWN, file.category, emptyMap())
    val check = file.renditions.firstOrNull()?.let(::gameCheck) ?: return Found(How.UNKNOWN, file.category, emptyMap())
    val dumps = listOfNotNull(check.sha1, check.bareSha1).flatMap(index::bySha1)
    // The same dump can be in two lists (a PlayStation disc and its PSP re-release, say): the
    // platform the file already has wins.
    dumps.sortedByDescending { it.platform == file.category }.firstOrNull()?.let { return found(How.CHECKSUM, it, index) }
    check.serial?.let { serial ->
        val platforms = if (check.ps2) listOf("ps2") else listOf("ps1", "ps2")
        index.bySerial(serial, platforms)?.let { return found(How.SERIAL, it, index) }
    }
    return Found(How.UNKNOWN, file.category, emptyMap())
}

/** A dump's details, with LaunchBox's for its game. */
private fun found(how: String, dump: Dump, index: GameIndex): Found {
    val meta = index.meta(dump.platform, GameNames.titleOf(dump.release))
    return Found(how, dump.platform, gameTags(GameNames.titleOf(dump.release), GameNames.regionOf(dump.release) ?: dump.region, GameNames.discOf(dump.release), dump.serial, dump.release, meta), dump.release)
}

private fun gameTags(title: String, region: String?, disc: String?, serial: String?, release: String?, meta: GameMeta?): Tags = buildMap {
    put(TagField.TITLE, listOf(meta?.title ?: title))
    region?.let { put(TagField.REGION, listOf(it)) }
    disc?.let { put(TagField.DISC_NUMBER, listOf(it)) }
    serial?.let { put(TagField.SERIAL, listOf(it)) }
    release?.let { put(TagField.RELEASE, listOf(it)) }
    meta?.year?.let { put(TagField.YEAR, listOf(it)) }
    meta?.publisher?.let { put(TagField.PUBLISHER, listOf(it)) }
    meta?.developer?.let { put(TagField.DEVELOPER, listOf(it)) }
    meta?.genres?.takeIf { it.isNotEmpty() }?.let { put(TagField.GENRE, it) }
    meta?.players?.let { put(TagField.PLAYERS, listOf(it)) }
}

/** Files that only describe or complete a disc image, and are named after it. */
private val COMPANIONS = setOf("cue", "m3u", "gdi", "ccd", "sub", "mds", "toc")

/**
 * Whether two names (without extension) are the same file's: equal, or one is the other with a
 * qualifier added ("Game (USA)" and "Game (USA) (Track 2)"). Never a mere prefix: "Crash Bandicoot
 * 2" isn't "Crash Bandicoot".
 */
internal fun sameName(a: String, b: String) = a.equals(b, ignoreCase = true) ||
    a.startsWith("$b (", ignoreCase = true) || b.startsWith("$a (", ignoreCase = true)

/**
 * A file with no match of its own takes the game of a matched file beside it (in the same folder,
 * or imported from the same one) that it's named after: "Game (USA).cue" and "Game (USA) (Track
 * 2).bin" go with "Game (USA) (Track 1).bin", "Crash 3.cue" with "Crash 3.bin". A .cue or other
 * companion named after nothing there joins the only game there, if there's just one.
 */
private fun Repository.fromSiblings(found: MutableMap<String, Found>, files: List<Version>, job: GameJob) {
    val idents = gameIdents()
    for (file in files) {
        val own = found[file.assetId] ?: continue
        if (own.how != How.UNKNOWN) continue
        val stem = originalName(file.assetId) ?: RepoPaths.name(file.path).substringBeforeLast('.')
        val siblings = (db.read { liveIn(RepoPaths.parent(file.path)) } + importedBeside(file.assetId).mapNotNull(::headVersion))
            .filter { it.assetId != file.assetId && it.kind == AssetKind.GAME && !it.deleted }
            .distinctBy { it.assetId }
            .mapNotNull { s ->
                val f = found[s.assetId]?.takeIf { it.how in How.MATCHED }
                when {
                    f != null -> s to f
                    idents[s.assetId] in How.MATCHED -> s to Found(How.SIBLING, s.category, s.tags, s.tags[TagField.RELEASE]?.firstOrNull())
                    else -> null
                }
            }
        val named = siblings.filter { (s, f) ->
            val sStem = originalName(s.assetId) ?: RepoPaths.name(s.path).substringBeforeLast('.')
            sameName(stem, sStem) || f.release?.let { sameName(stem, it) } == true
        }.maxByOrNull { it.second.release?.length ?: 0 }
        val companion = formatOf(file)?.lowercase() in COMPANIONS
        val only = if (companion) siblings.map { it.second }.distinctBy { it.platform to it.tags[TagField.TITLE] }.singleOrNull() else null
        val from = named?.second ?: only ?: continue
        // The game's details, but not what's particular to the other file.
        found[file.assetId] = Found(How.SIBLING, from.platform, from.tags - TagField.SERIAL - TagField.RELEASE)
        job.matched++
    }
}

/** Unmatched: the platform guessed on import and the title from the name, with LaunchBox's details if it knows the title. */
private fun Repository.byName(file: Version, own: Found, index: GameIndex?): Found {
    val platform = own.platform ?: file.category ?: return Found(How.UNKNOWN, null, emptyMap())
    val guess = GameNames.guess(originalName(file.assetId) ?: RepoPaths.name(file.path))
    val title = file.tags[TagField.TITLE]?.firstOrNull() ?: guess.title
    val meta = index?.meta(platform, title) ?: return Found(How.UNKNOWN, platform, emptyMap())
    val tags = gameTags(title, file.tags[TagField.REGION]?.firstOrNull() ?: guess.region, file.tags[TagField.DISC_NUMBER]?.firstOrNull() ?: guess.disc, null, null, meta)
    return Found(How.NAME, platform, tags)
}

/** Applies a game chosen by hand in the web UI (a database search) to every file of a game. */
fun Repository.applyGame(deviceId: String, files: List<Version>, meta: GameMeta): Long? = write {
    val scope = RevisionScope(this, deviceId, "Set ${files.firstOrNull()?.let { gameLabel(it) } ?: "a game"} to ${meta.title}")
    for (file in files) {
        val head = headVersion(file.assetId)?.takeIf { !it.deleted } ?: continue
        val tags = head.tags - TagField.PUBLISHER - TagField.DEVELOPER - TagField.GENRE - TagField.PLAYERS - TagField.YEAR +
            gameTags(meta.title, null, null, null, null, meta)
        scope.write(head.copy(category = meta.platform, tags = policy.filter(AssetKind.GAME, tags)))
        db.exec("INSERT OR REPLACE INTO game_ident (asset_id, how, at) VALUES (?, ?, ?)", file.assetId, How.NAME, clock())
    }
    scope.finish()
}

/** "Shadow of the Colossus (USA)", or the file's folder or name. */
fun gameLabel(v: Version): String {
    val title = v.tags[TagField.TITLE]?.firstOrNull() ?: return RepoPaths.gameFolder(v.path)?.let(RepoPaths::name) ?: RepoPaths.name(v.path)
    val region = v.tags[TagField.REGION]?.firstOrNull()
    return if (region == null || title.endsWith("($region)")) title else "$title ($region)"
}
