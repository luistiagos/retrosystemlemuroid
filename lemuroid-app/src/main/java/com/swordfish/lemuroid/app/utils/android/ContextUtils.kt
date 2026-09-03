package com.swordfish.lemuroid.app.utils.android

import android.app.ActivityManager
import android.app.Application
import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.Process
import java.io.FileInputStream

/**
 * True when running in the app's main (UI) process, false in `:game`.
 *
 * When the process name cannot be resolved at all the answer is `true`: below API 28 the `:game`
 * process is the minority case, and assuming "main" keeps telemetry, the app initializer and the
 * cache trimming alive instead of silently disabling them.
 */
fun Context.isMainProcess(): Boolean {
    val processName = retrieveProcessName(this) ?: return true
    return processName == this.packageName
}

private fun retrieveProcessName(context: Context): String? {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        // Plain try/catch rather than runCatching: keeps the guarded call lexically inside the
        // SDK_INT check, where lint's NewApi analysis is unambiguous.
        return try {
            Application.getProcessName()
        } catch (e: Throwable) {
            null
        }
    }

    // getRunningAppProcesses() is declared @NonNull in the framework, so Kotlin sees a platform
    // type and injects a null check as soon as the result is chained with `.`. Several old TV-box
    // ROMs return null there anyway (the ActivityManagerService answers null instead of an empty
    // list), and the app died with "getRunningAppProcesses(...) must not be null" — on the
    // startup path, in a process where no crash handler was installed yet.
    val fromActivityManager =
        runCatching {
            val currentPID = Process.myPid()
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            manager?.runningAppProcesses
                ?.firstOrNull { it.pid == currentPID }
                ?.processName
        }.getOrNull()

    return fromActivityManager ?: cmdlineProcessName(context.packageName)
}

/**
 * Process name read from `/proc/self/cmdline`, which Android sets at fork time. No binder IPC and
 * no permission, so it still answers on the ROMs where [ActivityManager.getRunningAppProcesses]
 * does not.
 *
 * The value is only accepted when it looks like one of our own processes; anything else is
 * reported as unresolved so the caller falls back to its default instead of trusting a truncated
 * or mangled cmdline — a wrong `false` here would skip [Application] initialization entirely.
 */
private fun cmdlineProcessName(packageName: String): String? =
    runCatching {
        // /proc reports a size of 0 for this file, so read the stream to EOF rather than sizing
        // a buffer from File.length().
        FileInputStream("/proc/self/cmdline").use { it.readBytes() }
            .toString(Charsets.UTF_8)
            .substringBefore('\u0000')
            .trim()
    }.getOrNull()
        ?.takeIf { it == packageName || it.startsWith("$packageName:") }

/** Returns true when the device is a TV or set-top box (queried via UiModeManager). */
fun Context.isTvDevice(): Boolean {
    val uiModeManager = getSystemService(Context.UI_MODE_SERVICE) as UiModeManager
    return uiModeManager.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
}

fun Context.getGLSLVersion(): Int {
    val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    return if (activityManager.deviceConfigurationInfo.reqGlEsVersion >= 0x30000) {
        3
    } else {
        2
    }
}
