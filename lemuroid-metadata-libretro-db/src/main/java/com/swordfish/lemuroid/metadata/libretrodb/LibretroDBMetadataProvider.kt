package com.swordfish.lemuroid.metadata.libretrodb

import com.swordfish.lemuroid.common.kotlin.filterNullable
import com.swordfish.lemuroid.lib.library.ArcadeSubSystemRoms
import com.swordfish.lemuroid.lib.library.GameSystem
import com.swordfish.lemuroid.lib.library.SystemID
import com.swordfish.lemuroid.lib.library.metadata.GameMetadata
import com.swordfish.lemuroid.lib.library.metadata.GameMetadataProvider
import com.swordfish.lemuroid.lib.storage.StorageFile
import com.swordfish.lemuroid.metadata.libretrodb.db.LibretroDBManager
import com.swordfish.lemuroid.metadata.libretrodb.db.LibretroDatabase
import com.swordfish.lemuroid.metadata.libretrodb.db.entity.LibretroRom
import timber.log.Timber
import java.util.Locale

class LibretroDBMetadataProvider(private val ovgdbManager: LibretroDBManager) :
    GameMetadataProvider {
    companion object {
        private val THUMB_REPLACE = Regex("[&*/:`<>?\\\\|]")

        /**
         * Maps well-known human-readable folder names (as found in ROM archives)
         * to the system dbname used by Lemuroid. Checked against exact path segments
         * (case-insensitive) so "nintendo" doesn't accidentally match "super nintendo".
         *
         * Uses List<Pair> instead of Map to allow the same folder to map to multiple
         * systems. "arcade/" contains both FBNeo and MAME 0.78 ROMs — LibretroDB
         * determines the actual system per file; we just need both to be eligible.
         */
        private val FOLDER_ALIASES: List<Pair<String, String>> = listOf(
            // Short folder names used in the luisluis123/lemusets dataset
            "a26"                  to "atari2600",
            "a78"                  to "atari7800",
            // arcade/ contains MAME 0.78 ROMs. Map to both systems so that
            // findByPathAndFilename can score FBNeo vs MAME2003Plus per-game;
            // the path-preference sort will pick MAME2003Plus when "mame2003plus"
            // is NOT an exact segment, falling back to whichever system LibretroDB
            // actually knows the ROM under.
            "arcade"               to "fbneo",
            "arcade"               to "mame2003plus",
            "atari 2600"           to "atari2600",
            "atari 7800"           to "atari7800",
            "game boy"             to "gb",
            "game boy advance"     to "gba",
            "game boy color"       to "gbc",
            "gameboy"              to "gb",
            "gameboy advance"      to "gba",
            "gameboy color"        to "gbc",
            "mastersystem"         to "sms",
            "master system"        to "sms",
            "sega master system"   to "sms",
            "megadrive"            to "md",
            "mega drive"           to "md",
            "genesis"              to "md",
            "sega megadrive"       to "md",
            "sega genesis"         to "md",
            "neo geo pocket"       to "ngp",
            "neo-geo pocket"       to "ngp",
            "ngpc"                 to "ngc",
            "neo geo pocket color" to "ngc",
            "neogeo"               to "neogeo",
            "neo-geo"              to "neogeo",
            "neo geo"              to "neogeo",
            // Neo Geo ROMs often reside inside a generic arcade/ or fbneo/ folder;
            // map both to neogeo so parentContainsSystem() can pass for them.
            "arcade"               to "neogeo",
            "fbneo"                to "neogeo",
            // CPS-1 ROMs also reside inside arcade/ or fbneo/ folders; map to cps1.
            "arcade"               to "cps1",
            "fbneo"                to "cps1",
            "cps1"                 to "cps1",
            // CPS-2 and CPS-3 ROMs follow the same folder conventions.
            "arcade"               to "cps2",
            "fbneo"                to "cps2",
            "cps2"                 to "cps2",
            "arcade"               to "cps3",
            "fbneo"                to "cps3",
            "cps3"                 to "cps3",
            // Data East ROMs also reside inside arcade/ or fbneo/ folders.
            "arcade"               to "dataeast",
            "fbneo"                to "dataeast",
            "dataeast"             to "dataeast",
            // Galaxian-hardware ROMs also reside inside arcade/ or fbneo/ folders.
            "arcade"               to "galaxian",
            "fbneo"                to "galaxian",
            "galaxian"             to "galaxian",
            // Toaplan, Taito, Psikyo, PGM, Kaneko, Cave, Technos, Seta ROMs reside in arcade/ or fbneo/ folders.
            "arcade"               to "toaplan",
            "fbneo"                to "toaplan",
            "toaplan"              to "toaplan",
            "arcade"               to "taito",
            "fbneo"                to "taito",
            "taito"                to "taito",
            "arcade"               to "psikyo",
            "fbneo"                to "psikyo",
            "psikyo"               to "psikyo",
            "arcade"               to "pgm",
            "fbneo"                to "pgm",
            "pgm"                  to "pgm",
            "arcade"               to "kaneko",
            "fbneo"                to "kaneko",
            "kaneko"               to "kaneko",
            "arcade"               to "cave",
            "fbneo"                to "cave",
            "cave"                 to "cave",
            "arcade"               to "technos",
            "fbneo"                to "technos",
            "technos"              to "technos",
            "arcade"               to "seta",
            "fbneo"                to "seta",
            "seta"                 to "seta",
            "nintendo"             to "nes",
            "nes"                  to "nes",
            "nintendo 64"          to "n64",
            "n64"                  to "n64",
            "nintendo ds"          to "nds",
            "nds"                  to "nds",
            "playstation"          to "psx",
            "psx"                  to "psx",
            "ps1"                  to "psx",
            "ps one"               to "psx",
            "playstation portable" to "psp",
            "psp"                  to "psp",
            "super nintendo"       to "snes",
            "snes"                 to "snes",
            "super nes"            to "snes",
            "pc engine"            to "pce",
            "pc-engine"            to "pce",
            "turbografx"           to "pce",
            "turbografx-16"        to "pce",
            "game gear"            to "gg",
            "gamegear"             to "gg",
            "lynx"                 to "lynx",
            "atari lynx"           to "lynx",
            "wonderswan"           to "ws",
            "wonder swan"          to "ws",
            "wonderswan color"     to "wsc",
            "wonder swan color"    to "wsc",
            "sega cd"              to "scd",
            "mega cd"              to "scd",
            "segacd"               to "scd",
            "megacd"               to "scd",
            "sega-cd"              to "scd",
            "mega-cd"              to "scd",
            "scd"                  to "scd",
            "mame"                 to "mame2003plus",
            "mame 2003"            to "mame2003plus",
            "mame2003"             to "mame2003plus",
            "mame 2003 plus"       to "mame2003plus",
            "mame2003plus"         to "mame2003plus",
        )
    }

    private val sortedSystemIds: List<String> by lazy {
        SystemID.values()
            .map { it.dbname }
            .sortedByDescending { it.length }
    }

    override suspend fun retrieveMetadata(storageFile: StorageFile): GameMetadata? {
        val db = ovgdbManager.dbInstance

        Timber.d("Looking metadata for file: $storageFile")

        val metadata =
            runCatching {
                findByCRC(storageFile, db)
                    ?: findBySerial(storageFile, db)
                    ?: findByFilename(db, storageFile)
                    ?: findByPathAndFilename(db, storageFile)
                    ?: findByUniqueExtension(storageFile)
                    ?: findByKnownSystem(storageFile)
                    ?: findByPathAndSupportedExtension(storageFile)
            }.getOrElse {
                Timber.e("Error in retrieving $storageFile metadata: $it... Skipping.")
                null
            }

        metadata?.let { Timber.d("Metadata retrieved for item: $it") }

        return metadata
    }

    private fun convertToGameMetadata(rom: LibretroRom): GameMetadata {
        val system = extractGameSystem(rom)
        return GameMetadata(
            name = rom.name,
            romName = rom.romName,
            thumbnail = computeCoverUrl(system, rom.name),
            system = system.id.dbname,
            developer = rom.developer,
        )
    }

    private suspend fun findByFilename(
        db: LibretroDatabase,
        file: StorageFile,
    ): GameMetadata? {
        return db.gameDao().findByFileName(file.name)
            .filterNullable { extractGameSystem(it).scanOptions.scanByFilename }
            ?.let { convertToGameMetadata(it) }
    }

    private suspend fun findByPathAndFilename(
        db: LibretroDatabase,
        file: StorageFile,
    ): GameMetadata? {
        // Fetch ALL rows matching this filename — a game can appear in multiple system DBs
        // (e.g. both FBNeo and MAME2003Plus contain many of the same ZIP names but with
        // different CRCs). Sort candidates so the best-matching system wins:
        //   score 3 — libretro-db or a curated dedicated-system override identifies this
        //             file as Neo Geo/CPS (highest confidence; these ROMs are reliably
        //             identified by filename alone without needing a dedicated folder)
        //   score 2 — dbname is an exact path segment
        //   score 1 — path contains a folder alias that maps DIRECTLY to this system,
        //             preferring the system whose alias is listed FIRST in FOLDER_ALIASES
        //             (mame2003plus entries come after fbneo but we bias mame2003plus here
        //             because the luisluis123/lemusets arcade folder ships MAME 0.78 ROMs)
        val segments = file.path?.lowercase(Locale.getDefault())
            ?.split('/', '\\')?.toHashSet() ?: emptySet()
        return db.gameDao().findAllByFileName(file.name)
            .filter { extractGameSystem(it).scanOptions.scanByPathAndFilename }
            .filter { parentContainsSystem(file.path, extractGameSystem(it).id.dbname) }
            .sortedByDescending { rom ->
                val dbname = extractGameSystem(rom).id.dbname
                when {
                    // Dedicated-system matches beat generic arcade folder heuristics.
                    dbname == "neogeo" || dbname == "cps1" || dbname == "cps2" || dbname == "cps3" || dbname == "dataeast" || dbname == "galaxian" || dbname == "toaplan" || dbname == "taito" || dbname == "psikyo" || dbname == "pgm" || dbname == "kaneko" || dbname == "cave" || dbname == "technos" || dbname == "seta" -> 3
                    // Exact path segment — high confidence
                    segments.contains(dbname) -> 2
                    // Alias-only match: bias mame2003plus over fbneo for "arcade/" folder
                    // because the dataset uses MAME 0.78 romset throughout
                    segments.contains("arcade") && dbname == "mame2003plus" -> 1
                    else -> 0
                }
            }
            .firstOrNull()
            ?.let { convertToGameMetadata(it) }
    }

    private fun findByPathAndSupportedExtension(file: StorageFile): GameMetadata? {
        val system =
            sortedSystemIds
                .filter { parentContainsSystem(file.path, it) }
                .map { GameSystem.findById(it) }
                .filter { it.scanOptions.scanByPathAndSupportedExtensions }
                .firstOrNull { it.supportedExtensions.contains(file.extension) }

        return system?.let {
            GameMetadata(
                name = file.extensionlessName,
                romName = file.name,
                thumbnail = null,
                system = it.id.dbname,
                developer = null,
            )
        }
    }

    private fun parentContainsSystem(
        parent: String?,
        dbname: String,
    ): Boolean {
        val lowercasePath = parent?.lowercase(Locale.getDefault()) ?: return false
        // Split into path segments and do exact segment matching.
        // Substring matching (e.g. "path.contains(dbname)") causes false positives:
        // "snes" contains "nes", "gba" contains "gb", "wsc" contains "ws", etc.
        val segments = lowercasePath.split('/', '\\').toHashSet()
        // Fast path: one of the path segments IS the dbname exactly.
        if (segments.contains(dbname)) return true
        // Slow path: check known human-readable folder aliases.
        return FOLDER_ALIASES.any { (alias, mappedDbname) ->
            mappedDbname == dbname && segments.contains(alias)
        }
    }

    private suspend fun findByCRC(
        file: StorageFile,
        db: LibretroDatabase,
    ): GameMetadata? {
        if (file.crc == null || file.crc == "0") return null
        return file.crc?.let { crc32 -> db.gameDao().findByCRC(crc32) }
            ?.let { convertToGameMetadata(it) }
    }

    private suspend fun findBySerial(
        file: StorageFile,
        db: LibretroDatabase,
    ): GameMetadata? {
        if (file.serial == null) return null
        return db.gameDao().findBySerial(file.serial!!)
            ?.let { convertToGameMetadata(it) }
    }

    private fun findByKnownSystem(file: StorageFile): GameMetadata? {
        if (file.systemID == null) return null

        return GameMetadata(
            name = file.extensionlessName,
            romName = file.name,
            thumbnail = null,
            system = file.systemID!!.dbname,
            developer = null,
        )
    }

    private fun findByUniqueExtension(file: StorageFile): GameMetadata? {
        val system = GameSystem.findByUniqueFileExtension(file.extension)

        if (system?.scanOptions?.scanByUniqueExtension == false) {
            return null
        }

        val result =
            system?.let {
                GameMetadata(
                    name = file.extensionlessName,
                    romName = file.name,
                    thumbnail = null,
                    system = it.id.dbname,
                    developer = null,
                )
            }

        return result
    }

    private fun extractGameSystem(rom: LibretroRom): GameSystem {
        val dedicatedSystemId = ArcadeSubSystemRoms.dedicatedSystemIdForRom(rom.romName)
        return GameSystem.findById(dedicatedSystemId ?: rom.system!!)
    }

    private fun computeCoverUrl(
        system: GameSystem,
        name: String?,
    ): String? {
        var systemName = system.libretroFullName

        // Specific mame version don't have any thumbnails in Libretro database
        if (system.id == SystemID.MAME2003PLUS) {
            systemName = "MAME"
        }

        if (name == null) {
            return null
        }

        val imageType = "Named_Boxarts"

        val thumbGameName = name.replace(THUMB_REPLACE, "_")

        return "https://thumbnails.libretro.com/$systemName/$imageType/$thumbGameName.png"
    }
}
