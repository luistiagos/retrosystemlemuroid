package com.swordfish.lemuroid.app.tv.game

import androidx.compose.runtime.Composable
import androidx.lifecycle.Lifecycle
import com.swordfish.lemuroid.R
import com.swordfish.lemuroid.app.shared.game.BaseGameActivity
import com.swordfish.lemuroid.app.shared.game.BaseGameScreenViewModel
import com.swordfish.lemuroid.app.tv.gamemenu.TVGameMenuActivity
import com.swordfish.lemuroid.common.coroutines.launchOnState
import com.swordfish.lemuroid.common.coroutines.safeCollect
import com.swordfish.lemuroid.common.displayToast
import com.swordfish.libretrodroid.GLRetroView
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map

class TVGameActivity : BaseGameActivity() {
    override fun getDialogClass() = TVGameMenuActivity::class.java

    override fun onGameCreated() {
        initializeFlows()
    }

    @Composable
    override fun GameScreen(viewModel: BaseGameScreenViewModel) {
        TVGameScreen(viewModel)
    }

    private fun initializeFlows() {
        launchOnState(Lifecycle.State.CREATED) {
            initializeShortcutToastFlow()
        }
    }

    private suspend fun initializeShortcutToastFlow() {
        // Espera o primeiro frame renderizado antes de disparar toast.
        // Em TV boxes sem gamepad (apenas controle remoto IR), inputDeviceManager emite lista vazia
        // imediatamente no boot; se disparado antes do primeiro frame, o toast compete com o carregamento
        // pesado do core na main thread, estourando o token de janela (API 25) e matando o processo (ver SafeToast).
        baseGameScreenViewModel.retroGameView.waitGLEvent<GLRetroView.GLRetroEvents.FrameRendered>()
        inputDeviceManager
            .getEnabledInputsObservable()
            .map { it.isEmpty() }
            .distinctUntilChanged()
            .filter { it }
            .safeCollect {
                displayToast(R.string.tv_game_message_missing_gamepad)
            }
    }
}
