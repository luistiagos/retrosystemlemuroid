package com.swordfish.lemuroid.app.shared.input.lemuroiddevice

import android.content.Context
import android.view.InputDevice
import android.view.KeyEvent
import com.swordfish.lemuroid.app.shared.input.InputKey
import com.swordfish.lemuroid.app.shared.input.RetroKey
import com.swordfish.lemuroid.app.shared.settings.GameShortcutType

interface LemuroidInputDevice {
    fun getCustomizableKeys(): List<RetroKey>

    fun getDefaultBindings(): Map<InputKey, RetroKey>

    fun isSupported(): Boolean

    fun isEnabledByDefault(appContext: Context): Boolean

    fun getSupportedShortcuts(): List<GameShortcutType>
}

private fun InputDevice.isGamepad(): Boolean {
    val isGamepadSource =
        (sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
        (sources and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK

    // Ver a nota em InputClass.isGamepadInputClass: BUTTON_1..4 sao evidencia
    // legitima de stick arcade DirectInput; KEYCODE_1..4 nao sao.
    val hasGenericNumberedButtons =
        hasKeys(
            KeyEvent.KEYCODE_BUTTON_1,
            KeyEvent.KEYCODE_BUTTON_2,
            KeyEvent.KEYCODE_BUTTON_3,
            KeyEvent.KEYCODE_BUTTON_4,
        ).all { it }

    return isGamepadSource || hasGenericNumberedButtons
}

fun InputDevice?.getLemuroidInputDevice(): LemuroidInputDevice {
    return when {
        this == null -> LemuroidInputDeviceUnknown
        isGamepad() -> LemuroidInputDeviceGamePad(this)
        (sources and InputDevice.SOURCE_KEYBOARD) == InputDevice.SOURCE_KEYBOARD -> LemuroidInputDeviceKeyboard(this)
        else -> LemuroidInputDeviceUnknown
    }
}
