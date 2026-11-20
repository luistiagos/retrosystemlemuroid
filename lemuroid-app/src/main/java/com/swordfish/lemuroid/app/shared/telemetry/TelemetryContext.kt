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
    private const val PREF_FGS_PID = "telemetry_fgs_pid"
    private const val PREF_FGS_TAGS = "telemetry_fgs_tags"
    private const val FGS_SEPARATOR = "+"

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

    /**
     * O breadcrumb, se ele pode ser o da sessao que morreu em [exitTimestamp]; senao vazio.
     *
     * O slot e um so, e quem o le e o scan de `ApplicationExitInfo` no proximo cold start do
     * processo principal — que pode vir horas e varias sessoes depois do exit, e cobre todos os
     * exits acumulados de uma vez. Sem este filtro, cada relatorio herdava o jogo que estivesse no
     * slot na hora do scan: o de uma sessao posterior, ou nenhum (uma saida limpa posterior apaga
     * o slot) — e "sem breadcrumb" era lido como "morreu antes de gravar o breadcrumb".
     */
    fun gameSessionForExit(
        context: Context,
        processName: String?,
        exitTimestamp: Long,
    ): String =
        if (isSessionOfExit(processName, exitTimestamp, lastGameSessionStartedAt(context))) {
            lastGameSession(context)
        } else {
            ""
        }

    /**
     * So um exit do `:game` tem sessao de jogo, e ela tem que ter comecado antes dele. Mesmo
     * criterio do `CoreCrashFallback`.
     */
    internal fun isSessionOfExit(
        processName: String?,
        exitTimestamp: Long,
        sessionStartedAt: Long,
    ): Boolean = processName.orEmpty().endsWith(":game") && sessionStartedAt in 1..exitTimestamp

    /**
     * Marca que este processo pos um trabalho em primeiro plano ([tag]: `download`, `library`...).
     *
     * O `lowmemory` do processo PRINCIPAL chega com `importance=125` (`FOREGROUND_SERVICE`) e sem
     * jogo, e o `ApplicationExitInfo` nao diz qual servico o segurava: o download de ROM ou um
     * Worker do WorkManager em foreground. Este marcador, lido por [foregroundWorkForExit] no scan
     * do proximo cold start, responde isso ([[2026-09-28-lowmemory-gameplay-ppsspp-foreground-service]]).
     *
     * O conjunto vale so para o pid que o gravou: se o pid gravado for outro (o processo anterior
     * morreu sem desmarcar — que e justamente o caso do low-memory), o conjunto recomeca so com a
     * tag nova. Assim um marcador preso nunca contamina o processo seguinte, sem limpeza no boot.
     */
    fun markForegroundWork(
        context: Context,
        tag: String,
    ) = updateForegroundWork(context, tag, active = true)

    /** Desfaz [markForegroundWork] ao fim do trabalho. */
    fun clearForegroundWork(
        context: Context,
        tag: String,
    ) = updateForegroundWork(context, tag, active = false)

    private fun updateForegroundWork(
        context: Context,
        tag: String,
        active: Boolean,
    ) {
        try {
            // Servico e Workers marcam de threads diferentes: o read-modify-write e serializado.
            synchronized(this) {
                val prefs = prefs(context)
                val pid = Process.myPid()
                val tags =
                    updatedForegroundTags(
                        storedPid = prefs.getInt(PREF_FGS_PID, 0),
                        storedTags = parseForegroundTags(prefs.getString(PREF_FGS_TAGS, "")),
                        currentPid = pid,
                        tag = tag,
                        active = active,
                    )
                prefs.edit()
                    .putInt(PREF_FGS_PID, pid)
                    .putString(PREF_FGS_TAGS, tags.sorted().joinToString(FGS_SEPARATOR))
                    .apply()
            }
        } catch (ignored: Throwable) {
            // telemetry never affects app behavior
        }
    }

    /**
     * `fgs=<tags>` se o exit e do processo principal que gravou o marcador e ele tinha trabalho em
     * primeiro plano; senao vazio. Nunca lanca.
     */
    fun foregroundWorkForExit(
        context: Context,
        processName: String?,
        exitPid: Int,
    ): String =
        try {
            val prefs = prefs(context)
            foregroundWorkOf(
                processName = processName,
                exitPid = exitPid,
                storedPid = prefs.getInt(PREF_FGS_PID, 0),
                storedTags = parseForegroundTags(prefs.getString(PREF_FGS_TAGS, "")),
            )
        } catch (e: Throwable) {
            ""
        }

    /** Decisao de [foregroundWorkForExit], sem `Context`. */
    internal fun foregroundWorkOf(
        processName: String?,
        exitPid: Int,
        storedPid: Int,
        storedTags: Set<String>,
    ): String {
        val isMainProcess = !processName.isNullOrEmpty() && !processName.contains(':')
        if (!isMainProcess || storedPid <= 0 || exitPid != storedPid || storedTags.isEmpty()) return ""
        return "fgs=" + storedTags.sorted().joinToString(FGS_SEPARATOR)
    }

    /** Conjunto apos marcar/desmarcar [tag] no processo [currentPid]; pid novo recomeca do zero. */
    internal fun updatedForegroundTags(
        storedPid: Int,
        storedTags: Set<String>,
        currentPid: Int,
        tag: String,
        active: Boolean,
    ): Set<String> {
        val base = if (storedPid == currentPid) storedTags else emptySet()
        return if (active) base + tag else base - tag
    }

    internal fun parseForegroundTags(raw: String?): Set<String> =
        raw.orEmpty().split(FGS_SEPARATOR).filter { it.isNotBlank() }.toSet()

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
