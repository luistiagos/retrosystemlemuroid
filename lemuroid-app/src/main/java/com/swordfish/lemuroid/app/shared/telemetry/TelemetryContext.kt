package com.swordfish.lemuroid.app.shared.telemetry

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Process
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Breadcrumb of what the app was doing, persisted to disk so it survives the process death it is
 * meant to explain.
 *
 * A native crash or ANR in the `:game` process is only recovered on the **next** launch, via
 * [android.app.ApplicationExitInfo]. By then the process that knew which game and which libretro
 * core were running is long gone, and the tombstone alone rarely names the core. Writing the
 * session here at game start means the report can say "saturn / yabasanshiro" instead of just
 * "SIGSEGV somewhere" — which is the difference between a triageable bug and a dead end.
 */
object TelemetryContext {
    private const val PREF_SESSION = "telemetry_last_session"
    private const val PREF_SESSION_STARTED_AT = "telemetry_last_session_started_at"

    /**
     * Records the game about to run. Called from the `:game` process; read back from whichever
     * process performs the exit scan (SharedPreferences with MODE_PRIVATE is per-package, and the
     * value is written once per game launch, so cross-process staleness is not a concern here).
     */
    fun setGameSession(
        context: Context,
        systemId: String,
        coreName: String,
        gameTitle: String,
    ) {
        try {
            prefs(context).edit()
                .putString(PREF_SESSION, "system=$systemId; core=$coreName; game=$gameTitle")
                .putLong(PREF_SESSION_STARTED_AT, System.currentTimeMillis())
                .apply()
        } catch (ignored: Throwable) {
            // telemetry never affects app behavior
        }
    }

    /** Clears the breadcrumb after a clean exit, so a later unrelated crash isn't blamed on a game. */
    fun clearGameSession(context: Context) {
        try {
            prefs(context).edit().remove(PREF_SESSION).remove(PREF_SESSION_STARTED_AT).apply()
        } catch (ignored: Throwable) {
        }
    }

    /** Last recorded game session, or empty when the app died outside a game. */
    fun lastGameSession(context: Context): String =
        try {
            prefs(context).getString(PREF_SESSION, "").orEmpty()
        } catch (e: Throwable) {
            ""
        }

    /**
     * When the session reported by [lastGameSession] started, or `0` when there is none. Lets a
     * reader tell an exit that belongs to that session from an older, unrelated one still sitting
     * in [android.app.ActivityManager.getHistoricalProcessExitReasons].
     */
    fun lastGameSessionStartedAt(context: Context): Long =
        try {
            prefs(context).getLong(PREF_SESSION_STARTED_AT, 0L)
        } catch (e: Throwable) {
            0L
        }

    fun processName(context: Context?): String =
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                android.app.Application.getProcessName()
            } else {
                val pid = Process.myPid()
                val am = context?.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                am?.runningAppProcesses?.firstOrNull { it.pid == pid }?.processName ?: "?"
            }
        } catch (e: Throwable) {
            "?"
        }

    fun formatTimestamp(millis: Long): String =
        try {
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(millis))
        } catch (e: Throwable) {
            millis.toString()
        }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(TelemetryReporter.PREFS_NAME, Context.MODE_PRIVATE)
}
