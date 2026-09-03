package com.swordfish.lemuroid.app.tv.gamemenu

import android.content.Context
import android.os.Bundle
import android.view.View
import androidx.leanback.preference.LeanbackPreferenceFragmentCompat
import androidx.lifecycle.Lifecycle
import androidx.preference.Preference
import androidx.preference.PreferenceScreen
import com.swordfish.lemuroid.R
import com.swordfish.lemuroid.app.shared.coreoptions.CoreOptionsPreferenceHelper
import com.swordfish.lemuroid.app.shared.gamemenu.GameMenuHelper
import com.swordfish.lemuroid.app.shared.input.InputDeviceManager
import com.swordfish.lemuroid.app.tv.gamemenu.TVGameMenuActivity.GameMenuRequest
import com.swordfish.lemuroid.common.coroutines.launchOnState
import com.swordfish.lemuroid.common.coroutines.safeCollect
import com.swordfish.lemuroid.lib.preferences.SharedPreferencesHelper
import com.swordfish.lemuroid.lib.saves.StatesManager
import com.swordfish.lemuroid.lib.saves.StatesPreviewManager
import dagger.android.support.AndroidSupportInjection
import javax.inject.Inject

class TVGameMenuFragment : LeanbackPreferenceFragmentCompat() {
    @Inject
    lateinit var statesManager: StatesManager

    @Inject
    lateinit var statesPreviewManager: StatesPreviewManager

    @Inject
    lateinit var inputDeviceManager: InputDeviceManager

    private lateinit var request: GameMenuRequest

    override fun onAttach(context: Context) {
        AndroidSupportInjection.inject(this)
        super.onAttach(context)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        request = GameMenuRequest.from(requireArguments())
        super.onCreate(savedInstanceState)
    }

    override fun onCreatePreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        preferenceManager.preferenceDataStore =
            SharedPreferencesHelper.getSharedPreferencesDataStore(requireContext())
        setPreferencesFromResource(R.xml.tv_game_settings, rootKey)
    }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)

        GameMenuHelper.setupAudioOption(preferenceScreen, request.audioEnabled)
        GameMenuHelper.setupFastForwardOption(
            preferenceScreen,
            request.fastForwardEnabled,
            request.fastForwardSupported,
        )
        GameMenuHelper.setupSaveOption(preferenceScreen, request.systemCoreConfig)

        if (request.numDisks > 1) {
            GameMenuHelper.setupChangeDiskOption(
                activity,
                preferenceScreen,
                request.currentDisk,
                request.numDisks,
            )
        }

        launchOnState(Lifecycle.State.CREATED) {
            initializeLoadAndSave()
        }

        launchOnState(Lifecycle.State.CREATED) {
            initializeControllers()
        }
    }

    private suspend fun initializeControllers() {
        inputDeviceManager.getGamePadsObservable()
            .safeCollect { setupCoreOptions(it.size) }
    }

    private fun setupCoreOptions(connectedGamePads: Int) {
        val coreOptionsScreen =
            findPreference<PreferenceScreen>(GameMenuHelper.SECTION_CORE_OPTIONS)
                ?: return

        coreOptionsScreen.removeAll()

        CoreOptionsPreferenceHelper.addPreferences(
            coreOptionsScreen,
            request.game.systemId,
            request.coreOptions.toList(),
            request.advancedCoreOptions.toList(),
        )

        CoreOptionsPreferenceHelper.addControllers(
            coreOptionsScreen,
            request.game.systemId,
            request.systemCoreConfig.coreID,
            connectedGamePads,
            request.systemCoreConfig.controllerConfigs,
        )
    }

    private suspend fun initializeLoadAndSave() {
        val saveScreen = findPreference<PreferenceScreen>(GameMenuHelper.SECTION_SAVE_GAME)
        val loadScreen = findPreference<PreferenceScreen>(GameMenuHelper.SECTION_LOAD_GAME)

        saveScreen?.isEnabled = request.systemCoreConfig.statesSupported
        loadScreen?.isEnabled = request.systemCoreConfig.statesSupported

        val slotsInfo = statesManager.getSavedSlotsInfo(request.game, request.systemCoreConfig.coreID)

        slotsInfo.forEachIndexed { index, saveInfo ->
            val bitmap =
                GameMenuHelper.getSaveStateBitmap(
                    requireContext(),
                    statesPreviewManager,
                    saveInfo,
                    request.game,
                    request.systemCoreConfig.coreID,
                    index,
                )

            if (saveScreen != null) {
                GameMenuHelper.addSavePreference(saveScreen, index, saveInfo, bitmap)
            }

            if (loadScreen != null) {
                GameMenuHelper.addLoadPreference(loadScreen, index, saveInfo, bitmap)
            }
        }
    }

    override fun onPreferenceTreeClick(preference: Preference): Boolean {
        if (GameMenuHelper.onPreferenceTreeClicked(activity, preference)) {
            return true
        }
        return super.onPreferenceTreeClick(preference)
    }

    @dagger.Module
    class Module

    companion object {
        fun newInstance(request: GameMenuRequest) =
            TVGameMenuFragment().apply {
                arguments = request.toBundle()
            }
    }
}
