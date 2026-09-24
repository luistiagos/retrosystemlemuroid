// `NetworkInfo` e companhia estão depreciados desde a API 29 e são exatamente o ponto deste
// arquivo: são o que existe abaixo da 23. A supressão é do arquivo todo para não repetir a
// anotação em cada linha do caminho legado.
@file:Suppress("DEPRECATION")

package com.swordfish.lemuroid.app.utils.android

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import android.os.Build
import android.telephony.TelephonyManager
import timber.log.Timber

/*
 * Estado da rede sem assumir APIs acima do `minSdkVersion = 21`.
 *
 * Três chamadas do caminho de download eram novas demais para o aparelho mais antigo que o app
 * declara suportar:
 *
 *  - `Context.getSystemService(Class)` — API 23. A sobrecarga por String existe desde a API 1 e
 *    devolve o mesmo objeto, então aqui não há guard: é só usar a antiga.
 *  - `ConnectivityManager.getActiveNetwork` — API 23. Sem ela resta `activeNetworkInfo`, que é
 *    depreciado mas continua respondendo em todas as versões.
 *
 * `getNetworkCapabilities(Network)`, `NetworkRequest` e `NetworkCallback` são da API 21 e podem ser
 * chamados direto.
 *
 * O guard por `SDK_INT` anda junto com `catch (NoSuchMethodError)` pelo motivo do pitfall 12 do
 * CLAUDE.md: as TV Box baratas anunciam Android 9/11 rodando 7.1 de verdade, e nelas o teste de
 * versão passa enquanto o `framework.jar` segue sem o método.
 */

/** [ConnectivityManager] pela sobrecarga por String de `getSystemService`, que existe desde a API 1. */
fun Context.connectivityManagerCompat(): ConnectivityManager? =
    getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

/** True quando a rede ativa é Wi-Fi. */
fun ConnectivityManager.isOnWifiCompat(): Boolean {
    activeCapabilitiesCompat()?.let { return it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) }

    val info = activeNetworkInfoCompat() ?: return false
    return info.isConnected && info.type == ConnectivityManager.TYPE_WIFI
}

/** True quando existe rede ativa com acesso à internet. */
fun ConnectivityManager.hasInternetCompat(): Boolean {
    activeCapabilitiesCompat()?.let { return it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) }

    return activeNetworkInfoCompat()?.isConnected == true
}

/** Geração aproximada da rede móvel ativa, ou `null` quando a rede ativa não é móvel. */
fun ConnectivityManager.mobileGenerationCompat(): MobileGeneration? {
    activeCapabilitiesCompat()?.let { caps ->
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) return null
        return when {
            caps.linkDownstreamBandwidthKbps >= 20000 -> MobileGeneration.G5
            caps.linkDownstreamBandwidthKbps >= 1000 -> MobileGeneration.G4
            caps.linkDownstreamBandwidthKbps >= 200 -> MobileGeneration.G3
            else -> MobileGeneration.G2
        }
    }

    val info = activeNetworkInfoCompat() ?: return null
    if (info.type != ConnectivityManager.TYPE_MOBILE) return null
    // Sem NetworkCapabilities não há banda estimada; o subtipo é o que existe na API 21. Constantes
    // posteriores à 22 (NR, GSM, TD_SCDMA) ficam de fora de propósito: este caminho só roda em
    // aparelho velho, e subtipo desconhecido cai no rótulo genérico do chamador.
    return when (info.subtype) {
        TelephonyManager.NETWORK_TYPE_LTE -> MobileGeneration.G4
        in MOBILE_3G_SUBTYPES -> MobileGeneration.G3
        in MOBILE_2G_SUBTYPES -> MobileGeneration.G2
        else -> null
    }
}

enum class MobileGeneration { G2, G3, G4, G5 }

private val MOBILE_3G_SUBTYPES =
    setOf(
        TelephonyManager.NETWORK_TYPE_HSPAP,
        TelephonyManager.NETWORK_TYPE_HSPA,
        TelephonyManager.NETWORK_TYPE_HSDPA,
        TelephonyManager.NETWORK_TYPE_HSUPA,
        TelephonyManager.NETWORK_TYPE_UMTS,
        TelephonyManager.NETWORK_TYPE_EHRPD,
        TelephonyManager.NETWORK_TYPE_EVDO_0,
        TelephonyManager.NETWORK_TYPE_EVDO_A,
        TelephonyManager.NETWORK_TYPE_EVDO_B,
    )

private val MOBILE_2G_SUBTYPES =
    setOf(
        TelephonyManager.NETWORK_TYPE_GPRS,
        TelephonyManager.NETWORK_TYPE_EDGE,
        TelephonyManager.NETWORK_TYPE_CDMA,
        TelephonyManager.NETWORK_TYPE_1xRTT,
        TelephonyManager.NETWORK_TYPE_IDEN,
    )

/**
 * `NetworkCapabilities` da rede ativa, ou `null` quando não dá para obtê-las.
 *
 * `null` é ambíguo de propósito — significa tanto "o aparelho não tem `getActiveNetwork`" quanto
 * "não há rede ativa" — porque nos dois casos o chamador faz a mesma coisa: cair para
 * `activeNetworkInfo`, que responde `null`/desconectado quando realmente não há rede.
 */
private fun ConnectivityManager.activeCapabilitiesCompat(): NetworkCapabilities? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null

    // try/catch simples em vez de runCatching: mantém a chamada guardada lexicalmente dentro do
    // teste de SDK_INT, onde a análise NewApi do lint é inequívoca (mesmo motivo de ContextUtils).
    return try {
        getNetworkCapabilities(activeNetwork)
    } catch (e: NoSuchMethodError) {
        Timber.w(e, "getActiveNetwork ausente com SDK_INT=${Build.VERSION.SDK_INT}; usando activeNetworkInfo")
        null
    } catch (e: SecurityException) {
        // Algumas ROMs derrubam a consulta mesmo com ACCESS_NETWORK_STATE declarada.
        Timber.w(e, "getNetworkCapabilities negou a consulta")
        null
    }
}

private fun ConnectivityManager.activeNetworkInfoCompat(): NetworkInfo? {
    return runCatching { activeNetworkInfo }.getOrNull()
}
