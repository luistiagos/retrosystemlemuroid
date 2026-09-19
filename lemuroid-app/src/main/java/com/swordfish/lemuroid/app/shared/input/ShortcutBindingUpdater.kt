package com.swordfish.lemuroid.app.shared.input

import android.content.Context
import android.content.Intent
import android.view.InputDevice
import android.view.KeyEvent
import com.swordfish.lemuroid.R
import com.swordfish.lemuroid.app.shared.settings.GameShortcutType
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@OptIn(DelicateCoroutinesApi::class)
class ShortcutBindingUpdater private constructor(
    private val inputDeviceManager: InputDeviceManager,
    private val scope: CoroutineScope,
    val extras: IntentExtras,
) {
    private var firstKeyCodeInCombo: Int? = null

    fun getTitle(context: Context): String {
        return context.getString(R.string.shortcut_binding_update_title, extras.shortcutType.displayName())
    }

    fun getMessage(context: Context): String {
        return context.getString(R.string.shortcut_binding_update_description, extras.device.name)
    }

    fun handleKeyEvent(event: KeyEvent): Boolean {
        return when (event.action) {
            KeyEvent.ACTION_DOWN -> onKeyDown(event)
            KeyEvent.ACTION_UP -> onKeyUp(event)
            else -> false
        }
    }

    private fun onKeyDown(event: KeyEvent): Boolean {
        return isTargetedDevice(event.device)
    }

    private fun onKeyUp(event: KeyEvent): Boolean {
        if (!isTargetedDevice(event.device)) return false

        val firstKey = firstKeyCodeInCombo
        if (firstKey == null) {
            firstKeyCodeInCombo = event.keyCode
            return false // wait for second key
        } else {
            if (firstKey == event.keyCode) return false // ignore same key press
            val combo = Pair(InputKey(firstKey), InputKey(event.keyCode))
            scope.launch {
                inputDeviceManager.updateShortcutBinding(event.device, extras.shortcutType, combo)
            }
            return true
        }
    }

    private fun isTargetedDevice(device: InputDevice?): Boolean {
        return device != null && extras.device.name == device.name
    }

    data class IntentExtras(val device: InputDevice, val shortcutType: GameShortcutType)

    companion object {
        const val REQUEST_DEVICE = "REQUEST_DEVICE"
        const val REQUEST_SHORTCUT_TYPE = "REQUEST_SHORTCUT_TYPE"

        /**
         * `null` quando o intent nao traz o dispositivo ou um atalho valido. Nenhum fluxo do app faz
         * isso: quem faz e o Robo test do Pre-Launch Report, que lanca as activities declaradas sem
         * extras. A activity fecha com `finish()` — lancar aqui derrubava o processo em
         * `performLaunchActivity`.
         */
        fun fromIntent(
            inputDeviceManager: InputDeviceManager,
            scope: CoroutineScope,
            intent: Intent,
        ): ShortcutBindingUpdater? {
            val extras = intent.extras ?: return null
            val device = extras.getParcelable<InputDevice>(REQUEST_DEVICE) ?: return null
            val shortcutType =
                extras.getString(REQUEST_SHORTCUT_TYPE)
                    ?.let { name -> GameShortcutType.values().firstOrNull { it.name == name } }
                    ?: return null
            return ShortcutBindingUpdater(inputDeviceManager, scope, IntentExtras(device, shortcutType))
        }
    }
}
