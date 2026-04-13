package com.swordfish.lemuroid.app.shared.game.viewmodel

import com.swordfish.touchinput.radial.sensors.TiltConfiguration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch

class GameViewModelSideEffects(private val scope: CoroutineScope) {
    sealed interface UiEffect {
        data class ShowMenu(
            val currentTiltConfiguration: TiltConfiguration,
            val tiltConfigurations: List<TiltConfiguration>,
        ) : UiEffect

        data class ShowToast(val message: String) : UiEffect

        data object SuccessfulFinish : UiEffect

        data class FailureFinish(val message: String, val isRomLoadFailure: Boolean = false) : UiEffect

        data object LoadQuickSave : UiEffect

        data object SaveQuickSave : UiEffect

        data object ToggleFastForward : UiEffect
    }

    private val uiEffects = MutableSharedFlow<UiEffect>()

    fun getUiEffects(): Flow<UiEffect> {
        return uiEffects
    }

    fun showToast(message: String) {
        scope.launch {
            uiEffects.emit(UiEffect.ShowToast(message))
        }
    }

    fun showMenu(
        tilt: GameViewModelTilt,
        inputs: GameViewModelInput,
    ) {
        scope.launch {
            val currentTiltConfiguration = tilt.getTiltConfiguration().firstOrNull() ?: return@launch
            val tiltConfigurations = inputs.getAllTiltConfigurations()
            uiEffects.emit(UiEffect.ShowMenu(currentTiltConfiguration, tiltConfigurations))
        }
    }

    fun loadQuickSave() {
        scope.launch {
            uiEffects.emit(UiEffect.LoadQuickSave)
        }
    }

    fun requestSuccessfulFinish() {
        scope.launch {
            uiEffects.emit(UiEffect.SuccessfulFinish)
        }
    }

    fun requestFailureFinish(message: String, isRomLoadFailure: Boolean = false) {
        scope.launch {
            uiEffects.emit(UiEffect.FailureFinish(message, isRomLoadFailure))
        }
    }

    fun saveQuickSave() {
        scope.launch {
            uiEffects.emit(UiEffect.SaveQuickSave)
        }
    }

    fun toggleFastForward() {
        scope.launch {
            uiEffects.emit(UiEffect.ToggleFastForward)
        }
    }
}
