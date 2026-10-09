package com.swordfish.lemuroid.common

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import timber.log.Timber

fun Bundle?.dump(): String {
    if (this == null) return "null"

    val builder = StringBuilder("Extras:\n")
    keySet()
        .toSet()
        .forEach { key ->
            builder.append(key).append(": ").append(get(key)).append("\n")
        }
    return builder.toString()
}

fun Context.animationDuration(): Int {
    return resources.getInteger(android.R.integer.config_mediumAnimTime)
}

fun Context.shortAnimationDuration(): Int {
    return resources.getInteger(android.R.integer.config_shortAnimTime)
}

fun Context.longAnimationDuration(): Int {
    return resources.getInteger(android.R.integer.config_longAnimTime)
}

/**
 * Abre a tela de detalhes do app nas configurações do sistema.
 *
 * Chamado quando o usuário nega uma permissão, para que ele possa concedê-la à mão. Em Android TV /
 * Fire OS essa tela pode não existir: sem o guard, `startActivity` lança `ActivityNotFoundException`
 * e derruba o app justamente depois de um "negar" — ver
 * `docs/bugs/done/2026-09-02-intents-sistema-sem-resolve-crasham-tv.md`.
 *
 * @return `true` se a tela foi aberta.
 */
fun Context.displayDetailsSettingsScreen(): Boolean {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
    intent.data = Uri.fromParts("package", packageName, null)
    return try {
        startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        Timber.w(e, "No activity found to handle ACTION_APPLICATION_DETAILS_SETTINGS")
        false
    } catch (e: SecurityException) {
        Timber.w(e, "Not allowed to open ACTION_APPLICATION_DETAILS_SETTINGS")
        false
    }
}
