package com.swordfish.lemuroid.lib.storage

import java.io.File

/**
 * Pure decision behind [SmartStoragePicker] — no Android types, so it is unit-testable.
 *
 * The ROMs directory must be stable across processes: every catalog row stores an absolute
 * `fileUri` under it, so a different answer on the next launch re-points nothing and silently
 * splits the library between volumes. See the bug doc
 * 2026-10-02-smartstoragepicker-pasta-roms-alterna-entre-volumes.md (documentacao/bugs).
 */
object RomsDirChoice {
    const val ROMS_DIR_NAME = "roms"

    /**
     * Path fragment shared by the app-managed ROMs dir of EVERY volume
     * (`<volume>/Android/data/<pkg>/files/roms/...`). Matches both paths and `file://` URIs,
     * so rows on a volume other than the current one are still recognised as catalog rows.
     */
    fun managedRomsMarker(packageName: String): String = "/Android/data/$packageName/files/$ROMS_DIR_NAME/"

    /** The managed ROMs root containing [pathOrUri] (no trailing slash), or null if outside any. */
    fun managedRomsRoot(
        pathOrUri: String,
        marker: String,
    ): String? {
        val at = pathOrUri.indexOf(marker)
        if (at < 0) return null
        return pathOrUri.substring(0, at + marker.length - 1)
    }

    /**
     * @param dir the ROMs directory to use for this process.
     * @param persist true when [dir] is a new decision that must be saved; false when it is the
     *        saved one or a fallback valid only for this process.
     */
    data class Result(
        val dir: File,
        val persist: Boolean,
    )

    /**
     * @param stored the ROMs directory saved by a previous decision, or null if none.
     * @param volumes writable app-specific external files dirs, primary first.
     * @param userSelectedSaf the user picked a custom folder via SAF.
     * @param freeBytes free space of a volume.
     * @param fallback directory to use when no volume is available at all.
     */
    fun choose(
        stored: File?,
        volumes: List<File>,
        userSelectedSaf: Boolean,
        freeBytes: (File) -> Long,
        fallback: File,
    ): Result {
        val primary = volumes.firstOrNull()

        if (stored != null) {
            // Saved volume still mounted and writable → keep it, whatever the free space says.
            if (stored.parentFile in volumes) return Result(stored, persist = false)
            // Saved volume missing (pendrive unplugged, or not mounted yet at boot). Fall back
            // for this process only: the saved choice wins again once the volume comes back.
            return Result(primary?.let { File(it, ROMS_DIR_NAME) } ?: fallback, persist = false)
        }

        if (primary == null) return Result(fallback, persist = false)

        // Upgrade from a version that re-chose on every launch: a volume that already has a
        // `roms` dir is where the library lives. Several of them means it already alternated —
        // pick among those, the downloads stay readable either way.
        val withLibrary = volumes.filter { File(it, ROMS_DIR_NAME).isDirectory }
        val candidates =
            when {
                withLibrary.size == 1 -> return Result(File(withLibrary[0], ROMS_DIR_NAME), persist = true)
                withLibrary.size > 1 -> withLibrary
                userSelectedSaf -> listOf(primary)
                else -> volumes
            }

        val best = candidates.maxByOrNull(freeBytes) ?: primary
        return Result(File(best, ROMS_DIR_NAME), persist = true)
    }
}
