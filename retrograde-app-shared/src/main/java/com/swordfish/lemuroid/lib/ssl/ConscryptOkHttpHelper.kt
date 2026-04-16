package com.swordfish.lemuroid.lib.ssl

import okhttp3.OkHttpClient
import org.conscrypt.Conscrypt
import timber.log.Timber
import java.security.KeyStore
import java.security.Security
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Helper that explicitly configures OkHttpClient to use Conscrypt's BoringSSL
 * for TLS, guaranteeing modern root CAs (ISRG Root X1, etc.) on old Android
 * devices that ship with outdated certificate stores.
 *
 * Usage:
 *   OkHttpClient.Builder()
 *       .applyConscryptTls()       // ← adds sslSocketFactory + trustManager
 *       .connectTimeout(…)
 *       .build()
 */
object ConscryptOkHttpHelper {

    private val conscryptProvider by lazy {
        try {
            val provider = Conscrypt.newProvider()
            // Ensure it's installed as the first provider system-wide as well
            Security.insertProviderAt(provider, 1)
            Timber.d("Conscrypt provider installed: ${provider.name} ${provider.version}")
            provider
        } catch (e: Throwable) {
            Timber.e(e, "Failed to create Conscrypt provider — falling back to system defaults")
            null
        }
    }

    private val trustManager: X509TrustManager? by lazy {
        try {
            val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            tmf.init(null as KeyStore?)
            tmf.trustManagers
                .filterIsInstance<X509TrustManager>()
                .firstOrNull()
        } catch (e: Exception) {
            Timber.e(e, "Failed to build TrustManager")
            null
        }
    }

    /**
     * Applies Conscrypt TLS to an [OkHttpClient.Builder].
     * If Conscrypt can't be loaded, falls back to system defaults silently.
     */
    fun OkHttpClient.Builder.applyConscryptTls(): OkHttpClient.Builder {
        val provider = conscryptProvider ?: return this
        val tm = trustManager ?: return this

        try {
            val sslContext = SSLContext.getInstance("TLS", provider)
            sslContext.init(null, arrayOf(tm), null)
            sslSocketFactory(sslContext.socketFactory, tm)
            Timber.d("OkHttp configured with Conscrypt TLS")
        } catch (e: Exception) {
            Timber.w(e, "Could not apply Conscrypt TLS to OkHttpClient — using system default")
        }
        return this
    }
}
