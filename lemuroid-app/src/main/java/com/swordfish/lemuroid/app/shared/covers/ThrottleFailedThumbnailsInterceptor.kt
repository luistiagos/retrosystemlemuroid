package com.swordfish.lemuroid.app.shared.covers

import android.util.LruCache
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

object ThrottleFailedThumbnailsInterceptor : Interceptor {
    private val failedThumbnailsStatusCode = LruCache<String, Int>(1024)

    override fun intercept(chain: Interceptor.Chain): Response {
        val requestUrl = chain.request().url.toString()

        // Atomic read: if null the key is absent, so we can proceed; if non-null, fail fast.
        val previousFailure = synchronized(failedThumbnailsStatusCode) {
            failedThumbnailsStatusCode[requestUrl]
        }
        if (previousFailure != null) {
            throw IOException("Thumbnail previously failed with code: $previousFailure")
        }

        val response = chain.proceed(chain.request())
        if (!response.isSuccessful) {
            synchronized(failedThumbnailsStatusCode) {
                failedThumbnailsStatusCode.put(chain.request().url.toString(), response.code)
            }
        }

        return response
    }
}
