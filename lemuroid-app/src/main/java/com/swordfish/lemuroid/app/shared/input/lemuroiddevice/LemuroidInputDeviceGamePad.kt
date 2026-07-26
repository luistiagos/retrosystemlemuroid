package com.swordfish.lemuroid.app.shared.input.lemuroiddevice

import android.content.Context
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
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
        // Binding identidade para todas as teclas de saída. Não consultamos
        // hasKeys(): em TV boxes baratas ele retorna false para teclas que o
        // controle envia, o que mapeava botões reais para KEYCODE_UNKNOWN.
        val allAvailableInputs =
            InputDeviceManager.OUTPUT_KEYS
                .associate { InputKey(it.keyCode) to RetroKey(it.keyCode) }

        val defaultOverride =
            bindingsOf(
                KeyEvent.KEYCODE_BUTTON_A to KeyEvent.KEYCODE_BUTTON_B,
                KeyEvent.KEYCODE_BUTTON_B to KeyEvent.KEYCODE_BUTTON_A,
                KeyEvent.KEYCODE_BUTTON_X to KeyEvent.KEYCODE_BUTTON_Y,
                KeyEvent.KEYCODE_BUTTON_Y to KeyEvent.KEYCODE_BUTTON_X,
                // Sticks arcade USB DirectInput expoem os botoes pela faixa evdev
                // BTN_TRIGGER..BTN_BASE6, traduzida pelo Generic.kl para
                // BUTTON_1..BUTTON_16. A ordem segue a tabela do manual do NJP308.
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
            )

        return allAvailableInputs + defaultOverride
    }

    override fun isEnabledByDefault(appContext: Context): Boolean {
        // hasKeys mente em muitas TV boxes. Se o dispositivo expõe eixos reais de
        // joystick (analógico ou HAT do D-pad), é um controle de verdade — habilita.
        // Controles remotos de TV não expõem eixos SOURCE_JOYSTICK, então continuam
        // desabilitados por padrão a menos que passem no teste de teclas.
        return device.supportsAllKeys(MINIMAL_KEYS_DEFAULT_ENABLED) ||
            device.supportsAllKeys(GENERIC_NUMBERED_FACE_KEYS) ||
            hasJoystickAxes()
    }

    override fun getSupportedShortcuts(): List<GameShortcutType> = GameShortcutType.values().toList()

    override fun isSupported(): Boolean {
        val isGamepadSource =
            (device.sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
                (device.sources and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
        return sequenceOf(
            isGamepadSource,
            hasGamepadEvidence(),
            device.isVirtual.not(),
        ).all { it }
    }

    // hasKeys() depende dos arquivos .kl da ROM; em TV boxes baratas ele retorna
    // false para botões que o controle realmente envia. Aceitamos qualquer
    // evidência de gamepad em vez de exigir hasKeys(A,B,X,Y) completo.
    private fun hasGamepadEvidence(): Boolean {
        if (device.supportsAllKeys(MINIMAL_SUPPORTED_KEYS)) return true

        val anyButton =
            device.hasKeys(
                KeyEvent.KEYCODE_BUTTON_A,
                KeyEvent.KEYCODE_BUTTON_B,
                KeyEvent.KEYCODE_BUTTON_X,
                KeyEvent.KEYCODE_BUTTON_Y,
                KeyEvent.KEYCODE_BUTTON_START,
                KeyEvent.KEYCODE_BUTTON_SELECT,
                KeyEvent.KEYCODE_BUTTON_L1,
                KeyEvent.KEYCODE_BUTTON_R1,
                // Sticks arcade DirectInput so expoem botoes numerados.
                KeyEvent.KEYCODE_BUTTON_1,
                KeyEvent.KEYCODE_BUTTON_2,
                KeyEvent.KEYCODE_BUTTON_3,
                KeyEvent.KEYCODE_BUTTON_4,
            ).any { it }
        if (anyButton) return true

        return hasJoystickAxes()
    }

    // Eixos consultados especificamente na classe SOURCE_JOYSTICK para não
    // confundir com eixos de mouse/touch de air-mouses e controles remotos.
    private fun hasJoystickAxes(): Boolean {
        return sequenceOf(
            MotionEvent.AXIS_X,
            MotionEvent.AXIS_HAT_X,
        ).any { axis -> device.getMotionRange(axis, InputDevice.SOURCE_JOYSTICK) != null }
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

        // Botoes de face de stick arcade DirectInput, como o Generic.kl do
        // Android os expoe. Ver a nota em getDefaultBindings.
        private val GENERIC_NUMBERED_FACE_KEYS =
            inputKeysOf(
                KeyEvent.KEYCODE_BUTTON_1,
                KeyEvent.KEYCODE_BUTTON_2,
                KeyEvent.KEYCODE_BUTTON_3,
                KeyEvent.KEYCODE_BUTTON_4,
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
