package com.swordfish.lemuroid.app.shared.input.lemuroiddevice

import android.content.Context
import android.view.InputDevice
import android.view.KeyEvent
import com.swordfish.lemuroid.app.shared.input.InputDeviceManager
import com.swordfish.lemuroid.app.shared.input.InputKey
import com.swordfish.lemuroid.app.shared.input.RetroKey
import com.swordfish.lemuroid.app.shared.input.bindingsOf
import com.swordfish.lemuroid.app.shared.input.inputKeysOf
import com.swordfish.lemuroid.app.shared.input.inputclass.getInputClass
import com.swordfish.lemuroid.app.shared.input.retroKeysOf
import com.swordfish.lemuroid.app.shared.input.supportsAllKeys
import com.swordfish.lemuroid.app.shared.settings.GameShortcutType

class LemuroidInputDeviceGamePad(private val device: InputDevice) : LemuroidInputDevice {
    override fun getDefaultBindings(): Map<InputKey, RetroKey> {
        val allAvailableInputs =
            InputDeviceManager.OUTPUT_KEYS
                .associate {
                    InputKey(it.keyCode) to getDefaultBindingForKey(device, it)
                }

        val defaultOverride =
            bindingsOf(
                KeyEvent.KEYCODE_BUTTON_A to KeyEvent.KEYCODE_BUTTON_B,
                KeyEvent.KEYCODE_BUTTON_B to KeyEvent.KEYCODE_BUTTON_A,
                KeyEvent.KEYCODE_BUTTON_X to KeyEvent.KEYCODE_BUTTON_Y,
                KeyEvent.KEYCODE_BUTTON_Y to KeyEvent.KEYCODE_BUTTON_X,
                // Generic USB arcade sticks often expose DirectInput-style numbered buttons.
                KeyEvent.KEYCODE_BUTTON_1 to KeyEvent.KEYCODE_BUTTON_B,
                KeyEvent.KEYCODE_BUTTON_2 to KeyEvent.KEYCODE_BUTTON_A,
                KeyEvent.KEYCODE_BUTTON_3 to KeyEvent.KEYCODE_BUTTON_Y,
                KeyEvent.KEYCODE_BUTTON_4 to KeyEvent.KEYCODE_BUTTON_X,
                KeyEvent.KEYCODE_BUTTON_5 to KeyEvent.KEYCODE_BUTTON_L2,
                KeyEvent.KEYCODE_BUTTON_6 to KeyEvent.KEYCODE_BUTTON_R2,
                KeyEvent.KEYCODE_BUTTON_7 to KeyEvent.KEYCODE_BUTTON_L1,
                KeyEvent.KEYCODE_BUTTON_8 to KeyEvent.KEYCODE_BUTTON_R1,
                KeyEvent.KEYCODE_BUTTON_9 to KeyEvent.KEYCODE_BUTTON_SELECT,
                KeyEvent.KEYCODE_BUTTON_10 to KeyEvent.KEYCODE_BUTTON_START,
                KeyEvent.KEYCODE_1 to KeyEvent.KEYCODE_BUTTON_B,
                KeyEvent.KEYCODE_2 to KeyEvent.KEYCODE_BUTTON_A,
                KeyEvent.KEYCODE_3 to KeyEvent.KEYCODE_BUTTON_Y,
                KeyEvent.KEYCODE_4 to KeyEvent.KEYCODE_BUTTON_X,
                KeyEvent.KEYCODE_5 to KeyEvent.KEYCODE_BUTTON_L2,
                KeyEvent.KEYCODE_6 to KeyEvent.KEYCODE_BUTTON_R2,
                KeyEvent.KEYCODE_7 to KeyEvent.KEYCODE_BUTTON_L1,
                KeyEvent.KEYCODE_8 to KeyEvent.KEYCODE_BUTTON_R1,
                KeyEvent.KEYCODE_9 to KeyEvent.KEYCODE_BUTTON_SELECT,
                KeyEvent.KEYCODE_0 to KeyEvent.KEYCODE_BUTTON_START,
            )

        return allAvailableInputs + defaultOverride
    }

    private fun getDefaultBindingForKey(
        device: InputDevice,
        it: RetroKey,
    ): RetroKey {
        val defaultBinding =
            if (device.hasKeys(it.keyCode).first()) {
                RetroKey(it.keyCode)
            } else {
                RetroKey(KeyEvent.KEYCODE_UNKNOWN)
            }
        return defaultBinding
    }

    override fun isEnabledByDefault(appContext: Context): Boolean {
        return device.supportsAllKeys(MINIMAL_KEYS_DEFAULT_ENABLED) ||
            GENERIC_NUMBERED_KEY_GROUPS.any { device.supportsAllKeys(it) }
    }

    override fun getSupportedShortcuts(): List<GameShortcutType> = GameShortcutType.values().toList()

    override fun isSupported(): Boolean {
        val isGamepadSource =
            (device.sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
                (device.sources and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
        val hasSupportedKeyGroup = MINIMAL_SUPPORTED_KEY_GROUPS.any { device.supportsAllKeys(it) }

        return sequenceOf(
            isGamepadSource || hasSupportedKeyGroup,
            hasSupportedKeyGroup,
            device.isVirtual.not(),
        ).all { it }
    }

    override fun getCustomizableKeys(): List<RetroKey> {
        val deviceAxis =
            device.motionRanges
                .map { it.axis }
                .toSet()

        val keysMappedToAxis =
            device.getInputClass().getAxesMap()
                .filter { it.key in deviceAxis }
                .map { it.value }
                .toSet()

        return CUSTOMIZABLE_KEYS
            .filter { it.keyCode !in keysMappedToAxis }
    }

    companion object {
        private val MINIMAL_SUPPORTED_KEYS =
            inputKeysOf(
                KeyEvent.KEYCODE_BUTTON_A,
                KeyEvent.KEYCODE_BUTTON_B,
                KeyEvent.KEYCODE_BUTTON_X,
                KeyEvent.KEYCODE_BUTTON_Y,
            )

        private val GENERIC_NUMBERED_FACE_KEYS =
            inputKeysOf(
                KeyEvent.KEYCODE_BUTTON_1,
                KeyEvent.KEYCODE_BUTTON_2,
                KeyEvent.KEYCODE_BUTTON_3,
                KeyEvent.KEYCODE_BUTTON_4,
            )

        private val GENERIC_KEYBOARD_NUMBER_FACE_KEYS =
            inputKeysOf(
                KeyEvent.KEYCODE_1,
                KeyEvent.KEYCODE_2,
                KeyEvent.KEYCODE_3,
                KeyEvent.KEYCODE_4,
            )

        private val GENERIC_NUMBERED_KEY_GROUPS =
            listOf(
                GENERIC_NUMBERED_FACE_KEYS,
                GENERIC_KEYBOARD_NUMBER_FACE_KEYS,
            )

        private val MINIMAL_SUPPORTED_KEY_GROUPS =
            listOf(
                MINIMAL_SUPPORTED_KEYS,
                GENERIC_NUMBERED_FACE_KEYS,
                GENERIC_KEYBOARD_NUMBER_FACE_KEYS,
            )

        private val MINIMAL_KEYS_DEFAULT_ENABLED =
            MINIMAL_SUPPORTED_KEYS +
                inputKeysOf(
                    KeyEvent.KEYCODE_BUTTON_START
                )

        private val CUSTOMIZABLE_KEYS: List<RetroKey> =
            retroKeysOf(
                KeyEvent.KEYCODE_BUTTON_A,
                KeyEvent.KEYCODE_BUTTON_B,
                KeyEvent.KEYCODE_BUTTON_X,
                KeyEvent.KEYCODE_BUTTON_Y,
                KeyEvent.KEYCODE_BUTTON_START,
                KeyEvent.KEYCODE_BUTTON_SELECT,
                KeyEvent.KEYCODE_BUTTON_L1,
                KeyEvent.KEYCODE_BUTTON_L2,
                KeyEvent.KEYCODE_BUTTON_R1,
                KeyEvent.KEYCODE_BUTTON_R2,
                KeyEvent.KEYCODE_BUTTON_THUMBL,
                KeyEvent.KEYCODE_BUTTON_THUMBR,
                KeyEvent.KEYCODE_BUTTON_MODE,
            )
    }
}
