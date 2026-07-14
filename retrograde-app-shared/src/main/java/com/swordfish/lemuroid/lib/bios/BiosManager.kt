package com.swordfish.lemuroid.lib.bios

import com.swordfish.lemuroid.common.files.safeDelete
import com.swordfish.lemuroid.common.kotlin.associateByNotNull
import com.swordfish.lemuroid.common.kotlin.writeToFile
import com.swordfish.lemuroid.lib.library.SystemCoreConfig
import com.swordfish.lemuroid.lib.library.SystemID
import com.swordfish.lemuroid.lib.library.db.entity.Game
import com.swordfish.lemuroid.lib.storage.DirectoriesManager
import com.swordfish.lemuroid.lib.storage.StorageFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

class BiosManager(private val directoriesManager: DirectoriesManager) {
    private val crcLookup = SUPPORTED_BIOS.associateByNotNull { it.externalCRC32 }
    private val nameLookup = SUPPORTED_BIOS.associateByNotNull { it.externalName }

    fun getMissingBiosFiles(
        coreConfig: SystemCoreConfig,
        game: Game,
    ): List<String> {
        val regionalBiosFiles = coreConfig.regionalBIOSFiles

        val gameLabels =
            Regex("\\([A-Za-z]+\\)")
                .findAll(game.title)
                .map { it.value.drop(1).dropLast(1) }
                .filter { it.isNotBlank() }
                .toSet()

        Timber.d("Found game labels: $gameLabels")

        val requiredRegionalFiles =
            gameLabels.intersect(regionalBiosFiles.keys)
                .ifEmpty { regionalBiosFiles.keys }
                .mapNotNull { regionalBiosFiles[it] }

        Timber.d("Required regional files for game: $requiredRegionalFiles")

        val systemDirectory = directoriesManager.getSystemDirectory()
        return (coreConfig.requiredBIOSFiles + requiredRegionalFiles)
            .filter { !isBiosFileAvailable(systemDirectory, it) }
    }

    fun deleteBiosBefore(timestampMs: Long) {
        Timber.i("Pruning old bios files")
        SUPPORTED_BIOS
            .map { File(directoriesManager.getSystemDirectory(), it.libretroFileName) }
            .filter { it.lastModified() < normalizeTimestamp(timestampMs) }
            .forEach {
                Timber.d("Pruning old bios file: ${it.path}")
                it.safeDelete()
            }
    }

    private fun buildBiosInfo(): BiosInfo {
        val systemDirectory = directoriesManager.getSystemDirectory()
        val bios =
            SUPPORTED_BIOS.groupBy {
                isBiosFileAvailable(systemDirectory, it.libretroFileName)
            }.withDefault { listOf() }

        return BiosInfo(bios.getValue(true), bios.getValue(false))
    }

    @Deprecated("Use getBiosInfoAsync()")
    fun getBiosInfo(): BiosInfo = buildBiosInfo()

    suspend fun getBiosInfoAsync(): BiosInfo =
        withContext(Dispatchers.IO) {
            buildBiosInfo()
        }

    fun tryAddBiosAfter(
        storageFile: StorageFile,
        inputStream: InputStream,
        timestampMs: Long,
    ): Boolean {
        val bios = findByCRC(storageFile) ?: findByName(storageFile) ?: return false

        Timber.i("Importing bios file: $bios")

        val biosFile = File(directoriesManager.getSystemDirectory(), bios.libretroFileName)
        // Create parent directory if needed (e.g. dc/ for Dreamcast BIOS)
        biosFile.parentFile?.mkdirs()
        if (biosFile.exists() && biosFile.setLastModified(normalizeTimestamp(timestampMs))) {
            Timber.d("Bios file already present. Updated last modification date.")
        } else {
            Timber.d("Bios file not available. Copying new file.")
            inputStream.writeToFile(biosFile)
        }
        return true
    }

    private fun findByCRC(storageFile: StorageFile): Bios? {
        return crcLookup[storageFile.crc]
    }

    private fun findByName(storageFile: StorageFile): Bios? {
        return nameLookup[storageFile.name]
    }

    private fun isBiosFileAvailable(systemDirectory: File, fileName: String): Boolean {
        val biosFile = File(systemDirectory, fileName)
        if (!biosFile.exists()) return false

        val expectedMd5s = biosEntryFor(fileName)?.md5?.split(",") ?: return true
        val actualMd5 = runCatching { md5Hex(biosFile) }
            .onFailure { Timber.w(it, "Failed to calculate BIOS MD5: ${biosFile.path}") }
            .getOrNull()

        val isValid = actualMd5 != null && expectedMd5s.any { it.equals(actualMd5, ignoreCase = true) }
        if (!isValid) {
            Timber.w("Invalid BIOS file: ${biosFile.path} expected=$expectedMd5s actual=$actualMd5")
            biosFile.safeDelete()
            return false
        }

        return true
    }

    private fun md5Hex(file: File): String {
        val digest = MessageDigest.getInstance("MD5")
        file.inputStream().use { input ->
            val buffer = ByteArray(256 * 1024)
            var bytesRead: Int
            while (input.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().joinToString("") { "%02X".format(it) }
    }

    private fun normalizeTimestamp(timestamp: Long) = (timestamp / 1000) * 1000

    data class BiosInfo(val detected: List<Bios>, val notDetected: List<Bios>)

    companion object {
        val SUPPORTED_BIOS =
            listOf(
                Bios(
                    "scph101.bin",
                    "6E3735FF4C7DC899EE98981385F6F3D0",
                    "PS One 4.5 NTSC-U/C",
                    SystemID.PSX,
                    "171BDCEC",
                ),
                Bios(
                    "scph7001.bin",
                    "1E68C231D0896B7EADCAD1D7D8E76129",
                    "PS Original 4.1 NTSC-U/C",
                    SystemID.PSX,
                    "502224B6",
                ),
                Bios(
                    "scph5501.bin",
                    "490F666E1AFB15B7362B406ED1CEA246",
                    "PS Original 3.0 NTSC-U/C",
                    SystemID.PSX,
                    "8D8CB7E4",
                ),
                Bios(
                    "scph1001.bin",
                    "924E392ED05558FFDB115408C263DCCF",
                    "PS Original 2.2 NTSC-U/C",
                    SystemID.PSX,
                    "37157331",
                ),
                Bios(
                    "lynxboot.img",
                    "FCD403DB69F54290B51035D82F835E7B",
                    "Lynx Boot Image",
                    SystemID.LYNX,
                    "0D973C9D",
                ),
                Bios(
                    "bios_CD_E.bin",
                    "E66FA1DC5820D254611FDCDBA0662372",
                    "Sega CD E",
                    SystemID.SEGACD,
                    "529AC15A",
                ),
                Bios(
                    "bios_CD_J.bin",
                    "278A9397D192149E84E820AC621A8EDD",
                    "Sega CD J",
                    SystemID.SEGACD,
                    "9D2DA8F2",
                ),
                Bios(
                    "bios_CD_U.bin",
                    "2EFD74E3232FF260E371B99F84024F7F",
                    "Sega CD U",
                    SystemID.SEGACD,
                    "C6D10268",
                ),
                Bios(
                    "bios7.bin",
                    "DF692A80A5B1BC90728BC3DFC76CD948",
                    "Nintendo DS ARM7",
                    SystemID.NDS,
                    "1280F0D5",
                ),
                Bios(
                    "bios9.bin",
                    "A392174EB3E572FED6447E956BDE4B25",
                    "Nintendo DS ARM9",
                    SystemID.NDS,
                    "2AB23573",
                ),
                Bios(
                    "firmware.bin",
                    "3C704824663CE26B6A1ED4D85238AE5B",
                    "Nintendo DS Firmware",
                    SystemID.NDS,
                    "13046805",
                ),
                Bios(
                    "gba_bios.bin",
                    "A860E8C0B6D573D191E4EC7AB1FCE4AB",
                    "Game Boy Advance BIOS",
                    SystemID.GBA,
                    "81977335",
                ),
                Bios(
                    "MSX.ROM",
                    "364A1A579FE5CB8DBA54519BCFCDAC0D",
                    "MSX BIOS",
                    SystemID.MSX,
                ),
                Bios(
                    "MSX2.ROM",
                    "EC3A01C91F24FBDDCBCAB0AD301BC9EF",
                    "MSX2 BIOS",
                    SystemID.MSX2,
                ),
                Bios(
                    "MSX2EXT.ROM",
                    "2183C2AFF17CF4297BDB496DE78C2E8A",
                    "MSX2 Extended BIOS",
                    SystemID.MSX2,
                ),
                Bios(
                    "MSX2P.ROM",
                    "847CC025FFAE665487940FF2639540E5",
                    "MSX2+ BIOS",
                    SystemID.MSX2,
                ),
                Bios(
                    "MSX2PEXT.ROM",
                    "7C8243C71D8F143B2531F01AFA6A05DC",
                    "MSX2+ Extended BIOS",
                    SystemID.MSX2,
                ),
                Bios(
                    "MSXDOS2.ROM",
                    "6418D091CD6907BBCF940324339E43BB",
                    "MSX-DOS2 BIOS",
                    SystemID.MSX,
                ),
                Bios(
                    "neogeo.zip",
                    "872DEA2E508FB1332E0FD4D5F9A2D15A,DFFB72F116D36D025068B23970A4F6DF",
                    "Neo Geo BIOS",
                    SystemID.FBNEO,
                    "362E948D",
                ),
                Bios(
                    "coleco.rom",
                    "2C66F5911E5B42B8EBE113403548EEE7",
                    "ColecoVision BIOS",
                    SystemID.COLECOVISION,
                    "3AA93EF3",
                ),
                Bios(
                    "exec.bin",
                    "62E761035CB657903761800F4437B8AF",
                    "Intellivision EXEC ROM",
                    SystemID.INTELLIVISION,
                    "CBCE86F7",
                ),
                Bios(
                    "grom.bin",
                    "0CD5946C6473E42E8E4C2137785E427F",
                    "Intellivision GROM",
                    SystemID.INTELLIVISION,
                    "683A4158",
                ),
                // 3DO BIOS (Opera) — file goes in system/ root (panafz1.bin)
                Bios(
                    "panafz1.bin",
                    "F47264DD47FE30F73AB3C010015C155B",
                    "3DO BIOS (Panasonic FZ-1)",
                    SystemID.THREE_DO,
                    "C8C8FF89",
                    "panafz1.bin",
                ),
                // Dreamcast BIOS (Flycast) — files go in system/dc/ subfolder
                Bios(
                    "dc/dc_boot.bin",
                    "E10C53C2F8B90BAB96EAD2D368858623",
                    "Dreamcast BIOS (World)",
                    SystemID.DREAMCAST,
                    "89F2B1A1",
                    "dc_boot.bin",
                ),
                Bios(
                    "dc/dc_flash.bin",
                    "0A93F7940C455905BEA6E392DFDE92A4",
                    "Dreamcast Flash ROM",
                    SystemID.DREAMCAST,
                    "C611B498",
                    "dc_flash.bin",
                ),
                // Famicom Disk System BIOS (FCEUmm)
                Bios(
                    "disksys.rom",
                    "CA30B50F880EB660A320674ED365EF7A",
                    "Famicom Disk System BIOS",
                    SystemID.FDS,
                    "5E607DCF",
                ),
                // Fairchild Channel F BIOS (FreeChaF) — files go in system/ root.
                // MD5/CRC32 computed from the files hosted on the HuggingFace dataset;
                // CRC32s match the canonical MAME "channelf" romset.
                Bios(
                    "sl31253.bin",
                    "AC9804D4C0E9D07E33472E3726ED15C3",
                    "Fairchild Channel F BIOS (PSU 1)",
                    SystemID.CHANNEL_F,
                    "04694ED9",
                ),
                Bios(
                    "sl31254.bin",
                    "DA98F4BB3242AB80D76629021BB27585",
                    "Fairchild Channel F BIOS (PSU 2)",
                    SystemID.CHANNEL_F,
                    "9C047BA3",
                ),
                // Sega Saturn BIOS (YabaSanshiro) — file goes in system/ root as saturn_bios.bin.
                // MD5/CRC32 computed from the file hosted on the HuggingFace dataset.
                Bios(
                    "saturn_bios.bin",
                    "AF5828FDFF51384F99B3C4926BE27762",
                    "Sega Saturn BIOS",
                    SystemID.SATURN,
                    "2ABA43C2",
                    "saturn_bios.bin",
                ),
                // Magnavox Odyssey2 BIOS (O2EM) — file in system/ root as o2rom.bin (G7000 model).
                // MD5/CRC32 computed from the file hosted on the HuggingFace dataset.
                Bios(
                    "o2rom.bin",
                    "562D5EBF9E030A40D6FABFC2F33139FD",
                    "Magnavox Odyssey2 BIOS (G7000)",
                    SystemID.ODYSSEY2,
                    "8016A315",
                    "o2rom.bin",
                ),
                // Neo Geo CD BIOS (NeoCD) — goes in system/neocd/. The Universe BIOS 3.2 is
                // region-free and auto-patched by the core. MD5/CRC32 computed from the file
                // hosted on the HuggingFace dataset (under the neocd/ subfolder). Both the
                // local path and the HF path carry the neocd/ prefix, so externalName matches.
                Bios(
                    "neocd/uni-bioscd.rom",
                    "08CA8B2DBA6662E8024F9E789711C6FC",
                    "Neo Geo CD BIOS (Universe BIOS 3.2)",
                    SystemID.NEOCD,
                    "FF3ABC59",
                    "neocd/uni-bioscd.rom",
                ),
                // Commodore Amiga Kickstart ROMs (PUAE) — files in system/ root.
                // PUAE picks the matching Kickstart per emulated model. MD5/CRC32 computed
                // from the files hosted on the HuggingFace dataset.
                Bios(
                    "kick34005.A500",
                    "82A21C1890CAE844B3DF741F2762D48D",
                    "Amiga Kickstart v1.3 (A500/A2000/CDTV)",
                    SystemID.AMIGA,
                    "C4F0F55F",
                    "kick34005.A500",
                ),
                Bios(
                    "kick40068.A1200",
                    "646773759326FBAC3B2311FD8C8793EE",
                    "Amiga Kickstart v3.1 (A1200)",
                    SystemID.AMIGA,
                    "1483A091",
                    "kick40068.A1200",
                ),
                // Amiga CD32 — Kickstart 3.1 + extended (CD) ROM, both in system/ root.
                Bios(
                    "kick40060.CD32",
                    "5F8924D013DD57A89CF349F4CDEDC6B1",
                    "Amiga CD32 Kickstart v3.1",
                    SystemID.AMIGA_CD32,
                    "1E62D4A5",
                    "kick40060.CD32",
                ),
                Bios(
                    "kick40060.CD32.ext",
                    "BB72565701B1B6FAECE07D68EA5DA639",
                    "Amiga CD32 extended ROM",
                    SystemID.AMIGA_CD32,
                    "87746BE2",
                    "kick40060.CD32.ext",
                ),
                // Amiga CDTV — extended (CD) ROM; pairs with the A500 KS 1.3 (kick34005.A500).
                Bios(
                    "kick34005.CDTV",
                    "89DA1838A24460E4B93F4F0C5D92D48D",
                    "Amiga CDTV extended ROM",
                    SystemID.AMIGA_CDTV,
                    "42BAA124",
                    "kick34005.CDTV",
                ),
                // NEC PC-FX BIOS (Beetle PC-FX) — file in system/ root as pcfx.rom.
                // MD5/CRC32 computed from the file hosted on the HuggingFace dataset.
                Bios(
                    "pcfx.rom",
                    "08E36EDBEA28A017F79F8D4F7FF9B6D7",
                    "NEC PC-FX BIOS",
                    SystemID.PCFX,
                    "76FFB97A",
                    "pcfx.rom",
                ),
            )

        fun biosEntryFor(fileName: String): Bios? =
            SUPPORTED_BIOS.firstOrNull { it.libretroFileName == fileName }
    }
}
