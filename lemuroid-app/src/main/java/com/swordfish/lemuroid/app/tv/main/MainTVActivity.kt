package com.swordfish.lemuroid.app.tv.main

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.app.AlertDialog
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.ViewModelProvider
import com.swordfish.lemuroid.R
import com.swordfish.lemuroid.app.mobile.feature.shortcuts.ShortcutsGenerator
import com.swordfish.lemuroid.app.shared.GameInteractor
import com.swordfish.lemuroid.app.shared.game.BaseGameActivity
import com.swordfish.lemuroid.app.shared.game.CoreCrashFallback
import com.swordfish.lemuroid.app.shared.game.GameLauncher
import com.swordfish.lemuroid.app.shared.main.BusyActivity
import com.swordfish.lemuroid.app.shared.main.GameLaunchTaskHandler
import com.swordfish.lemuroid.app.shared.roms.RomOnDemandManager
import com.swordfish.lemuroid.app.tv.channel.ChannelUpdateWork
import com.swordfish.lemuroid.app.tv.favorites.TVFavoritesFragment
import com.swordfish.lemuroid.app.tv.game.TVRomDownloadDialog
import com.swordfish.lemuroid.app.tv.games.TVGamesFragment
import com.swordfish.lemuroid.app.tv.home.TVHomeFragment
import com.swordfish.lemuroid.app.tv.search.TVSearchFragment
import com.swordfish.lemuroid.app.tv.shared.BaseTVActivity
import com.swordfish.lemuroid.app.tv.shared.TVAppUpdateDialog
import com.swordfish.lemuroid.app.tv.shared.TVHelper
import com.swordfish.lemuroid.common.coroutines.launchOnState
import com.swordfish.lemuroid.common.coroutines.safeCollect
import kotlinx.coroutines.launch
import com.swordfish.lemuroid.lib.injection.PerActivity
import com.swordfish.lemuroid.lib.injection.PerFragment
import com.swordfish.lemuroid.lib.library.db.RetrogradeDatabase
import dagger.Provides
import dagger.android.ContributesAndroidInjector
import javax.inject.Inject

class MainTVActivity : BaseTVActivity(), BusyActivity {
    @Inject
    lateinit var gameLaunchTaskHandler: GameLaunchTaskHandler

    var mainViewModel: MainTVViewModel? = null

    override fun activity(): Activity = this

    override fun isBusy(): Boolean = mainViewModel?.inProgress?.value ?: false

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tv_main)

        val factory = MainTVViewModel.Factory(applicationContext)
        mainViewModel = ViewModelProvider(this, factory).get(MainTVViewModel::class.java)

        launchOnState(Lifecycle.State.CREATED) {
            mainViewModel?.inProgress?.safeCollect {
                findViewById<View>(R.id.tv_loading).isVisible = it
            }
        }

        ensureLegacyStoragePermissionsIfNeeded()

        // Aviso de versao nova ao entrar no app (silencioso, no maximo 1x/12h).
        TVAppUpdateDialog(this).checkOnStartup()

        // Mesma explicacao que a home do celular da: o jogo anterior morreu dentro do core, sem
        // excecao Java e sem tela de crash. A deteccao roda numa thread de fundo do
        // MainProcessInitializer, entao aqui e uma coleta, nao uma leitura unica.
        launchOnState(Lifecycle.State.STARTED) {
            CoreCrashFallback.pendingNotice().safeCollect { notice ->
                if (notice == null || isFinishing || isDestroyed) return@safeCollect
                CoreCrashFallback.consumeNotice(applicationContext)
                AlertDialog.Builder(this@MainTVActivity)
                    .setTitle(R.string.core_crash_notice_title)
                    .setMessage(
                        if (notice.optionDisabled) {
                            getString(
                                R.string.core_crash_notice_message_option_disabled,
                                notice.gameTitle,
                                notice.coreName,
                            )
                        } else {
                            getString(
                                R.string.core_crash_notice_message,
                                notice.gameTitle,
                                notice.coreName,
                            )
                        },
                    )
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
        }
    }

    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?,
    ) {
        super.onActivityResult(requestCode, resultCode, data)

        when (requestCode) {
            BaseGameActivity.REQUEST_PLAY_GAME -> {
                lifecycleScope.launch {
                    gameLaunchTaskHandler.handleGameFinish(false, this@MainTVActivity, resultCode, data)
                    ChannelUpdateWork.enqueue(applicationContext)
                }
            }
        }
    }

    private fun ensureLegacyStoragePermissionsIfNeeded() {
        if (TVHelper.isSAFSupported(this) || hasLegacyPermissions()) {
            return
        }

        val requestPermission = ActivityResultContracts.RequestPermission()
        val requestPermissionLauncher =
            registerForActivityResult(requestPermission) { isGranted ->
                if (!isGranted) {
                    finish()
                }
            }
        requestPermissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    private fun hasLegacyPermissions(): Boolean {
        val result = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
        return result == PackageManager.PERMISSION_GRANTED
    }

    @dagger.Module
    abstract class Module {
        @PerFragment
        @ContributesAndroidInjector(modules = [TVHomeFragment.Module::class])
        abstract fun tvHomeFragment(): TVHomeFragment

        @PerFragment
        @ContributesAndroidInjector(modules = [TVGamesFragment.Module::class])
        abstract fun tvGamesFragment(): TVGamesFragment

        @PerFragment
        @ContributesAndroidInjector(modules = [TVSearchFragment.Module::class])
        abstract fun tvSearchFragment(): TVSearchFragment

        @PerFragment
        @ContributesAndroidInjector(modules = [TVFavoritesFragment.Module::class])
        abstract fun tvFavoritesFragment(): TVFavoritesFragment

        @dagger.Module
        companion object {
            @Provides
            @PerActivity
            @JvmStatic
            fun gameInteractor(
                activity: MainTVActivity,
                retrogradeDb: RetrogradeDatabase,
                shortcutsGenerator: ShortcutsGenerator,
                gameLauncher: GameLauncher,
                romOnDemandManager: RomOnDemandManager,
            ) = GameInteractor(
                activity,
                retrogradeDb,
                true,
                shortcutsGenerator,
                gameLauncher,
                onPlaceholderGame = { game, onComplete ->
                    TVRomDownloadDialog(activity, romOnDemandManager).show(game, onComplete)
                },
            )
        }
    }
}
