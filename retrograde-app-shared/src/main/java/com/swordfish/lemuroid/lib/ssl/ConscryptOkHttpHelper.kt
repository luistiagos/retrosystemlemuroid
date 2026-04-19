package com.swordfish.lemuroid.lib.ssl

import okhttp3.OkHttpClient
import org.conscrypt.Conscrypt
import timber.log.Timber
import java.security.SecureRandom
import java.security.Security
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Helper that explicitly configures OkHttpClient to use Conscrypt's BoringSSL
 * for TLS, with a trust-all TrustManager to ensure connectivity on old Android
 * devices that ship with outdated certificate stores (e.g. lack ISRG Root X1).
 *
 * Usage:
 *   OkHttpClient.Builder()
 *       .applyConscryptTls()       // ← adds sslSocketFactory + trust-all manager
 *       .connectTimeout(…)
 *       .build()
 */
object ConscryptOkHttpHelper {

    private val conscryptProvider by lazy {
        try {
            val provider = Conscrypt.newProvider()
            Security.insertProviderAt(provider, 1)
            Timber.d("Conscrypt provider installed: ${provider.name} ${provider.version}")
            provider
        } catch (e: Throwable) {
            Timber.e(e, "Failed to create Conscrypt provider — falling back to system defaults")
            null
        }
    }

    private val trustAll = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    /**
     * Applies Conscrypt TLS with a trust-all TrustManager to an [OkHttpClient.Builder].
     * This guarantees connectivity on old Android devices that lack modern root CAs
     * (e.g. ISRG Root X1 / Let's Encrypt) in their system trust store.
     * The trust-all is applied regardless of whether Conscrypt loads successfully.
     */
    fun OkHttpClient.Builder.applyConscryptTls(): OkHttpClient.Builder {
        // Always apply trust-all first — this guarantees no SSLHandshakeException
        // regardless of Conscrypt availability.
        try {
            val sslContext = SSLContext.getInstance("TLS").apply {
                init(null, arrayOf<TrustManager>(trustAll), SecureRandom())
            }
            sslSocketFactory(sslContext.socketFactory, trustAll)
            hostnameVerifier { _, _ -> true }
        } catch (e: Exception) {
            Timber.e(e, "Failed to apply trust-all TrustManager — SSL validation will use system defaults")
        }

        // Additionally try to use Conscrypt for modern cipher suites and TLS 1.3.
        val provider = conscryptProvider ?: return this
        try {
            val sslContext = SSLContext.getInstance("TLS", provider).apply {
                init(null, arrayOf<TrustManager>(trustAll), SecureRandom())
            }
            sslSocketFactory(sslContext.socketFactory, trustAll)
            Timber.d("OkHttp configured with Conscrypt TLS (trust-all)")
        } catch (e: Exception) {
            Timber.w(e, "Could not apply Conscrypt TLS to OkHttpClient — trust-all still active via system TLS")
        }
        return this
    }
}
