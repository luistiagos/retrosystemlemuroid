package com.swordfish.lemuroid.app.shared.startup

import android.content.Context
import androidx.startup.Initializer
import androidx.work.WorkManagerInitializer
import com.swordfish.lemuroid.app.shared.bios.EmbeddedBiosInstaller
import com.swordfish.lemuroid.app.shared.library.LibraryIndexScheduler
import com.swordfish.lemuroid.app.shared.savesync.SaveSyncWork
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import timber.log.Timber

class MainProcessInitializer : Initializer<Unit> {
    @OptIn(DelicateCoroutinesApi::class)
    override fun create(context: Context) {
        Timber.i("Requested initialization of main process tasks")
        SaveSyncWork.enqueueAutoWork(context, 0)
        LibraryIndexScheduler.scheduleCoreUpdate(context)
        // Delay BIOS installation slightly so the main UI is visible before touching the filesystem.
        // GlobalScope is acceptable here because the Initializer has no lifecycle of its own.
        // If the process is killed before the delay elapses the installation will be retried
        // on the next launch since EmbeddedBiosInstaller.installIfNeeded() is idempotent.
        GlobalScope.launch {
            kotlinx.coroutines.delay(5_000L)
            try {
                EmbeddedBiosInstaller.installIfNeeded(context)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                Timber.e(e, "MainProcessInitializer: BIOS installation failed")
            }
        }
    }

    override fun dependencies(): List<Class<out Initializer<*>>> {
        return listOf(WorkManagerInitializer::class.java, DebugInitializer::class.java)
    }
}
