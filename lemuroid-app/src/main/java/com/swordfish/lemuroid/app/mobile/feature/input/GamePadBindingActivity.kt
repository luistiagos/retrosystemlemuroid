package com.swordfish.lemuroid.app.mobile.feature.input

import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.compose.setContent
import androidx.compose.foundation.focusable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.lifecycleScope
import com.swordfish.lemuroid.app.mobile.shared.compose.ui.AppTheme
import com.swordfish.lemuroid.app.shared.input.InputBindingUpdater
import com.swordfish.lemuroid.app.shared.input.InputDeviceManager
import com.swordfish.lemuroid.lib.android.RetrogradeActivity
import timber.log.Timber
import javax.inject.Inject

class GamePadBindingActivity : RetrogradeActivity() {
    @Inject
    lateinit var inputDeviceManager: InputDeviceManager

    private lateinit var inputBindingUpdater: InputBindingUpdater

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Sem extras = nao veio da tela de controles (o Robo test do Pre-Launch Report lanca toda
        // activity declarada assim). Fechar, nao lancar.
        inputBindingUpdater =
            InputBindingUpdater.fromIntent(inputDeviceManager, lifecycleScope, intent)
                ?: run { finish(); return }

        setContent {
            AppTheme {
                val focusRequester = remember { FocusRequester() }

                AlertDialog(
                    modifier =
                        Modifier
                            .focusRequester(focusRequester)
                            .focusable()
                            .onKeyEvent { handleKeyEvent(it.nativeKeyEvent) }
                            .onGloballyPositioned { focusRequester.requestFocus() },
                    title = { Text(text = inputBindingUpdater.getTitle(applicationContext)) },
                    text = {
                        GenericMotionCapture(::handleMotionEvent)
                        Text(text = inputBindingUpdater.getMessage(applicationContext))
                    },
                    onDismissRequest = { finish() },
                    confirmButton = {},
                )
            }
        }
    }

    private fun handleKeyEvent(event: KeyEvent): Boolean {
        Timber.i("Received key event: $event")
        val result = inputBindingUpdater.handleKeyEvent(event)

        if (event.action == KeyEvent.ACTION_UP && result) {
            finish()
        }

        return result
    }

    // Vale so quando o dialogo nao esta na frente; o caminho normal e o listener
    // instalado por GenericMotionCapture. Ver o comentario la.
    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        return handleMotionEvent(event) || super.onGenericMotionEvent(event)
    }

    private fun handleMotionEvent(event: MotionEvent): Boolean {
        val result = inputBindingUpdater.handleMotionEvent(event)

        if (result) {
            finish()
        }

        return result
    }

    @dagger.Module
    abstract class Module
}

// O AlertDialog do Compose vive numa janela propria, e evento de motion (gatilho
// analogico) vai para a janela com foco - nunca chega ao onGenericMotionEvent da
// Activity. O DecorView dessa janela e o ultimo a ver o evento, depois que a
// hierarquia Compose o recusa, entao e ali que da para captura-lo. Este composable
// precisa ficar DENTRO do conteudo do dialogo: e o LocalView de la que resolve para
// a janela certa.
@Composable
private fun GenericMotionCapture(onMotionEvent: (MotionEvent) -> Boolean) {
    val rootView = LocalView.current.rootView
    DisposableEffect(rootView) {
        rootView.setOnGenericMotionListener { _, event -> onMotionEvent(event) }
        onDispose { rootView.setOnGenericMotionListener(null) }
    }
}
