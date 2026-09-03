package com.swordfish.lemuroid.app.shared.input

import android.content.Context
import android.content.SharedPreferences
import android.hardware.input.InputManager
import android.os.Handler
import android.os.HandlerThread
import android.view.InputDevice
import android.view.KeyEvent
import androidx.core.content.edit
import com.fredporciuncula.flow.preferences.FlowSharedPreferences
import com.swordfish.lemuroid.app.shared.input.lemuroiddevice.LemuroidInputDeviceGamePad
import com.swordfish.lemuroid.app.shared.input.lemuroiddevice.getLemuroidInputDevice
import com.swordfish.lemuroid.app.shared.settings.GameShortcut
import com.swordfish.lemuroid.app.shared.settings.GameShortcutType
import dagger.Lazy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.PairSerializer
import kotlinx.serialization.json.Json

@Serializable
private data class PortOrder(val descriptors: List<String> = emptyList())

@OptIn(ExperimentalCoroutinesApi::class)
class InputDeviceManager(
    private val context: Context,
    sharedPreferencesFactory: Lazy<SharedPreferences>,
) {
    private val inputManager = context.getSystemService(Context.INPUT_SERVICE) as InputManager

    // InputDevice.hasKeys() crosses Binder into InputManagerService. Keep device
    // enumeration and listener registration away from the main looper.
    private val inputDeviceHandler by lazy {
        val thread = HandlerThread(INPUT_DEVICE_THREAD_NAME).apply { start() }
        Handler(thread.looper)
    }

    private val sharedPreferences by lazy { sharedPreferencesFactory.get() }

    private val flowSharedPreferences by lazy { FlowSharedPreferences(sharedPreferences) }

    fun getInputBindingsObservable(): Flow<(InputDevice?) -> Map<InputKey, RetroKey>> {
        return getEnabledInputsObservable()
            .flatMapLatest { devices ->
                val allDeviceBindingsFlows = devices.map { device -> getBindingsFlow(device).map { device to it } }
                combine(allDeviceBindingsFlows) { it.toMap() }
            }
            .map { bindings -> { bindings[it] ?: mapOf() } }
    }

    fun getGameShortcutsObservable(): Flow<Map<InputDevice, List<GameShortcut>>> {
        return getEnabledInputsObservable()
            .flatMapLatest { devices ->
                val allShortcutFlows = devices.map { device -> getShortcutBindingsFlow(device).map { device to it } }
                combine(allShortcutFlows) { it.toMap() }
            }
    }

    fun getGamePadsPortMapperObservable(): Flow<(InputDevice?) -> Int?> {
        return combine(getEnabledInputsObservable(), getPortOrderFlow()) { gamePads, portOrder ->
            val sorted = if (portOrder.isEmpty()) {
                gamePads
            } else {
                val portOrderSet = portOrder.toSet()
                val ordered = portOrder.mapNotNull { desc -> gamePads.find { it.descriptor == desc } }
                val remaining = gamePads.filter { d -> d.descriptor !in portOrderSet }
                ordered + remaining
            }
            val portMappings = sorted.mapIndexed { index, inputDevice -> inputDevice.id to index }.toMap()
            sorted.forEach { d ->
                android.util.Log.d("INPUT_DIAG", "registered gamepad id=${d.id} name=${d.name} port=${portMappings[d.id]} descriptor=${d.descriptor}")
            }
            val mapper: (InputDevice?) -> Int? = { portMappings[it?.id] }
            mapper
        }
    }

    fun getPortOrderFlow(): Flow<List<String>> {
        return flowSharedPreferences.getString(PORT_ORDER_PREFERENCE_KEY)
            .asFlow()
            .map { pref ->
                if (pref.isNullOrEmpty()) emptyList()
                else runCatching { Json.decodeFromString(PortOrder.serializer(), pref).descriptors }.getOrDefault(emptyList())
            }
            .flowOn(Dispatchers.IO)
    }

    suspend fun savePortOrder(descriptors: List<String>) = withContext(Dispatchers.IO) {
        sharedPreferences.edit(commit = true) {
            putString(PORT_ORDER_PREFERENCE_KEY, Json.encodeToString(PortOrder.serializer(), PortOrder(descriptors)))
        }
    }

    private fun getBindingsFlow(inputDevice: InputDevice): Flow<Map<InputKey, RetroKey>> {
        return flowSharedPreferences.getString(computeKeyBindingGamePadPreference(inputDevice))
            .asFlow()
            .map { parseBindingsPreference(it, inputDevice) }
            .flowOn(Dispatchers.IO)
    }

    private fun getShortcutBindingsFlow(device: InputDevice): Flow<List<GameShortcut>> {
        val flows =
            GameShortcutType.entries.map { type ->
                flowSharedPreferences.getString(computeGameShortcutPreference(device, type))
                    .asFlow()
                    .map { parseShortcutPreference(it, device, type) }
            }
        return if (flows.isEmpty()) {
            flowOf(emptyList())
        } else {
            combine(flows) { shortcuts -> shortcuts.mapNotNull { it } }
        }.flowOn(Dispatchers.IO)
    }

    private fun parseShortcutPreference(
        preference: String?,
        device: InputDevice,
        type: GameShortcutType,
    ): GameShortcut? {
        return if (preference.isNullOrEmpty()) {
            GameShortcut.getDefault(device, type)
        } else {
            val decoded = runCatching { Json.decodeFromString(bindingsComboSerializer, preference) }
            val combo = decoded.getOrNull() ?: return GameShortcut.getDefault(device, type)
            GameShortcut(type = type, keys = setOf(combo.first.keyCode, combo.second.keyCode))
        }
    }

    suspend fun getCurrentBindings(inputDevice: InputDevice): Map<InputKey, RetroKey> {
        return withContext(Dispatchers.IO) {
            val preference =
                sharedPreferences.getString(
                    computeKeyBindingGamePadPreference(inputDevice),
                    "",
                )
            parseBindingsPreference(preference, inputDevice)
        }
    }

    suspend fun getCurrentShortcuts(inputDevice: InputDevice): List<GameShortcut> {
        return withContext(Dispatchers.IO) {
            GameShortcutType.entries.mapNotNull { type ->
                val preference = sharedPreferences.getString(computeGameShortcutPreference(inputDevice, type), "")
                parseShortcutPreference(preference, inputDevice, type)
            }
        }
    }

    private fun parseBindingsPreference(
        preference: String?,
        inputDevice: InputDevice,
    ): Map<InputKey, RetroKey> {
        val defaultBindings = getDefaultBinding(inputDevice)
        if (preference.isNullOrEmpty()) {
            return defaultBindings
        }

        val decoded = runCatching { Json.decodeFromString(bindingsMapSerializer, preference) }.getOrNull()
            ?: return defaultBindings

        // Sanitiza entradas corrompidas ou legadas que mapearam teclas para KEYCODE_UNKNOWN ou 0.
        val sanitized = decoded.filterValues { it.keyCode != KeyEvent.KEYCODE_UNKNOWN && it.keyCode != 0 }
        return defaultBindings + sanitized
    }

    suspend fun updateBinding(
        inputDevice: InputDevice,
        retroKey: RetroKey,
        inputKey: InputKey,
    ) = withContext(Dispatchers.IO) {
        val prevBindings =
            getCurrentBindings(inputDevice).entries
                .map { it.key to it.value }
                .filter { (_, value) -> value != retroKey }

        val bindings = prevBindings + listOf(inputKey to retroKey)

        val sharedPreferencesContent = Json.encodeToString(bindingsMapSerializer, bindings.toMap())

        sharedPreferences.edit(commit = true) {
            putString(computeKeyBindingGamePadPreference(inputDevice), sharedPreferencesContent)
        }
    }

    suspend fun updateShortcutBinding(
        inputDevice: InputDevice,
        shortcutType: GameShortcutType,
        inputKeys: Pair<InputKey, InputKey>,
    ) = withContext(Dispatchers.IO) {
        sharedPreferences.edit(commit = true) {
            val key = computeGameShortcutPreference(inputDevice, shortcutType)
            val value = Json.encodeToString(bindingsComboSerializer, inputKeys)
            putString(key, value)
        }
    }

    suspend fun resetAllBindings() =
        withContext(Dispatchers.IO) {
            sharedPreferences.edit(commit = true) {
                sharedPreferences.all.keys
                    .filter { it.startsWith(GAME_PAD_BINDING_PREFERENCE_BASE_KEY) }
                    .forEach { remove(it) }
            }
        }

    fun getGamePadsObservable(): Flow<List<InputDevice>> {
        return callbackFlow {
            val refresh = Runnable { trySend(getAllGamePads()) }

            fun onDeviceEvent(deviceId: Int) {
                LemuroidInputDeviceGamePad.invalidateKeySupportCache(deviceId)
                inputDeviceHandler.removeCallbacks(refresh)
                inputDeviceHandler.postDelayed(refresh, INPUT_DEVICE_CHANGE_DEBOUNCE_MS)
            }

            val listener =
                object : InputManager.InputDeviceListener {
                    override fun onInputDeviceAdded(deviceId: Int) = onDeviceEvent(deviceId)

                    override fun onInputDeviceChanged(deviceId: Int) = onDeviceEvent(deviceId)

                    override fun onInputDeviceRemoved(deviceId: Int) = onDeviceEvent(deviceId)
                }

            inputManager.registerInputDeviceListener(listener, inputDeviceHandler)
            inputDeviceHandler.post(refresh)

            awaitClose {
                inputDeviceHandler.removeCallbacks(refresh)
                inputManager.unregisterInputDeviceListener(listener)
            }
        }.flowOn(Dispatchers.IO)
    }

    fun getDistinctGamePadsObservable(): Flow<List<InputDevice>> {
        return getGamePadsObservable()
            .map { device -> device.distinctBy { it.descriptor } }
    }

    fun getEnabledInputsObservable(): Flow<List<InputDevice>> {
        return getGamePadsObservable()
            .flatMapLatest { devices ->
                if (devices.isEmpty()) {
                    return@flatMapLatest flowOf(emptyList())
                }

                val deviceStatuesFlows = devices.map { getDeviceStatus(it) }
                combine(deviceStatuesFlows) { deviceStatues ->
                    deviceStatues
                        .filter { it.enabled }
                        .map { it.device }
                }
            }
    }

    private fun getDeviceStatus(inputDevice: InputDevice): Flow<DeviceStatus> {
        val defaultValue = inputDevice.getLemuroidInputDevice().isEnabledByDefault(context)
        return flowSharedPreferences.getBoolean(computeEnabledGamePadPreference(inputDevice), defaultValue)
            .asFlow()
            .map { isEnabled -> DeviceStatus(inputDevice, isEnabled) }
    }

    private fun getDefaultBinding(inputDevice: InputDevice): Map<InputKey, RetroKey> {
        return inputDevice
            .getLemuroidInputDevice()
            .getDefaultBindings()
    }

    private fun getAllGamePads(): List<InputDevice> {
        return runCatching {
            InputDevice.getDeviceIds()
                .map { InputDevice.getDevice(it) }
                .filterNotNull()
                .filter { device ->
                    val supported = device.getLemuroidInputDevice().isSupported()
                    val blacklisted = device.name in BLACKLISTED_DEVICES
                    if (!supported || blacklisted) {
                        android.util.Log.d(
                            "INPUT_DIAG",
                            "rejected device name=${device.name} sources=${device.sources} " +
                                "isVirtual=${device.isVirtual} supported=$supported blacklisted=$blacklisted",
                        )
                    }
                    supported && !blacklisted
                }
                .sortedWith(
                    compareByDescending<InputDevice> { it.gamepadPriority() }
                        .thenBy { it.controllerNumber },
                )
        }.getOrNull() ?: listOf()
    }

    // Scores a device so that real joysticks always occupy the lowest port slots.
    // Score 3: SOURCE_JOYSTICK + has motion ranges  → PS4, Xbox, GameSir, etc.
    // Score 2: SOURCE_JOYSTICK only                 → rare, but still a real controller
    // Score 1: has motion ranges (hat axes, etc.)   → D-pad-only gamepads
    // Score 0: no joystick source, no axes          → TV remotes masquerading as gamepads
    private fun InputDevice.gamepadPriority(): Int {
        var score = 0
        if ((sources and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK) score += 2
        if (motionRanges.isNotEmpty()) score += 1
        return score
    }

    private data class DeviceStatus(val device: InputDevice, val enabled: Boolean)

    companion object {
        private const val GAME_PAD_BINDING_PREFERENCE_BASE_KEY = "pref_key_gamepad_binding_key"
        private const val GAME_PAD_ENABLED_PREFERENCE_BASE_KEY = "pref_key_gamepad_enabled"
        private const val PORT_ORDER_PREFERENCE_KEY = "pref_key_port_order"
        private const val INPUT_DEVICE_THREAD_NAME = "lemuroid-input-devices"
        private const val INPUT_DEVICE_CHANGE_DEBOUNCE_MS = 150L

        private val bindingsMapSerializer = MapSerializer(InputKey.serializer(), RetroKey.serializer())
        private val bindingsComboSerializer = PairSerializer(InputKey.serializer(), InputKey.serializer())

        private fun getSharedPreferencesId(inputDevice: InputDevice) = inputDevice.descriptor

        // This is a last resort, but sadly there are some devices which present keys and the
        // SOURCE_GAMEPAD, so we basically black list them.
        private val BLACKLISTED_DEVICES =
            setOf(
                "virtual-search",
                "sunxi-ir-uinput",
            )

        fun computeEnabledGamePadPreference(inputDevice: InputDevice) =
            "${GAME_PAD_ENABLED_PREFERENCE_BASE_KEY}_${getSharedPreferencesId(inputDevice)}"

        fun computeGameShortcutPreference(
            inputDevice: InputDevice,
            type: GameShortcutType,
        ) = "${GAME_PAD_BINDING_PREFERENCE_BASE_KEY}_${getSharedPreferencesId(inputDevice)}_shortcut_$type."

        fun computeKeyBindingGamePadPreference(inputDevice: InputDevice) =
            "${GAME_PAD_BINDING_PREFERENCE_BASE_KEY}_${getSharedPreferencesId(inputDevice)}"

        fun computeKeyBindingRetroKeyPreference(
            inputDevice: InputDevice,
            retroKey: RetroKey,
        ): String {
            val keyCode = retroKey.keyCode
            return "${GAME_PAD_BINDING_PREFERENCE_BASE_KEY}_${getSharedPreferencesId(inputDevice)}_$keyCode"
        }

        val OUTPUT_KEYS: List<RetroKey> =
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
                KeyEvent.KEYCODE_UNKNOWN,
                KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_RIGHT,
            )
    }
}
