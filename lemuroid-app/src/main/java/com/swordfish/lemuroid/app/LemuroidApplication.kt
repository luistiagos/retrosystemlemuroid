package com.swordfish.lemuroid.app

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import androidx.startup.AppInitializer
import androidx.work.ListenableWorker
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.imageLoader
import com.google.android.material.color.DynamicColors
import com.swordfish.lemuroid.app.shared.covers.CoverUtils
import com.swordfish.lemuroid.app.shared.startup.GameProcessInitializer
import com.swordfish.lemuroid.app.shared.startup.MainProcessInitializer
import com.swordfish.lemuroid.app.utils.android.isMainProcess
import com.swordfish.lemuroid.ext.feature.context.ContextHandler
import com.swordfish.lemuroid.lib.injection.HasWorkerInjector
import com.swordfish.lemuroid.lib.preferences.LocaleHelper
import dagger.android.AndroidInjector
import dagger.android.DispatchingAndroidInjector
import dagger.android.support.DaggerApplication
import org.conscrypt.Conscrypt
import timber.log.Timber
import java.security.Security
import javax.inject.Inject

class LemuroidApplication : DaggerApplication(), HasWorkerInjector, ImageLoaderFactory {
    @Inject
    lateinit var workerInjector: DispatchingAndroidInjector<ListenableWorker>

    @SuppressLint("CheckResult")
    override fun onCreate() {
        super.onCreate()
        Timber.d("PERF T0_APP_ONCREATE ms=0 (baseline)")

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
        Thread { imageLoader }.start()

        val initializeComponent =
            if (isMainProcess()) {
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

    override fun newImageLoader(): ImageLoader {
        return CoverUtils.buildImageLoader(applicationContext)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE) {
            Timber.d("onTrimMemory level=$level — clearing Coil memory cache")
            applicationContext.imageLoader.memoryCache?.clear()
        }
    }

    companion object {
        fun isLowRamDevice(context: Context): Boolean {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            return am.isLowRamDevice
        }
    }
}
