package com.swordfish.lemuroid.app.tv.gamemenu

import android.os.Bundle
import androidx.fragment.app.Fragment
import com.swordfish.lemuroid.app.shared.GameMenuContract
import com.swordfish.lemuroid.app.shared.coreoptions.LemuroidCoreOption
import com.swordfish.lemuroid.app.tv.shared.TVBaseSettingsActivity
import com.swordfish.lemuroid.lib.injection.PerFragment
import com.swordfish.lemuroid.lib.library.SystemCoreConfig
import com.swordfish.lemuroid.lib.library.db.entity.Game
import dagger.android.ContributesAndroidInjector
import java.io.Serializable
import java.security.InvalidParameterException

class TVGameMenuActivity : TVBaseSettingsActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            // Extra obrigatorio ausente = nao foi o BaseGameActivity que abriu o menu (o Robo test
            // do Pre-Launch Report lanca toda activity declarada sem extras). Fechar, nao lancar.
            val request = parseGameMenuRequest() ?: run { finish(); return }

            val fragment = TVGameMenuFragmentWrapper.newInstance(request)
            supportFragmentManager.beginTransaction().replace(android.R.id.content, fragment)
                .commit()
        }
    }

    private fun parseGameMenuRequest(): GameMenuRequest? {
        val extras = intent.extras ?: return null

        return GameMenuRequest(
            game =
                extras.getSerializable(GameMenuContract.EXTRA_GAME) as? Game
                    ?: return null,
            systemCoreConfig =
                extras.getSerializable(GameMenuContract.EXTRA_SYSTEM_CORE_CONFIG) as? SystemCoreConfig
                    ?: return null,
            coreOptions =
                extras.getSerializable(GameMenuContract.EXTRA_CORE_OPTIONS) as? Array<LemuroidCoreOption>
                    ?: return null,
            advancedCoreOptions =
                extras.getSerializable(GameMenuContract.EXTRA_ADVANCED_CORE_OPTIONS) as? Array<LemuroidCoreOption>
                    ?: return null,
            numDisks = extras.getInt(GameMenuContract.EXTRA_DISKS),
            currentDisk = extras.getInt(GameMenuContract.EXTRA_CURRENT_DISK),
            audioEnabled = extras.getBoolean(GameMenuContract.EXTRA_AUDIO_ENABLED),
            fastForwardEnabled = extras.getBoolean(GameMenuContract.EXTRA_FAST_FORWARD),
            fastForwardSupported = extras.getBoolean(GameMenuContract.EXTRA_FAST_FORWARD_SUPPORTED),
        )
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    class TVGameMenuFragmentWrapper : BaseSettingsFragmentWrapper() {
        override fun createFragment(): Fragment {
            return TVGameMenuFragment.newInstance(GameMenuRequest.from(requireArguments()))
        }

        companion object {
            fun newInstance(request: GameMenuRequest) =
                TVGameMenuFragmentWrapper().apply {
                    arguments = request.toBundle()
                }
        }
    }

    @Suppress("ArrayInDataClass")
    data class GameMenuRequest(
        val game: Game,
        val systemCoreConfig: SystemCoreConfig,
        val coreOptions: Array<LemuroidCoreOption>,
        val advancedCoreOptions: Array<LemuroidCoreOption>,
        val numDisks: Int,
        val currentDisk: Int,
        val audioEnabled: Boolean,
        val fastForwardEnabled: Boolean,
        val fastForwardSupported: Boolean,
    ) : Serializable {
        fun toBundle() =
            Bundle(1).apply {
                putSerializable(ARG_REQUEST, this@GameMenuRequest)
            }

        companion object {
            private const val ARG_REQUEST = "game_menu_request"

            @Suppress("DEPRECATION")
            fun from(arguments: Bundle): GameMenuRequest =
                arguments.getSerializable(ARG_REQUEST) as? GameMenuRequest
                    ?: throw InvalidParameterException("Missing game menu request")
        }
    }

    @dagger.Module
    abstract class Module {
        @PerFragment
        @ContributesAndroidInjector(modules = [TVGameMenuFragment.Module::class])
        abstract fun tvGameMenuFragment(): TVGameMenuFragment
    }
}
