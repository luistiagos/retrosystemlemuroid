package com.swordfish.lemuroid.app.shared.library

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import com.swordfish.lemuroid.app.mobile.shared.NotificationsManager
import com.swordfish.lemuroid.app.utils.android.createSyncForegroundInfo
import com.swordfish.lemuroid.lib.core.CoreUpdater
import com.swordfish.lemuroid.lib.core.CoresSelection
import com.swordfish.lemuroid.lib.injection.AndroidWorkerInjection
import com.swordfish.lemuroid.lib.injection.WorkerKey
import com.swordfish.lemuroid.lib.library.CoreID
import com.swordfish.lemuroid.lib.library.GameSystem
import com.swordfish.lemuroid.lib.library.db.RetrogradeDatabase
import dagger.Binds
import dagger.android.AndroidInjector
import dagger.multibindings.IntoMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import timber.log.Timber
import javax.inject.Inject

class CoreUpdateWork(context: Context, workerParams: WorkerParameters) :
    CoroutineWorker(context, workerParams) {
    @Inject
    lateinit var retrogradeDatabase: RetrogradeDatabase

    @Inject
    lateinit var coreUpdater: CoreUpdater

    @Inject
    lateinit var coresSelection: CoresSelection

    override suspend fun doWork(): Result {
        AndroidWorkerInjection.inject(this)

        Timber.i("Starting core update/install work")

        val notificationsManager = NotificationsManager(applicationContext)

        val foregroundInfo =
            createSyncForegroundInfo(
                NotificationsManager.CORE_INSTALL_NOTIFICATION_ID,
                notificationsManager.installingCoresNotification(),
            )

        // Attempt to show a progress notification. On Android 16+, DATA_SYNC FGS was removed:
        // if setForeground() throws, we still want the download to proceed.
        try {
            setForeground(foregroundInfo)
        } catch (e: Exception) {
            Timber.w(e, "CoreUpdateWork: setForeground failed (${e.message}), continuing without notification")
        }

        var hadFailure = false
        try {
            // If a specific coreID was requested (e.g. triggered from the game screen),
            // only download that one core instead of all cores — much faster.
            val specificCoreId = inputData.getString(KEY_CORE_ID)
            val cores = if (specificCoreId != null) {
                Timber.i("Downloading specific core: $specificCoreId")
                listOf(CoreID.valueOf(specificCoreId))
            } else {
                retrogradeDatabase.gameDao().selectSystems()
                    .asFlow()
                    .map { GameSystem.findById(it) }
                    .map { coresSelection.getCoreConfigForSystem(it) }
                    .map { it.coreID }
                    .toList()
            }

            coreUpdater.downloadCores(applicationContext, cores)
        } catch (e: CancellationException) {
            throw e
        } catch (e: java.io.IOException) {
            Timber.e(e, "Core update work failed with I/O exception: ${e.message}")
            hadFailure = true
        } catch (e: Throwable) {
            Timber.e(e, "Core update work failed with exception: ${e.message}")
            hadFailure = true
        }

        return if (hadFailure) Result.retry() else Result.success()
    }

    companion object {
        /** Optional: if set, only this core (by CoreID.name) is downloaded instead of all cores. */
        const val KEY_CORE_ID = "key_core_id"
    }

    @dagger.Module(subcomponents = [Subcomponent::class])
    abstract class Module {
        @Binds
        @IntoMap
        @WorkerKey(CoreUpdateWork::class)
        abstract fun bindMyWorkerFactory(builder: Subcomponent.Builder): AndroidInjector.Factory<out ListenableWorker>
    }

    @dagger.Subcomponent
    interface Subcomponent : AndroidInjector<CoreUpdateWork> {
        @dagger.Subcomponent.Builder
        abstract class Builder : AndroidInjector.Builder<CoreUpdateWork>()
    }
}
