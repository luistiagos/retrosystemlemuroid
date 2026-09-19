package com.swordfish.lemuroid.common

import android.content.Context
import android.content.ContextWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Toast
import timber.log.Timber

/**
 * Toast que não derruba o processo quando o token da janela expira.
 *
 * A partir do Android 7.1 (API 25) o `NotificationManagerService` passou a expirar o token da
 * janela do toast por tempo (`scheduleTimeoutLocked`). Quem monta a janela é o app: o
 * `Toast$TN.handleShow` roda numa mensagem da main thread e chama `WindowManager.addView` com esse
 * token. Se a main thread não drenar a mensagem dentro do prazo — o que acontece o tempo todo
 * enquanto o core carrega a ROM em TV box lenta — o token já morreu e o `addView` lança
 * `BadTokenException: Unable to add window -- token android.os.BinderProxy@… is not valid`.
 * O try/catch que engole isso só existe no framework a partir do Android 8 (API 26), então no 7.1
 * a exceção sobe pelo Looper, mata a main thread e cai no `Thread.setDefaultUncaughtExceptionHandler`
 * de quem estiver rodando.
 *
 * O contorno (mesmo do ToastCompat) explora o fato de o `handleShow` pegar o WindowManager de
 * `mView.getContext().getApplicationContext()`: passando um [ContextWrapper] que devolve a si mesmo
 * como application context, conseguimos entregar um `WindowManager` que engole a `BadTokenException`.
 * Sem reflection e sem API oculta.
 *
 * Aplicado em **todas** as versões de propósito. Não dá para confiar no `SDK_INT` desses aparelhos:
 * boxes baratas (MXQ e clones) anunciam Android 9/11 rodando framework 7.1 de verdade, e um guard
 * por versão deixaria justamente o device afetado de fora. Onde o framework já protege, o wrapper é
 * inerte.
 *
 * Ver `documentacao/bugs/open/2026-08-16-tvbox-mxq-crash-toast-badtoken.md`.
 */
fun Context.displayToast(
    string: String,
    length: Int = Toast.LENGTH_SHORT,
) {
    showToastSafely(this, string, length)
}

fun Context.displayToast(
    stringId: Int,
    length: Int = Toast.LENGTH_SHORT,
) {
    showToastSafely(this, getString(stringId), length)
}

private fun showToastSafely(
    context: Context,
    text: CharSequence,
    length: Int,
) {
    try {
        val safeContext = SafeToastContext(context.applicationContext)
        val toast = Toast.makeText(safeContext, text, length)
        hookToastView(toast, safeContext)
        toast.show()
    } catch (e: Throwable) {
        // Um aviso de UI nunca pode derrubar a tela que o dispara.
        Timber.w(e, "Failed to display toast")
    }
}

private fun hookToastView(toast: Toast, fallbackContext: SafeToastContext) {
    try {
        @Suppress("DEPRECATION")
        val view = toast.view ?: return
        val field = View::class.java.getDeclaredField("mContext")
        field.isAccessible = true
        val currentContext = field.get(view) as? Context
        if (currentContext !is SafeToastContext) {
            field.set(view, SafeToastContext(currentContext ?: fallbackContext))
        }
    } catch (e: Throwable) {
        Timber.d(e, "Could not hook toast view mContext via reflection")
    }
}

private class SafeToastContext(base: Context) : ContextWrapper(base) {
    override fun getApplicationContext(): Context {
        val app = baseContext.applicationContext
        return if (app === this || app === baseContext) {
            this
        } else {
            SafeToastContext(app)
        }
    }

    override fun getSystemService(name: String): Any? {
        if (name == Context.LAYOUT_INFLATER_SERVICE) {
            val inflater = super.getSystemService(name) as? LayoutInflater
            return inflater?.cloneInContext(this)
        }
        val service = super.getSystemService(name)
        return if (name == Context.WINDOW_SERVICE && service is WindowManager) {
            SafeWindowManager(service)
        } else {
            service
        }
    }
}

private class SafeWindowManager(
    private val delegate: WindowManager,
) : WindowManager by delegate {
    override fun addView(
        view: View,
        params: ViewGroup.LayoutParams,
    ) {
        try {
            delegate.addView(view, params)
        } catch (e: WindowManager.BadTokenException) {
            // Token expirado antes da main thread desenhar o toast: descarta o aviso, mantém o app.
            Timber.w("Dropped toast with expired window token: ${e.message}")
        } catch (e: Throwable) {
            Timber.w(e, "Unexpected error in SafeWindowManager.addView")
        }
    }

    override fun removeView(view: View) {
        try {
            delegate.removeView(view)
        } catch (e: Throwable) {
            Timber.w("Failed to remove toast view: ${e.message}")
        }
    }

    override fun removeViewImmediate(view: View) {
        try {
            delegate.removeViewImmediate(view)
        } catch (e: Throwable) {
            Timber.w("Failed to removeViewImmediate toast view: ${e.message}")
        }
    }
}
