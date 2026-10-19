package com.tlisonbee.mediasync.library

/**
 * A game platform. [id] is a game's category; [name] is its folder in games/. [list] names its
 * No-Intro or Redump list in the libretro database (metadat/[listFolder]/[list].dat), which
 * identifies a file by its checksum; [launchBox] its platform in the LaunchBox games database,
 * which has each game's year, publisher, developer and genres.
 */
data class Platform(
    val id: String,
    val name: String,
    val list: String,
    val listFolder: String,
    val launchBox: String,
    /** File extensions only this platform uses, for guessing a file's platform before it's identified. */
    val extensions: Set<String> = emptySet(),
    /** Words in a folder name that mean this platform ("ps2"), compared without case or punctuation. */
    val aliases: Set<String> = emptySet(),
    /** Discs say which game they are in a SYSTEM.CNF file (the PlayStations before the PSP). */
    val systemCnf: Boolean = false,
) {
    val disc get() = listFolder == REDUMP

    companion object {
        const val REDUMP = "redump"
        const val NO_INTRO = "no-intro"
    }
}

object Platforms {
    val ALL = listOf(
        Platform("ps1", "PlayStation", "Sony - PlayStation", Platform.REDUMP, "Sony Playstation", aliases = setOf("ps1", "psx", "psone", "playstation", "playstation1"), systemCnf = true),
        Platform("ps2", "PlayStation 2", "Sony - PlayStation 2", Platform.REDUMP, "Sony Playstation 2", aliases = setOf("ps2", "playstation2"), systemCnf = true),
        Platform("psp", "PlayStation Portable", "Sony - PlayStation Portable", Platform.REDUMP, "Sony PSP", setOf("cso"), setOf("psp", "playstationportable")),
        Platform("gc", "GameCube", "Nintendo - GameCube", Platform.REDUMP, "Nintendo GameCube", setOf("gcm", "gcz"), setOf("gc", "gcn", "ngc", "gamecube")),
        Platform("wii", "Wii", "Nintendo - Wii", Platform.REDUMP, "Nintendo Wii", setOf("wbfs"), setOf("wii")),
        Platform("xbox", "Xbox", "Microsoft - Xbox", Platform.REDUMP, "Microsoft Xbox", aliases = setOf("xbox", "originalxbox")),
        Platform("saturn", "Saturn", "Sega - Saturn", Platform.REDUMP, "Sega Saturn", aliases = setOf("saturn", "segasaturn")),
        Platform("dc", "Dreamcast", "Sega - Dreamcast", Platform.REDUMP, "Sega Dreamcast", setOf("gdi", "cdi"), setOf("dc", "dreamcast")),
        Platform("nes", "NES", "Nintendo - Nintendo Entertainment System", Platform.NO_INTRO, "Nintendo Entertainment System", setOf("nes", "unf", "unif"), setOf("nes", "famicom", "nintendoentertainmentsystem")),
        Platform("snes", "Super NES", "Nintendo - Super Nintendo Entertainment System", Platform.NO_INTRO, "Super Nintendo Entertainment System", setOf("sfc", "smc", "swc", "fig"), setOf("snes", "sfc", "supernes", "supernintendo", "superfamicom")),
        Platform("n64", "Nintendo 64", "Nintendo - Nintendo 64", Platform.NO_INTRO, "Nintendo 64", setOf("n64", "z64", "v64"), setOf("n64", "nintendo64")),
        Platform("gb", "Game Boy", "Nintendo - Game Boy", Platform.NO_INTRO, "Nintendo Game Boy", setOf("gb"), setOf("gb", "gameboy")),
        Platform("gbc", "Game Boy Color", "Nintendo - Game Boy Color", Platform.NO_INTRO, "Nintendo Game Boy Color", setOf("gbc"), setOf("gbc", "gameboycolor")),
        Platform("gba", "Game Boy Advance", "Nintendo - Game Boy Advance", Platform.NO_INTRO, "Nintendo Game Boy Advance", setOf("gba"), setOf("gba", "gameboyadvance")),
        Platform("nds", "Nintendo DS", "Nintendo - Nintendo DS", Platform.NO_INTRO, "Nintendo DS", setOf("nds"), setOf("nds", "ds", "nintendods")),
        Platform("genesis", "Genesis", "Sega - Mega Drive - Genesis", Platform.NO_INTRO, "Sega Genesis", setOf("md", "gen", "smd"), setOf("genesis", "megadrive", "segagenesis")),
        Platform("sms", "Master System", "Sega - Master System - Mark III", Platform.NO_INTRO, "Sega Master System", setOf("sms"), setOf("sms", "mastersystem")),
        Platform("gg", "Game Gear", "Sega - Game Gear", Platform.NO_INTRO, "Sega Game Gear", setOf("gg"), setOf("gg", "gamegear")),
    )

    private val byId = ALL.associateBy { it.id }

    fun byId(id: String?): Platform? = id?.let(byId::get)

    /** The platform whose folder is [name] ("PlayStation 2"), compared without case. */
    fun byFolder(name: String): Platform? = ALL.firstOrNull { it.name.equals(name, ignoreCase = true) }

    /**
     * A first guess at a file's platform, before the game databases have seen it: from an extension
     * only one platform uses, or else a folder on its way named after one ("PS2/", "Games/GameCube").
     */
    fun guess(path: String): Platform? {
        val parts = path.replace('\\', '/').split('/').filter { it.isNotEmpty() }
        val ext = parts.lastOrNull()?.substringAfterLast('.', "")?.lowercase().orEmpty()
        ALL.firstOrNull { ext in it.extensions }?.let { return it }
        for (folder in parts.dropLast(1).asReversed()) {
            val key = folder.lowercase().filter { it.isLetterOrDigit() }
            ALL.firstOrNull { key in it.aliases || key == it.name.lowercase().filter(Char::isLetterOrDigit) }?.let { return it }
        }
        return null
    }
}
