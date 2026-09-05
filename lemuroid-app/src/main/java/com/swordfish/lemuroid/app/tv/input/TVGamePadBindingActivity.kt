package com.swordfish.lemuroid.app.tv.input

import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.leanback.app.GuidedStepSupportFragment
import androidx.lifecycle.lifecycleScope
import com.swordfish.lemuroid.app.shared.input.InputBindingUpdater
import com.swordfish.lemuroid.app.shared.input.InputDeviceManager
import com.swordfish.lemuroid.app.tv.shared.BaseTVActivity
import javax.inject.Inject

class TVGamePadBindingActivity : BaseTVActivity() {
    override val enableGamepadNavigationTranslation = false

    @Inject
    lateinit var inputDeviceManager: InputDeviceManager

    private lateinit var inputBindingUpdater: InputBindingUpdater

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        inputBindingUpdater = InputBindingUpdater(inputDeviceManager, lifecycleScope, intent)

        if (null == savedInstanceState) {
            val fragment =
                TVGamePadBindingFragment.create(
                    inputBindingUpdater.getTitle(applicationContext),
                    inputBindingUpdater.getMessage(applicationContext),
                )
            GuidedStepSupportFragment.addAsRoot(this, fragment, android.R.id.content)
        }
    }

    override fun onKeyDown(
        keyCode: Int,
        event: KeyEvent,
    ): Boolean {
        return inputBindingUpdater.handleKeyEvent(event)
    }

    override fun onKeyUp(
        keyCode: Int,
        event: KeyEvent,
    ): Boolean {
        val result = inputBindingUpdater.handleKeyEvent(event)

        if (result) {
            finish()
        }

        return result
    }

    // Gatilho analogico nao gera KeyEvent na maioria dos controles. Aqui a janela da
    // Activity e a unica em foco, entao o override basta.
    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (inputBindingUpdater.handleMotionEvent(event)) {
            finish()
            return true
        }

        return super.onGenericMotionEvent(event)
    }
}
