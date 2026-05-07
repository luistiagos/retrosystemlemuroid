package com.swordfish.lemuroid.app.shared.startup

import android.content.Context
import androidx.startup.Initializer
import androidx.work.WorkManagerInitializer
import androidx.work.WorkManager
import com.swordfish.lemuroid.app.LemuroidApplication
import com.swordfish.lemuroid.app.shared.bios.EmbeddedBiosInstaller
import com.swordfish.lemuroid.app.shared.library.LibraryIndexScheduler
import com.swordfish.lemuroid.app.shared.roms.StreamingRomsManager
import com.swordfish.lemuroid.app.shared.roms.StreamingRomsWork
import com.swordfish.lemuroid.app.shared.savesync.SaveSyncWork
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import timber.log.Timber

class MainProcessInitializer : Initializer<Unit> {
    @OptIn(DelicateCoroutinesApi::class)
    override fun create(context: Context) {
        Timber.i("Requested initialization of main process tasks")

        // Mark catalog as populated SYNCHRONOUSLY so StreamingRomsManager.init's
        // background Thread sees PREF_CATALOG_VERSION == CATALOG_VERSION and
        // PREF_DOWNLOAD_DONE == true before it can reset them and enqueue
        // StreamingRomsWork. SharedPreferences.apply() updates the in-memory cache
        // immediately, so all subsequent reads in this process see the new values
        // even before the async disk flush completes.
        StreamingRomsManager.markCatalogPopulated(context)
        WorkManager.getInstance(context).cancelUniqueWork(StreamingRomsWork.UNIQUE_WORK_ID)

        // DB insertion and placeholder creation — idempotent, safe every startup.
        // A small delay ensures Application.onCreate() + Dagger injection complete
        // before we try to access manifestQuickLoader (which is @Inject lateinit var).
        GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            kotlinx.coroutines.delay(500L)
            try {
                val app = context.applicationContext as? LemuroidApplication
                app?.manifestQuickLoader?.load()
            } catch (e: Throwable) {
                Timber.e(e, "MainProcessInitializer: manifest quick load failed")
            }
        }

        // Move WorkManager scheduling off the main thread so the UI becomes
        // responsive sooner — these calls resolve ContentProvider URIs and
        // touch the database internally.
        GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            SaveSyncWork.enqueueAutoWork(context, 0)
            LibraryIndexScheduler.scheduleCoreUpdate(context)
        }

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
