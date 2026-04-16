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
 * Rules (in priority order):
 * 1. If the user has manually selected a folder via the SAF picker → do not interfere;
 *    return the standard external files dir (the download happened there before).
 * 2. If there is only one available external volume → use it as-is (no change).
 * 3. If multiple volumes are present (SD card, USB drive on Smart TV, etc.) →
 *    compare available free space on each volume and pick the one with the most free
 *    space.  If the primary volume wins, nothing changes.  If a removable volume wins,
 *    ROMs will be stored there.
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
     * Clears the cached directory so the next [getBestRomsDirectory] call
     * re-evaluates volumes. Call after the user changes their SAF folder
     * or when storage media is mounted/unmounted.
     */
    fun invalidateCache() {
        cachedBestRomsDir = null
    }

    private fun computeBestRomsDirectory(context: Context): File {
        val appContext = context.applicationContext

        // Rule 1: user manually selected a folder → respect their choice.
        val userSelected = SharedPreferencesHelper
            .getLegacySharedPreferences(appContext)
            .getString(appContext.getString(R.string.pref_key_extenral_folder), null)

        if (!userSelected.isNullOrEmpty()) {
            Timber.d("SmartStoragePicker: user has a custom SAF folder — keeping default roms dir")
            return defaultRomsDir(appContext)
        }

        // Gather all writable external volumes available to this app.
        val volumes = appContext
            .getExternalFilesDirs(null)
            .filterNotNull()
            .filter { it.exists() && it.canWrite() }

        // Rule 2: only one volume available.
        if (volumes.size <= 1) {
            Timber.d("SmartStoragePicker: single volume — using primary")
            return File(volumes.firstOrNull() ?: defaultRomsDir(appContext), "roms")
                .apply { mkdirs() }
        }

        // Rule 3: pick the volume with the most free space.
        val best = volumes.maxByOrNull { freeBytes(it) } ?: volumes.first()
        val primary = volumes.first()

        return if (best == primary) {
            Timber.d("SmartStoragePicker: primary has most free space (${freeBytes(primary) / MB}MB) — no change")
            defaultRomsDir(appContext)
        } else {
            Timber.i(
                "SmartStoragePicker: removable volume chosen — " +
                    "${freeBytes(best) / MB}MB free vs ${freeBytes(primary) / MB}MB on primary"
            )
            File(best, "roms").apply { mkdirs() }
        }
    }

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
        val userSelected = SharedPreferencesHelper
            .getLegacySharedPreferences(appContext)
            .getString(appContext.getString(R.string.pref_key_extenral_folder), null)
        if (!userSelected.isNullOrEmpty()) return false

        val volumes = appContext
            .getExternalFilesDirs(null)
            .filterNotNull()
            .filter { it.exists() && it.canWrite() }
        if (volumes.size <= 1) return false

        val primary = volumes.first()
        val best = volumes.maxByOrNull { freeBytes(it) }
        return best != null && best != primary
    }

    // ──────────────────────────────────────────────────────────────────────────────────

    private const val MB = 1_048_576L

    private fun defaultRomsDir(context: Context): File =
        File(context.getExternalFilesDir(null), "roms").apply { mkdirs() }

    private fun freeBytes(dir: File): Long = try {
        StatFs(dir.path).availableBytes
    } catch (_: Exception) {
        0L
    }

    private fun totalBytes(dir: File): Long = try {
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
