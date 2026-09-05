package com.swordfish.lemuroid.app.shared.input

import android.content.Context
import android.content.Intent
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import com.swordfish.lemuroid.R
import com.swordfish.lemuroid.app.shared.input.inputclass.AXIS_PRESS_THRESHOLD
import com.swordfish.lemuroid.app.shared.input.inputclass.getInputClass
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import timber.log.Timber

@OptIn(DelicateCoroutinesApi::class)
class InputBindingUpdater(
    private val inputDeviceManager: InputDeviceManager,
    private val scope: CoroutineScope,
    intent: Intent,
) {
    val extras = parseExtras(intent)

    fun getTitle(context: Context): String {
        val keyName = InputKey(extras.retroKey).displayName()
        return context.getString(R.string.gamepad_binding_update_title, keyName)
    }

    fun getMessage(context: Context): String {
        return context.getString(R.string.gamepad_binding_update_description, extras.device.name)
    }

    // Na maioria dos controles modernos o gatilho nao gera KeyEvent nenhum: o unico
    // sinal e o eixo (AXIS_LTRIGGER/AXIS_BRAKE, AXIS_RTRIGGER/AXIS_THROTTLE). Sem
    // isto a linha de L2/R2 abriria o dialogo e apertar o gatilho nao faria nada.
    // Arma na passagem do limiar e grava na soltura, espelhando o par
    // ACTION_DOWN/ACTION_UP das teclas.
    private var armedAxisKeyCode: Int? = null

    fun handleMotionEvent(event: MotionEvent): Boolean {
        val device = event.device ?: return false
        if (!isTargetedDevice(device)) return false

        val pressedKeyCode =
            device.getInputClass().getAxesMap()
                .filterKeys { event.getAxisValue(it) > AXIS_PRESS_THRESHOLD }
                .values
                .minOrNull()

        if (pressedKeyCode != null) {
            armedAxisKeyCode = pressedKeyCode
            return false
        }

        val releasedKeyCode = armedAxisKeyCode ?: return false
        armedAxisKeyCode = null

        Timber.d("Received input binding axis event: $releasedKeyCode $device")

        scope.launch {
            inputDeviceManager.updateBinding(device, RetroKey(extras.retroKey), InputKey(releasedKeyCode))
        }

        return true
    }

    fun handleKeyEvent(event: KeyEvent): Boolean {
        Timber.d("Received input binding event: $event ${event.device}")
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

        scope.launch {
            inputDeviceManager.updateBinding(event.device, RetroKey(extras.retroKey), InputKey(event.keyCode))
        }

        return true
    }

    private fun isTargetedDevice(device: InputDevice?): Boolean {
        return device != null && extras.device.name == device.name
    }

    private fun parseExtras(intent: Intent): IntentExtras {
        val device =
            intent.extras?.getParcelable<InputDevice>(REQUEST_DEVICE)
                ?: throw IllegalArgumentException("REQUEST_DEVICE has not been passed")

        val retroKey =
            intent.extras?.getInt(REQUEST_RETRO_KEY)
                ?: throw IllegalArgumentException("REQUEST_RETRO_KEY has not been passed")

        return IntentExtras(device, retroKey)
    }

    data class IntentExtras(val device: InputDevice, val retroKey: Int)

    companion object {
        const val REQUEST_DEVICE = "REQUEST_DEVICE"
        const val REQUEST_RETRO_KEY = "REQUEST_RETRO_KEY"
    }
}
