package com.swordfish.lemuroid.app

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import androidx.startup.AppInitializer
import androidx.work.Configuration
import androidx.work.ListenableWorker
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.imageLoader
import com.google.android.material.color.DynamicColors
import com.swordfish.lemuroid.app.shared.covers.CoverUtils
import com.swordfish.lemuroid.app.shared.startup.GameProcessInitializer
import com.swordfish.lemuroid.app.shared.startup.MainProcessInitializer
import com.swordfish.lemuroid.app.shared.startup.NativeTempDir
import com.swordfish.lemuroid.app.shared.telemetry.CrashTelemetry
import com.swordfish.lemuroid.app.utils.android.isMainProcess
import com.swordfish.lemuroid.ext.feature.context.ContextHandler
import com.swordfish.lemuroid.lib.injection.HasWorkerInjector
import com.swordfish.lemuroid.lib.library.catalog.ManifestQuickLoader
import com.swordfish.lemuroid.lib.preferences.LocaleHelper
import dagger.android.AndroidInjector
import dagger.android.DispatchingAndroidInjector
import dagger.android.support.DaggerApplication
import org.conscrypt.Conscrypt
import timber.log.Timber
import java.security.Security
import javax.inject.Inject

class LemuroidApplication :
    DaggerApplication(),
    HasWorkerInjector,
    ImageLoaderFactory,
    Configuration.Provider {
    @Inject
    lateinit var workerInjector: DispatchingAndroidInjector<ListenableWorker>

    @Inject
    lateinit var manifestQuickLoader: ManifestQuickLoader

    /**
     * Resolved once. Below API 28 naming the process is a binder round-trip, and this is asked on
     * every onTrimMemory; the process a live Application belongs to never changes.
     */
    private val runningInMainProcess: Boolean by lazy { isMainProcess() }

    @SuppressLint("CheckResult")
    override fun onCreate() {
        super.onCreate()

        // Error telemetry, first thing — a crash during the rest of onCreate should still report.
        // Installed in BOTH processes: the emulator (and most crashes) live in ":game", and a
        // handler installed only under isMainProcess() would miss exactly those.
        // The process tag is passed as a lambda so that nothing has to be resolved *before* the
        // handler exists — naming the process is itself a call that fails on old TV boxes.
        // BaseGameActivity later chains its own handler on top of this one.
        CrashTelemetry.installUncaughtHandler(this) { if (runningInMainProcess) "main" else "game" }

        // Native TMPDIR, before anything can load a core. Unconditional for the same reason as the
        // handler above: a process misdetected as "main" would skip it, and this has to hold in
        // ":game", where the cores run. Costs a stat and a mkdir on an existing directory.
        NativeTempDir.install(this)

        if (runningInMainProcess) {
            // Native crash / ANR / low-memory kill never unwind through Java, so they are recovered
            // from the previous session via ApplicationExitInfo. The API is scoped to the package,
            // so this single scan also covers the ":game" process.
            CrashTelemetry.reportPastExitsAsync(this)
        }

        // Install Conscrypt in background — no HTTP calls happen before the UI is visible,
        // and each OkHttpClient also applies Conscrypt explicitly via applyConscryptTls().
        Thread {
            try {
                Security.insertProviderAt(Conscrypt.newProvider(), 1)
                Timber.d("Conscrypt provider installed")
            } catch (e: Throwable) {
                Timber.e(e, "Failed to install Conscrypt provider")
            }
        }.start()

        // Pre-warm Coil ImageLoader on background thread so that the first AsyncImage
        // composable doesn't trigger getCacheDir() disk I/O on the main thread (~143ms).
        // Only in the main (UI) process — the :game process never shows cover art and
        // must keep every spare MB for the emulator core on weak devices.
        if (runningInMainProcess) {
            Thread { imageLoader }.start()
        }

        val initializeComponent =
            if (runningInMainProcess) {
                MainProcessInitializer::class.java
            } else {
                GameProcessInitializer::class.java
            }

        AppInitializer.getInstance(this).initializeComponent(initializeComponent)

        DynamicColors.applyToActivitiesIfAvailable(this)
    }

    override fun attachBaseContext(base: Context) {
        // Pre-load locale SharedPreferences on a background thread so that the Activity's
        // attachBaseContext does not block on disk I/O (~1.1s on first cold start).
        LocaleHelper.preload(base)
        super.attachBaseContext(LocaleHelper.wrapContext(base))
        ContextHandler.attachBaseContext(base)
    }

    override fun applicationInjector(): AndroidInjector<out DaggerApplication> {
        return DaggerLemuroidApplicationComponent.builder().create(this)
    }

    override fun workerInjector(): AndroidInjector<ListenableWorker> = workerInjector

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setInitializationExceptionHandler { throwable ->
                Timber.e(throwable, "WorkManager initialization failed (bad storage or file system state)")
            }
            .build()

    override fun newImageLoader(): ImageLoader {
        return CoverUtils.buildImageLoader(applicationContext)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Only the main (UI) process has an image cache to trim. Touching
        // applicationContext.imageLoader in the :game process would lazily *build* an
        // ImageLoader just to clear it — wasteful exactly when memory is scarce.
        if (!runningInMainProcess) return
        when {
            // App went to background — drop the whole image memory cache; it can be
            // rebuilt from the disk cache cheaply when the user returns.
            level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> {
                Timber.d("onTrimMemory level=$level — UI hidden, clearing Coil memory cache")
                applicationContext.imageLoader.memoryCache?.clear()
            }
            // Foreground but the system is reclaiming memory. Trim proactively so we
            // free RAM before the LMK kills us (the main cause of crashes on TV boxes).
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> {
                Timber.d("onTrimMemory level=$level — running low, clearing Coil memory cache")
                applicationContext.imageLoader.memoryCache?.clear()
            }
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        if (!runningInMainProcess) return
        applicationContext.imageLoader.memoryCache?.clear()
    }

    companion object {
        fun isLowRamDevice(context: Context): Boolean {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            return am.isLowRamDevice
        }
    }
}
