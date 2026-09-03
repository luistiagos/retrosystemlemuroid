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
            val game =
                intent.extras?.getSerializable(GameMenuContract.EXTRA_GAME) as? Game
                    ?: throw InvalidParameterException("Missing EXTRA_GAME")

            val core =
                intent.extras?.getSerializable(
                    GameMenuContract.EXTRA_SYSTEM_CORE_CONFIG,
                ) as? SystemCoreConfig
                    ?: throw InvalidParameterException("Missing EXTRA_SYSTEM_CORE_CONFIG")

            val options =
                intent.extras?.getSerializable(
                    GameMenuContract.EXTRA_CORE_OPTIONS,
                ) as? Array<LemuroidCoreOption>
                    ?: throw InvalidParameterException("Missing EXTRA_CORE_OPTIONS")

            val advancedOptions =
                intent.extras?.getSerializable(
                    GameMenuContract.EXTRA_ADVANCED_CORE_OPTIONS,
                ) as? Array<LemuroidCoreOption>
                    ?: throw InvalidParameterException("Missing EXTRA_ADVANCED_CORE_OPTIONS")

            val numDisks =
                intent.extras?.getInt(GameMenuContract.EXTRA_DISKS)
                    ?: throw InvalidParameterException("Missing EXTRA_DISKS")

            val currentDisk =
                intent.extras?.getInt(GameMenuContract.EXTRA_CURRENT_DISK)
                    ?: throw InvalidParameterException("Missing EXTRA_CURRENT_DISK")

            val audioEnabled =
                intent.extras?.getBoolean(GameMenuContract.EXTRA_AUDIO_ENABLED)
                    ?: throw InvalidParameterException("Missing EXTRA_AUDIO_ENABLED")

            val fastForwardEnabled =
                intent.extras?.getBoolean(GameMenuContract.EXTRA_FAST_FORWARD)
                    ?: throw InvalidParameterException("Missing EXTRA_FAST_FORWARD")

            val fastForwardSupported =
                intent.extras?.getBoolean(GameMenuContract.EXTRA_FAST_FORWARD_SUPPORTED)
                    ?: throw InvalidParameterException("Missing EXTRA_FAST_FORWARD_SUPPORTED")

            val fragment =
                TVGameMenuFragmentWrapper.newInstance(
                    GameMenuRequest(
                        game,
                        core,
                        options,
                        advancedOptions,
                        numDisks,
                        currentDisk,
                        audioEnabled,
                        fastForwardEnabled,
                        fastForwardSupported,
                    ),
                )
            supportFragmentManager.beginTransaction().replace(android.R.id.content, fragment)
                .commit()
        }
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
