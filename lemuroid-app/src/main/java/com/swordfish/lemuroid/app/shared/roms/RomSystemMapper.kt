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
        "ngc"          to "neogeocd",
        // Bandai
        "ws"           to "wswan",
        "wsc"          to "wswanc",
        // Arcade — server uses "fbneo" as the system name for all arcade ROMs
        // ROMs are MAME 0.78 romset → use mame2003plus core locally, but server stores them under "fbneo"
        "fbneo"        to "fbneo",       // SystemID.FBNEO dbname
        "mame2003plus" to "fbneo",       // SystemID.MAME2003PLUS dbname (served from fbneo collection)
        "arcade"       to "fbneo",       // HuggingFace folder alias (mame 0.78, served as fbneo on server)
    )

    /**
     * Returns the endpoint system name for the given Lemuroid [systemId],
     * or null if the system is not supported by the remote endpoint.
     */
    fun toEndpointSystem(systemId: String): String? = SYSTEM_TO_ENDPOINT[systemId]
}
