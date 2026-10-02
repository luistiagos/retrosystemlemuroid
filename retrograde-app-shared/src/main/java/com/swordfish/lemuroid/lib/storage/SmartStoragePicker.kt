package com.swordfish.lemuroid.lib.storage

import android.content.Context
import android.os.StatFs
import com.swordfish.lemuroid.lib.R
import com.swordfish.lemuroid.lib.preferences.SharedPreferencesHelper
import timber.log.Timber
import java.io.File

/**
 * Selects the optimal storage volume for ROM downloads.
 *
 * The volume is decided ONCE and saved; later launches reuse it while it stays mounted.
 * Re-choosing by free space on every launch made the ROMs dir jump between volumes and
 * split the catalog (see [RomsDirChoice] for the rules of the first decision).
 *
 * All returned directories are app-specific (`getExternalFilesDirs`), so no special
 * storage permissions are required.
 */
object SmartStoragePicker {

    @Volatile
    private var cachedBestRomsDir: File? = null

    /**
     * Returns the [File] directory that should be used as the ROMs root.
     * The directory is created (`mkdirs`) before being returned.
     * The result is cached after the first call to avoid repeated filesystem
     * and StatFs queries on every access.
     */
    fun getBestRomsDirectory(context: Context): File {
        cachedBestRomsDir?.let { return it }
        return computeBestRomsDirectory(context).also { cachedBestRomsDir = it }
    }

    /**
     * Clears the in-process cache. The saved choice still wins on the next call; this only
     * matters when the saved volume was missing and the process fell back to the primary.
     */
    fun invalidateCache() {
        cachedBestRomsDir = null
    }

    private fun computeBestRomsDirectory(context: Context): File {
        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // A user-picked SAF folder keeps the ROMs dir on the primary volume (downloads lived
        // there before smart selection existed). Only weighs on the first decision.
        val userSelected =
            SharedPreferencesHelper
                .getLegacySharedPreferences(appContext)
                .getString(appContext.getString(R.string.pref_key_extenral_folder), null)

        val result =
            RomsDirChoice.choose(
                stored = prefs.getString(KEY_ROMS_DIR, null)?.let { File(it) },
                volumes = writableVolumes(appContext),
                userSelectedSaf = !userSelected.isNullOrEmpty(),
                freeBytes = ::freeBytes,
                fallback = defaultRomsDir(appContext),
            )

        if (result.persist) {
            // commit, not apply: the :game process reads this file at its own start.
            prefs.edit().putString(KEY_ROMS_DIR, result.dir.absolutePath).commit()
            val freeMb = freeBytes(result.dir.parentFile ?: result.dir) / MB
            Timber.i("SmartStoragePicker: ROMs dir chosen and saved — ${result.dir} ($freeMb MB free)")
        } else {
            Timber.d("SmartStoragePicker: ROMs dir ${result.dir}")
        }
        return result.dir.apply { mkdirs() }
    }

    private fun writableVolumes(context: Context): List<File> =
        context
            .getExternalFilesDirs(null)
            .filterNotNull()
            .filter { it.exists() && it.canWrite() }

    /**
     * Returns a snapshot of all detected external volumes with their free-space info.
     * Useful for display in the Settings screen.
     */
    fun getVolumeInfoList(context: Context): List<VolumeInfo> {
        val appContext = context.applicationContext
        val primary = appContext.getExternalFilesDir(null)
        return appContext
            .getExternalFilesDirs(null)
            .filterNotNull()
            .filter { it.exists() }
            .mapIndexed { index, dir ->
                VolumeInfo(
                    directory = dir,
                    freeSpaceBytes = freeBytes(dir),
                    totalSpaceBytes = totalBytes(dir),
                    isRemovable = dir != primary,
                    index = index,
                )
            }
    }

    /**
     * Returns true if smart-selection chose a removable volume (SD card / USB drive).
     */
    fun isUsingRemovableStorage(context: Context): Boolean {
        val appContext = context.applicationContext
        val primary = appContext.getExternalFilesDir(null) ?: return false
        return getBestRomsDirectory(appContext).parentFile != primary
    }

    // ──────────────────────────────────────────────────────────────────────────────────

    private const val MB = 1_048_576L

    private const val PREFS_NAME = "smart_storage_prefs"
    private const val KEY_ROMS_DIR = "roms_dir"

    // No mkdirs here: this is evaluated before RomsDirChoice.choose, and a `roms` dir created on
    // the primary at that point makes the "volume that already holds the library" rule pick it.
    private fun defaultRomsDir(context: Context): File = File(context.getExternalFilesDir(null), "roms")

    private fun freeBytes(dir: File): Long =
        try {
            StatFs(dir.path).availableBytes
        } catch (_: Exception) {
            0L
        }

    private fun totalBytes(dir: File): Long =
        try {
            StatFs(dir.path).totalBytes
        } catch (_: Exception) {
            0L
        }

    // ──────────────────────────────────────────────────────────────────────────────────

    data class VolumeInfo(
        val directory: File,
        val freeSpaceBytes: Long,
        val totalSpaceBytes: Long,
        /** True for SD card, USB drive, etc.  False for built-in flash / emulated storage. */
        val isRemovable: Boolean,
        /** 0 = primary, 1+ = secondary volumes */
        val index: Int,
    ) {
        val freeSpaceMB: Long get() = freeSpaceBytes / 1_048_576L
        val totalSpaceMB: Long get() = totalSpaceBytes / 1_048_576L
    }
}
