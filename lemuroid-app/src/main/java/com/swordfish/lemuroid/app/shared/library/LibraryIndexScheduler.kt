package com.swordfish.lemuroid.app.shared.library

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.swordfish.lemuroid.app.LemuroidApplication
import timber.log.Timber

object LibraryIndexScheduler {

    /**
     * Catalog-aware fast path: inserts new games from `catalog_manifest.txt`
     * into the DB without running the LibretroDB scan. Used after catalog
     * downloads (RomsDownloadManager, StreamingRomsManager, RomOnDemandManager)
     * since the files match the manifest 1:1 — no metadata inference needed.
     *
     * For ROMs the user adds outside the catalog (folder picker, SD/USB mount,
     * settings rescan) keep using [scheduleLibrarySync] / [scheduleManualLibrarySync]
     * — those go through LibretroDB so unknown files get proper system detection.
     */
    suspend fun triggerCatalogQuickLoad(applicationContext: Context) {
        try {
            val app = applicationContext.applicationContext as? LemuroidApplication
                ?: return
            app.manifestQuickLoader.load()
        } catch (e: Throwable) {
            Timber.e(e, "triggerCatalogQuickLoad failed")
        }
    }

    val CORE_UPDATE_WORK_ID: String = CoreUpdateWork::class.java.simpleName
    /** Separate ID for urgent single-core downloads triggered from the game screen. */
    val CORE_UPDATE_URGENT_WORK_ID: String = "${CoreUpdateWork::class.java.simpleName}_urgent"
    val LIBRARY_INDEX_WORK_ID: String = LibraryIndexWork::class.java.simpleName
    /** Separate ID for user-triggered scans (folder change, USB mount, etc.).  */
    val LIBRARY_INDEX_MANUAL_WORK_ID: String = "${LibraryIndexWork::class.java.simpleName}_manual"

    /** Called by automated processes (downloads, streaming catalog). Progress bar NOT shown. */
    fun scheduleLibrarySync(applicationContext: Context) {
        WorkManager.getInstance(applicationContext)
            .beginUniqueWork(
                LIBRARY_INDEX_WORK_ID,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequestBuilder<LibraryIndexWork>().build(),
            )
            .enqueue()
    }

    /** Called by user-triggered actions (folder change, USB/SD mount). Shows progress bar. */
    fun scheduleManualLibrarySync(applicationContext: Context) {
        WorkManager.getInstance(applicationContext)
            .beginUniqueWork(
                LIBRARY_INDEX_MANUAL_WORK_ID,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequestBuilder<LibraryIndexWork>().build(),
            )
            .enqueue()
    }

    fun scheduleCoreUpdate(applicationContext: Context, coreId: String? = null) {
        if (coreId != null) {
            // Urgent: a specific core is needed right now to play a game.
            // Use a separate work ID with REPLACE so it starts immediately,
            // without waiting for any in-progress all-cores update to finish.
            val inputData = androidx.work.workDataOf(CoreUpdateWork.KEY_CORE_ID to coreId)
            WorkManager.getInstance(applicationContext)
                .enqueueUniqueWork(
                    CORE_UPDATE_URGENT_WORK_ID,
                    ExistingWorkPolicy.REPLACE,
                    OneTimeWorkRequestBuilder<CoreUpdateWork>()
                        .setInputData(inputData)
                        .build(),
                )
        } else {
            // Background: download all cores needed by the library.
            // Delayed 2 minutes so it does not compete with app startup I/O on slow devices.
            WorkManager.getInstance(applicationContext)
                .beginUniqueWork(
                    CORE_UPDATE_WORK_ID,
                    ExistingWorkPolicy.APPEND_OR_REPLACE,
                    OneTimeWorkRequestBuilder<CoreUpdateWork>()
                        .setInitialDelay(2, java.util.concurrent.TimeUnit.MINUTES)
                        .build(),
                )
                .enqueue()
        }
    }

    fun cancelLibrarySync(applicationContext: Context) {
        WorkManager.getInstance(applicationContext).cancelUniqueWork(LIBRARY_INDEX_WORK_ID)
    }

    fun cancelCoreUpdate(applicationContext: Context) {
        WorkManager.getInstance(applicationContext).cancelUniqueWork(CORE_UPDATE_WORK_ID)
        WorkManager.getInstance(applicationContext).cancelUniqueWork(CORE_UPDATE_URGENT_WORK_ID)
    }
}
