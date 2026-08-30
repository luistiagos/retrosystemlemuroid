package com.swordfish.lemuroid.app.tv.shared

import android.view.InputDevice
import android.view.KeyEvent
import com.swordfish.lemuroid.app.shared.ImmersiveActivity

abstract class BaseTVActivity : ImmersiveActivity() {
    protected open val enableGamepadNavigationTranslation: Boolean = true

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (enableGamepadNavigationTranslation) {
            val translatedEvent = translateGamepadNavEvent(event)
            if (translatedEvent != null) {
                return super.dispatchKeyEvent(translatedEvent)
            }
        }
        return super.dispatchKeyEvent(event)
    }

    companion object {
        fun translateGamepadNavEvent(event: KeyEvent): KeyEvent? {
            val isGamepadOrJoystick =
                (event.source and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
                    (event.source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK ||
                    event.device?.let {
                        (it.sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
                            (it.sources and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
                    } ?: false

            if (!isGamepadOrJoystick) return null

            val mappedKeyCode = when (event.keyCode) {
                KeyEvent.KEYCODE_BUTTON_A,
                KeyEvent.KEYCODE_BUTTON_1,
                KeyEvent.KEYCODE_BUTTON_2 -> KeyEvent.KEYCODE_DPAD_CENTER
                KeyEvent.KEYCODE_BUTTON_B,
                KeyEvent.KEYCODE_BUTTON_3 -> KeyEvent.KEYCODE_BACK
                else -> null
            } ?: return null

            return KeyEvent(
                event.downTime,
                event.eventTime,
                event.action,
                mappedKeyCode,
                event.repeatCount,
                event.metaState,
                event.deviceId,
                event.scanCode,
                event.flags,
                event.source,
            )
        }
    }
}
