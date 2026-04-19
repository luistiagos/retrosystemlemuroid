package com.swordfish.lemuroid.app.shared.roms

/**
 * Maps Lemuroid systemId values (which match the HuggingFace folder names) to the
 * system names expected by the remote endpoint at emuladores.pythonanywhere.com.
 *
 * The JSON file mnemonico_map.json at the project root is the source of truth for
 * this map; it is embedded here to avoid runtime file I/O.
 */
object RomSystemMapper {

    private val SYSTEM_TO_ENDPOINT: Map<String, String> = mapOf(
        // Atari
        "atari2600"    to "atari2600",   // SystemID.ATARI2600 dbname
        "a26"          to "atari2600",   // HuggingFace folder alias
        "atari7800"    to "atari7800",   // SystemID.ATARI7800 dbname
        "a78"          to "atari7800",   // HuggingFace folder alias
        "atari5200"    to "atari5200",   // SystemID.ATARI5200 dbname
        "a52"          to "atari5200",   // HuggingFace folder alias
        "lynx"         to "lynx",
        // Nintendo
        "nes"          to "nes",
        "snes"         to "snes",
        "gb"           to "gb",
        "gbc"          to "gbc",
        "gba"          to "gba",
        "n64"          to "n64",
        "nds"          to "nds",
        "3ds"          to "3ds",
        // Sega
        "md"           to "megadrive",   // SystemID.GENESIS dbname
        "megadrive"    to "megadrive",   // HuggingFace folder alias
        "scd"          to "megacd",      // SystemID.SEGACD dbname
        "megacd"       to "megacd",      // HuggingFace folder alias
        "sms"          to "mastersystem",
        "gg"           to "gamegear",
        // Sony
        "psx"          to "psx",
        "psp"          to "psp",
        // NEC
        "pce"          to "pcengine",
        // SNK
        "ngp"          to "ngp",
        "ngc"          to "ngpc",
        // Bandai
        "ws"           to "wswan",
        "wsc"          to "wswanc",
        // Arcade — server uses "fbneo" as the system name for all arcade ROMs
        // ROMs are MAME 0.78 romset → use mame2003plus core locally, but server stores them under "fbneo"
        "fbneo"        to "fbneo",       // SystemID.FBNEO dbname
        "neogeo"       to "neogeo",      // SystemID.NEOGEO dbname
        "cps1"         to "fbneo",        // SystemID.CPS1 dbname (served by fbneo endpoint)
        "cps2"         to "fbneo",        // SystemID.CPS2 dbname (served by fbneo endpoint)
        "cps3"         to "fbneo",        // SystemID.CPS3 dbname (served by fbneo endpoint)
        "dataeast"     to "fbneo",        // SystemID.DATAEAST dbname (served by fbneo endpoint)
        "galaxian"     to "fbneo",        // SystemID.GALAXIAN dbname (served by fbneo endpoint)
        "toaplan"      to "fbneo",        // SystemID.TOAPLAN dbname (served by fbneo endpoint)
        "taito"        to "fbneo",        // SystemID.TAITO dbname (served by fbneo endpoint)
        "psikyo"       to "fbneo",        // SystemID.PSIKYO dbname (served by fbneo endpoint)
        "pgm"          to "fbneo",        // SystemID.PGM dbname (served by fbneo endpoint)
        "kaneko"       to "fbneo",        // SystemID.KANEKO dbname (served by fbneo endpoint)
        "cave"         to "fbneo",        // SystemID.CAVE dbname (served by fbneo endpoint)
        "technos"      to "fbneo",        // SystemID.TECHNOS dbname (served by fbneo endpoint)
        "seta"         to "fbneo",        // SystemID.SETA dbname (served by fbneo endpoint)
        "mame2003plus" to "mame2003plus",       // SystemID.MAME2003PLUS dbname (served from fbneo collection)
        "arcade"       to "mame2003plus",       // HuggingFace folder alias (mame 0.78, served as fbneo on server)
        // MSX
        "msx"          to "msx",
        "msx2"         to "msx2",
    )

    /**
     * Returns the endpoint system name for the given Lemuroid [systemId],
     * or null if the system is not supported by the remote endpoint.
     */
    fun toEndpointSystem(systemId: String): String? = SYSTEM_TO_ENDPOINT[systemId]
}
