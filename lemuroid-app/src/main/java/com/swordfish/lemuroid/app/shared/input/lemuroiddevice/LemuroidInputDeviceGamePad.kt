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
import com.swordfish.lemuroid.app.shared.input.retroKeysOf
import com.swordfish.lemuroid.app.shared.settings.GameShortcutType
import java.util.concurrent.ConcurrentHashMap

class LemuroidInputDeviceGamePad(private val device: InputDevice) : LemuroidInputDevice {
    override fun getDefaultBindings(): Map<InputKey, RetroKey> {
        // A ORDEM destes tres blocos importa. A tela de Configuracoes > Controles
        // inverte este mapa com reverseLookup() (associateBy: o ultimo vence).
        // Varias InputKeys apontam para a mesma RetroKey - BUTTON_A e BUTTON_1
        // ambos viram BUTTON_B - entao quem aparece por ultimo e o nome exibido.
        // Os overrides de stick arcade vem PRIMEIRO para que o nome canonico
        // (BUTTON_A, BUTTON_START, ...) seja o mostrado em controles normais.
        // O mapa resultante e o mesmo nos dois casos; so a ordem de iteracao muda.

        // Sticks arcade USB DirectInput expoem os botoes pela faixa evdev
        // BTN_TRIGGER..BTN_BASE6, traduzida pelo Generic.kl para
        // BUTTON_1..BUTTON_16. A ordem segue a tabela do manual do NJP308.
        val genericArcadeOverride =
            bindingsOf(
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

        // Fallbacks para controles/TV boxes que reportam confirmacao como DPAD_CENTER ou ENTER.
        // Ficam antes de allAvailableInputs para que o nome canonico (BUTTON_START, etc.)
        // prevaleça no reverseLookup() das configuracoes.
        val genericExtraOverride =
            bindingsOf(
                KeyEvent.KEYCODE_DPAD_CENTER to KeyEvent.KEYCODE_BUTTON_A,
                KeyEvent.KEYCODE_ENTER to KeyEvent.KEYCODE_BUTTON_START,
            )

        // Binding identidade para todas as teclas de saída. Não consultamos
        // hasKeys(): em TV boxes baratas ele retorna false para teclas que o
        // controle envia, o que mapeava botões reais para KEYCODE_UNKNOWN.
        val allAvailableInputs =
            InputDeviceManager.OUTPUT_KEYS
                .associate { InputKey(it.keyCode) to RetroKey(it.keyCode) }

        val faceButtonSwap =
            bindingsOf(
                KeyEvent.KEYCODE_BUTTON_A to KeyEvent.KEYCODE_BUTTON_B,
                KeyEvent.KEYCODE_BUTTON_B to KeyEvent.KEYCODE_BUTTON_A,
                KeyEvent.KEYCODE_BUTTON_X to KeyEvent.KEYCODE_BUTTON_Y,
                KeyEvent.KEYCODE_BUTTON_Y to KeyEvent.KEYCODE_BUTTON_X,
            )

        return genericArcadeOverride + genericExtraOverride + allAvailableInputs + faceButtonSwap
    }

    override fun isEnabledByDefault(appContext: Context): Boolean {
        // hasKeys mente em muitas TV boxes. Se o dispositivo expõe eixos reais de
        // joystick (analógico ou HAT do D-pad), ou tem nome explícito de gamepad,
        // é um controle de verdade — habilita.
        // Controles remotos de TV não expõem eixos de joystick nem nomes de gamepad.
        return supportsAllCached(MINIMAL_KEYS_DEFAULT_ENABLED) ||
            supportsAllCached(GENERIC_NUMBERED_FACE_KEYS) ||
            isKnownGamepadName(device.name) ||
            hasJoystickAxes()
    }

    override fun getSupportedShortcuts(): List<GameShortcutType> = GameShortcutType.values().toList()

    override fun isSupported(): Boolean {
        val isGamepadSource =
            (device.sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
                (device.sources and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
        return isGamepadSource && device.isVirtual.not() && hasGamepadEvidence()
    }

    // hasKeys() depende dos arquivos .kl da ROM; em TV boxes baratas ele retorna
    // false para botões que o controle realmente envia. Aceitamos qualquer
    // evidência de gamepad em vez de exigir hasKeys(A,B,X,Y) completo.
    private fun hasGamepadEvidence(): Boolean {
        val supportedKeys = getCachedSupportedKeys()
        if (MINIMAL_SUPPORTED_KEYS.all { it.keyCode in supportedKeys }) return true
        if (supportedKeys.isNotEmpty()) return true

        if (isKnownGamepadName(device.name)) return true

        return hasJoystickAxes()
    }

    private fun supportsAllCached(keys: List<InputKey>): Boolean {
        val supportedKeys = getCachedSupportedKeys()
        return keys.all { it.keyCode in supportedKeys }
    }

    private fun getCachedSupportedKeys(): Set<Int> {
        keySupportCache[device.id]
            ?.takeIf { it.descriptor == device.descriptor }
            ?.let { return it.supportedKeys }

        // A single hasKeys() covers every heuristic used by this wrapper. Do not
        // hold a lock during Binder IPC; duplicate concurrent first lookups are
        // harmless and subsequent enumerations use the cached value.
        val hasKeys = device.hasKeys(*GAMEPAD_EVIDENCE_KEY_CODES)
        val supportedKeys =
            GAMEPAD_EVIDENCE_KEY_CODES
                .filterIndexed { index, _ -> hasKeys.getOrElse(index) { false } }
                .toSet()
        keySupportCache[device.id] = CachedKeySupport(device.descriptor, supportedKeys)
        return supportedKeys
    }

    // Eixos consultados para detectar controle real. Checa SOURCE_JOYSTICK,
    // SOURCE_GAMEPAD e motionRanges genericos excluindo apenas mouse/touchpad.
    private fun hasJoystickAxes(): Boolean {
        val candidateAxes = sequenceOf(
            MotionEvent.AXIS_X,
            MotionEvent.AXIS_Y,
            MotionEvent.AXIS_HAT_X,
            MotionEvent.AXIS_HAT_Y,
            MotionEvent.AXIS_Z,
            MotionEvent.AXIS_RZ,
        )
        return candidateAxes.any { axis ->
            device.getMotionRange(axis, InputDevice.SOURCE_JOYSTICK) != null ||
                device.getMotionRange(axis, InputDevice.SOURCE_GAMEPAD) != null ||
                (device.getMotionRange(axis)?.let { (it.source and InputDevice.SOURCE_CLASS_POINTER) == 0 } ?: false)
        }
    }

    // L2/R2 ficavam de fora da lista sempre que o controle expunha os gatilhos como
    // eixos (AXIS_LTRIGGER/AXIS_BRAKE e AXIS_RTRIGGER/AXIS_THROTTLE) - o caso da
    // maioria dos controles modernos. O motivo era honesto: o caminho de motion
    // event mandava o keycode fixo direto para o core, entao o binding nao teria
    // efeito nenhum. Hoje esse caminho passa pelo mesmo mapa de bindings
    // (GameViewModelInput.initializeVirtualGamePadMotionsFlow) e a captura aceita o
    // proprio gatilho como tecla fisica (InputBindingUpdater.handleMotionEvent),
    // entao nao ha mais motivo para esconder as duas linhas.
    override fun getCustomizableKeys(): List<RetroKey> = CUSTOMIZABLE_KEYS

    companion object {
        private data class CachedKeySupport(
            val descriptor: String,
            val supportedKeys: Set<Int>,
        )

        private val keySupportCache = ConcurrentHashMap<Int, CachedKeySupport>()

        private val GAMEPAD_EVIDENCE_KEY_CODES =
            intArrayOf(
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
            )

        internal fun invalidateKeySupportCache(deviceId: Int) {
            keySupportCache.remove(deviceId)
        }

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
                    KeyEvent.KEYCODE_BUTTON_START,
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

        private fun isKnownGamepadName(name: String): Boolean {
            val lower = name.lowercase()
            return sequenceOf("gamepad", "joystick", "controller", "ipega", "pg-").any { lower.contains(it) }
        }
    }
}
