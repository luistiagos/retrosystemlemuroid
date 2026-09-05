package com.swordfish.lemuroid.app.shared.input.inputclass

import android.view.InputDevice
import com.swordfish.lemuroid.app.shared.input.InputKey

interface InputClass {
    fun getInputKeys(): Set<InputKey>

    fun getAxesMap(): Map<Int, Int>
}

// Um gatilho analogico so conta como pressionado acima deste valor. Compartilhado
// pelo caminho de jogo (GameViewModelInput.initializeVirtualGamePadMotionsFlow) e
// pela captura de binding (InputBindingUpdater.handleMotionEvent), para que a tela
// de Controles e o jogo concordem sobre o ponto em que L2/R2 disparam.
const val AXIS_PRESS_THRESHOLD = 0.5f

// Classificacao apenas por `sources`, que e um campo local barato.
//
// Nao consultar hasKeys() aqui: getInputClass() roda no caminho de motion event
// (GameViewModelInput.initializeVirtualGamePadMotionsFlow) e hasKeys e uma
// chamada IPC para o InputManagerService - seria um binder por evento.
//
// A evidencia por BUTTON_1..4 de stick arcade vive em
// LemuroidInputDeviceGamePad.hasGamepadEvidence(), onde e consultada apenas na
// enumeracao de dispositivos. Aqui ela seria inerte de qualquer forma: um device
// sem source de gamepad nunca passa em LemuroidInputDeviceGamePad.isSupported(),
// e classifica-lo como gamepad so o impediria de ser tratado como teclado.
fun InputDevice?.getInputClass(): InputClass {
    return when {
        this == null -> InputClassUnknown
        (sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD -> InputClassGamePad
        (sources and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK -> InputClassGamePad
        (sources and InputDevice.SOURCE_KEYBOARD) == InputDevice.SOURCE_KEYBOARD -> InputClassKeyboard
        else -> InputClassUnknown
    }
}
