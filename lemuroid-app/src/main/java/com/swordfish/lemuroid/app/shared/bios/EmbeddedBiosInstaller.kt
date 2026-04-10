package com.swordfish.lemuroid.app.shared.bios

import android.content.Context
import com.swordfish.lemuroid.lib.storage.DirectoriesManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

/**
 * Installs embedded BIOS files bundled in assets/bios/ into the app's system directory.
 * Runs on every launch but skips files that are already present, so it is effectively
 * a no-op after the first install.
 */
object EmbeddedBiosInstaller {

    private val BIOS_FILES = listOf(
        // PlayStation
        "scph101.bin",
        "scph7001.bin",
        "scph5501.bin",
        "scph1001.bin",
        // Atari Lynx (required)
        "lynxboot.img",
        // Sega CD (regional)
        "bios_CD_E.bin",
        "bios_CD_J.bin",
        "bios_CD_U.bin",
        // Nintendo DS
        "bios7.bin",
        "bios9.bin",
        "firmware.bin",
        // Game Boy Advance (optional, improves compatibility)
        "gba_bios.bin",
    )

    suspend fun installIfNeeded(context: Context) = withContext(Dispatchers.IO) {
        val systemDir = DirectoriesManager(context).getSystemDirectory()
        for (fileName in BIOS_FILES) {
            val destination = File(systemDir, fileName)
            if (destination.exists()) {
                // Check if the installed file size matches the asset — catches interrupted copies.
                val expectedSize = runCatching {
                    context.assets.openFd("bios/$fileName").use { it.length }
                }.getOrElse { -1L }
                val sizeOk = expectedSize < 0L || destination.length() == expectedSize
                if (sizeOk && destination.length() > 0L) continue
                // Corrupt or empty — delete and re-install
                destination.delete()
            }
            try {
                context.assets.open("bios/$fileName").use { input ->
                    destination.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                Timber.i("EmbeddedBios: installed $fileName")
            } catch (e: Exception) {
                Timber.e(e, "EmbeddedBios: failed to install $fileName")
            }
        }
    }
}
