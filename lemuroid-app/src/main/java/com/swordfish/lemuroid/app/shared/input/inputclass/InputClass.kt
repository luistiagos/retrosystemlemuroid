package com.swordfish.lemuroid.app.shared.input.inputclass

import android.view.InputDevice
import android.view.KeyEvent
import com.swordfish.lemuroid.app.shared.input.InputKey

interface InputClass {
    fun getInputKeys(): Set<InputKey>

    fun getAxesMap(): Map<Int, Int>
}

fun InputDevice?.getInputClass(): InputClass {
    return when {
        this == null -> InputClassUnknown
        isGamepadInputClass() -> InputClassGamePad
        (sources and InputDevice.SOURCE_KEYBOARD) == InputDevice.SOURCE_KEYBOARD -> InputClassKeyboard
        else -> InputClassUnknown
    }
}

private fun InputDevice.isGamepadInputClass(): Boolean {
    val isGamepadSource =
        (sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
            (sources and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK

    // Sticks arcade DirectInput expoem os botoes pela faixa evdev
    // BTN_TRIGGER..BTN_BASE6, que o Generic.kl traduz para BUTTON_1..BUTTON_16
    // em vez de BUTTON_A/B/X/Y. Nao usamos KEYCODE_1..9 como evidencia: essa
    // faixa vem de KEY_1..KEY_9 (linha numerica de teclado), que um HID de
    // gamepad nunca emite, e aceita-la transformaria remotes de TV em gamepad.
    val hasGenericNumberedButtons =
        hasKeys(
            KeyEvent.KEYCODE_BUTTON_1,
            KeyEvent.KEYCODE_BUTTON_2,
            KeyEvent.KEYCODE_BUTTON_3,
            KeyEvent.KEYCODE_BUTTON_4,
        ).all { it }

    return isGamepadSource || hasGenericNumberedButtons
}
