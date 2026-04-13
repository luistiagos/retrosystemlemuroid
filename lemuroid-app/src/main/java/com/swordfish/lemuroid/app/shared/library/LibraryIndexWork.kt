package com.swordfish.lemuroid.app.shared.library

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import com.swordfish.lemuroid.app.mobile.shared.NotificationsManager
import com.swordfish.lemuroid.app.utils.android.createSyncForegroundInfo
import com.swordfish.lemuroid.lib.injection.AndroidWorkerInjection
import com.swordfish.lemuroid.lib.injection.WorkerKey
import com.swordfish.lemuroid.lib.library.LemuroidLibrary
import dagger.Binds
import dagger.android.AndroidInjector
import dagger.multibindings.IntoMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject

class LibraryIndexWork(context: Context, workerParams: WorkerParameters) :
    CoroutineWorker(context, workerParams) {
    @Inject
    lateinit var lemuroidLibrary: LemuroidLibrary

    override suspend fun doWork(): Result {
        AndroidWorkerInjection.inject(this)

        val notificationsManager = NotificationsManager(applicationContext)

        val foregroundInfo =
            createSyncForegroundInfo(
                NotificationsManager.LIBRARY_INDEXING_NOTIFICATION_ID,
                notificationsManager.libraryIndexingNotification(),
            )

        // On Android 16+, DATA_SYNC FGS type was removed; setForeground may throw.
        // Proceed without a notification rather than crashing.
        try {
            setForeground(foregroundInfo)
        } catch (e: Exception) {
            Timber.w(e, "LibraryIndexWork: setForeground failed (${e.message}), continuing without notification")
        }

        val result =
            withContext(Dispatchers.IO) {
                kotlin.runCatching {
                    lemuroidLibrary.indexLibrary()
                }
            }

        result.exceptionOrNull()?.let {
            if (it is CancellationException) throw it
            Timber.e(it, "Library indexing work terminated with an exception")
        }

        return Result.success()
    }

    @dagger.Module(subcomponents = [Subcomponent::class])
    abstract class Module {
        @Binds
        @IntoMap
        @WorkerKey(LibraryIndexWork::class)
        abstract fun bindMyWorkerFactory(builder: Subcomponent.Builder): AndroidInjector.Factory<out ListenableWorker>
    }

    @dagger.Subcomponent
    interface Subcomponent : AndroidInjector<LibraryIndexWork> {
        @dagger.Subcomponent.Builder
        abstract class Builder : AndroidInjector.Builder<LibraryIndexWork>()
    }
}
