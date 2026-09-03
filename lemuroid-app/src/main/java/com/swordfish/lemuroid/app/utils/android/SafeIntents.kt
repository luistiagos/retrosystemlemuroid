package com.swordfish.lemuroid.app.utils.android

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.annotation.StringRes
import com.swordfish.lemuroid.common.displayToast
import timber.log.Timber

/**
 * Dispara uma Intent de sistema sem assumir que existe alguém para atendê-la.
 *
 * Builds de Android TV e Fire OS frequentemente não embarcam `DocumentsUI`/Files, e a tela de
 * "acesso a todos os arquivos" simplesmente não existe. Nesses aparelhos `startActivity` com uma
 * Intent implícita lança `ActivityNotFoundException` e derruba o app — foi o crash em
 * `Instrumentation.checkStartActivityResult` visto no Fire TV Stick e na TCL Smart TV.
 *
 * O guard é try/catch, e **não** `resolveActivity`, de propósito: o app não declara `<queries>` no
 * manifesto, então a partir da API 30 o filtro de visibilidade de pacotes faz `resolveActivity`
 * devolver `null` mesmo quando existe um handler — um guard por resolve esconderia botões que
 * funcionam. Iniciar a Activity por Intent implícita continua permitido sem `<queries>`, então o
 * try/catch acerta nos dois sentidos.
 *
 * @param fallbackMessage aviso exibido quando não há handler. Passe `null` quando houver outra
 *   Intent para tentar em seguida — só a última da cadeia deve falar com o usuário.
 * @return `true` se a Activity foi iniciada.
 *
 * Ver `documentacao/bugs/done/2026-09-02-intents-sistema-sem-resolve-crasham-tv.md`.
 */
fun Context.startActivitySafely(
    intent: Intent,
    @StringRes fallbackMessage: Int? = null,
): Boolean =
    try {
        startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        reportMissingHandler(e, intent.action, fallbackMessage)
        false
    } catch (e: SecurityException) {
        // Handler existe mas não é exportado: para o usuário o efeito é o mesmo de não existir.
        reportMissingHandler(e, intent.action, fallbackMessage)
        false
    }

/**
 * Mesmo guard para os contratos de `ActivityResult`. `ActivityResultLauncher.launch` monta a Intent
 * e chama `startActivityForResult` de forma síncrona, então a `ActivityNotFoundException` sobe pelo
 * call-site — foi assim que o seletor `application/zip` crashou no Fire TV, com o
 * `checkStartActivityResult` na mesma pilha do `dispatchKeyEvent` do controle remoto.
 */
fun <I> ActivityResultLauncher<I>.launchSafely(
    context: Context,
    input: I,
    @StringRes fallbackMessage: Int,
): Boolean =
    try {
        launch(input)
        true
    } catch (e: ActivityNotFoundException) {
        context.reportMissingHandler(e, null, fallbackMessage)
        false
    } catch (e: SecurityException) {
        context.reportMissingHandler(e, null, fallbackMessage)
        false
    }

private fun Context.reportMissingHandler(
    error: Exception,
    action: String?,
    @StringRes fallbackMessage: Int?,
) {
    Timber.w(error, "No activity found to handle intent (action=$action)")
    fallbackMessage?.let { displayToast(it, Toast.LENGTH_LONG) }
}
