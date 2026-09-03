package com.swordfish.lemuroid.app.utils.android

import android.app.ForegroundServiceStartNotAllowedException
import android.os.Build
import androidx.annotation.DoNotInline
import androidx.annotation.RequiresApi

/**
 * Checks the API 31 exception without loading its class on older Android versions.
 */
fun RuntimeException.isForegroundServiceStartNotAllowed(): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && Api31Impl.isForegroundServiceStartNotAllowed(this)

@RequiresApi(Build.VERSION_CODES.S)
private object Api31Impl {
    @DoNotInline
    fun isForegroundServiceStartNotAllowed(exception: RuntimeException): Boolean =
        exception is ForegroundServiceStartNotAllowedException
}
