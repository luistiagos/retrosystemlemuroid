package com.swordfish.lemuroid.app.shared.telemetry

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.swordfish.lemuroid.R
import com.swordfish.lemuroid.common.displayToast
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.util.regex.Pattern

/**
 * Installs error telemetry and recovers the crashes that a `UncaughtExceptionHandler` structurally
 * cannot see.
 *
 * The failures that actually take this app down — SIGSEGV inside a libretro core, ANR while the
 * main thread waits on the GL thread, kill by the low-memory killer — never unwind through Java, so
 * no handler ever fires. The only supported way to observe them is
 * [ActivityManager.getHistoricalProcessExitReasons] (API 30+), read on the **next** launch. That is
 * also the only mechanism that covers the `:game` process, where the emulator (and its crashes)
 * live: the API is scoped to the package, not the process, so a scan from the main process sees
 * `:game` exits too.
 *
 * Not every such exit is a defect, and a scan that reports them all drowns the panel in noise:
 * see [isInteresting] for what is filtered out and why.
 *
 * Everything here is best-effort and must never change app behavior.
 */
object CrashTelemetry {
    private const val PREF_LAST_EXIT_TS = "telemetry_last_exit_ts"
    private const val MAX_TRACE_BYTES = 512 * 1024

    /**
     * First backtrace frame of a tombstone, e.g.
     * `  #00 pc 000abcde  /data/app/.../yabasanshiro_libretro_android.so (SomeSym+12)`.
     * The symbol group is anchored to end at `+<offset>)` so C++ names containing their own
     * parentheses are not truncated at the first `)`.
     */
    private val TOMBSTONE_FRAME: Pattern =
        Pattern.compile("#\\d+\\s+pc\\s+\\w+\\s+(\\S+)(?:\\s+\\((.+?\\+[\\dxa-f]+)\\))?")

    @Volatile private var installed = false

    /** Tag of the current process — `main` or `game` — resolved right after the handler is live. */
    @Volatile private var component = "main"

    /**
     * Determines whether a throwable indicates a storage environment failure rather than an app logic defect:
     * - Disk or database is full (SQLiteFullException, SQLITE_FULL, ENOSPC, No space left)
     * - Storage volume / adoptable storage is missing (SQLiteCantOpenDatabaseException on missing directory)
     * - Disk I/O failure (SQLiteDiskIOException)
     * - WorkManager bad filesystem state (ForceStopRunnable IllegalStateException)
     */
    fun isStorageEnvironmentFailure(throwable: Throwable?): Boolean {
        var t: Throwable? = throwable
        while (t != null) {
            val className = t.javaClass.name
            if (className.contains("SQLiteFullException") ||
                className.contains("SQLiteCantOpenDatabaseException") ||
                className.contains("SQLiteDiskIOException")
            ) {
                return true
            }
            val msg = t.message.orEmpty()
            if (msg.contains("database or disk is full", ignoreCase = true) ||
                msg.contains("SQLITE_FULL", ignoreCase = true) ||
                msg.contains("SQLiteCantOpenDatabaseException", ignoreCase = true) ||
                msg.contains("Cannot open database", ignoreCase = true) ||
                msg.contains("The file system on the device is in a bad state", ignoreCase = true) ||
                msg.contains("WorkManager cannot access the app's internal data store", ignoreCase = true) ||
                msg.contains("ENOSPC", ignoreCase = true) ||
                msg.contains("No space left on device", ignoreCase = true)
            ) {
                return true
            }
            for (suppressed in t.suppressed) {
                if (isStorageEnvironmentFailure(suppressed)) return true
            }
            t = t.cause
        }
        return false
    }

    /**
     * Installs the uncaught-exception handler for the current process, chaining to whatever handler
     * was already there so existing behavior (crash screen, process teardown) is preserved.
     *
     * [componentResolver] tags the report — `main` or `game` — so the panel shows which process
     * died. It is a lambda, and it runs only **after** the handler is live: naming the process
     * touches `ActivityManager`, exactly the kind of call that fails on the old TV-box ROMs this
     * app supports, and a failure while resolving it would leave the startup crash it caused
     * invisible to telemetry.
     */
    @Synchronized
    fun installUncaughtHandler(context: Context, componentResolver: () -> String) {
        if (installed) return
        try {
            TelemetryReporter.init(context)
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, error ->
                val isStorageFailure = isStorageEnvironmentFailure(error)
                val isMainThread = runCatching { thread == Looper.getMainLooper().thread }.getOrDefault(false)

                if (isStorageFailure && !isMainThread) {
                    // Non-fatal background storage exception (e.g. Room transaction failure when disk is full,
                    // or adoptable storage disappeared while app was running).
                    // Log locally, show friendly toast on UI thread, and avoid killing the process.
                    Timber.e(error, "Non-fatal background storage error intercepted on thread ${thread.name}")
                    try {
                        Handler(Looper.getMainLooper()).post {
                            context.displayToast(R.string.home_download_roms_out_of_space)
                        }
                    } catch (ignored: Throwable) {
                    }
                    return@setDefaultUncaughtExceptionHandler
                }

                if (!isStorageFailure) {
                    try {
                        TelemetryReporter.reportThrowable(
                            component = component,
                            thread = thread,
                            error = error,
                            extraContext = TelemetryContext.lastGameSession(context),
                            terminal = true,
                        )
                    } catch (ignored: Throwable) {
                    }
                } else {
                    Timber.w(error, "Storage environment failure occurred on main thread; omitting from crash telemetry")
                }
                previous?.uncaughtException(thread, error)
            }
            installed = true
        } catch (ignored: Throwable) {
            // telemetry never affects app behavior
        }
        component = runCatching { componentResolver() }.getOrDefault(component)
    }

    /** Scans the previous session's abnormal exits on a daemon thread. Call once, from the main process. */
    fun reportPastExitsAsync(context: Context) {
        try {
            Thread({ reportPastExits(context.applicationContext) }, "Lemuroid-ExitScan")
                .apply { isDaemon = true }
                .start()
        } catch (ignored: Throwable) {
        }
    }

    private fun reportPastExits(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return
            val exits = am.getHistoricalProcessExitReasons(context.packageName, 0, 0)
            if (exits.isNullOrEmpty()) return

            val prefs = context.getSharedPreferences(TelemetryReporter.PREFS_NAME, Context.MODE_PRIVATE)
            val watermark = prefs.getLong(PREF_LAST_EXIT_TS, 0L)
            var maxTs = watermark

            exits.forEach { info ->
                val ts = info.timestamp
                if (ts > maxTs) maxTs = ts
                if (ts <= watermark) return@forEach // already processed in a prior session
                if (!isInteresting(info)) return@forEach
                reportOneExit(context, info)
            }

            // Advance past everything seen so old exits are never reprocessed.
            prefs.edit().putLong(PREF_LAST_EXIT_TS, maxTs).apply()
        } catch (ignored: Throwable) {
        }
    }

    /**
     * Which exits are worth a report. Two reasons are deliberately absent:
     *
     * - **`REASON_CRASH`**: a Java crash unwinds through the handler installed by
     *   [installUncaughtHandler] in *both* processes, which reports it **with its stack** at the
     *   moment it happens. Finding the same event here on the next launch reported it a second
     *   time, and `ApplicationExitInfo` keeps no stack for a Java crash — the echo arrived as
     *   `description == "crash"`, with the literal word `crash` as its only log. [TelemetryReporter]
     *   dedups against an in-memory set, so it cannot see across sessions and never caught it.
     *   Dropping it loses nothing: the one case the live handler misses is a crash killed before
     *   its report goes out, and the echo has no stack to add there either.
     * - **`REASON_LOW_MEMORY` on a background process**: see [isLowMemoryWorthReporting].
     */
    private fun isInteresting(info: ApplicationExitInfo): Boolean =
        when (info.reason) {
            ApplicationExitInfo.REASON_CRASH_NATIVE, ApplicationExitInfo.REASON_ANR -> true
            ApplicationExitInfo.REASON_LOW_MEMORY -> isLowMemoryWorthReporting(info)
            else -> false
        }

    /**
     * The low-memory killer reclaiming a **cached** process is Android working as designed: the user
     * left the app and the system later recycled it. Reporting that amounts to reporting that the
     * app was closed — it produced 1,297 of 1,374 `lowmemory` reports (all `IMPORTANCE_CACHED`, 400)
     * and buried the handful that mean something.
     *
     * Importance counts *down*, so the cut keeps everything at or above
     * [ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE] (200) — the process died with
     * something on screen. That is `IMPORTANCE_FOREGROUND` (100), in front of the user, and
     * `IMPORTANCE_FOREGROUND_SERVICE` (125), which is `:game` with a match running: real memory
     * bugs of ours. `IMPORTANCE_PERCEPTIBLE` (230) and below are not.
     */
    private fun isLowMemoryWorthReporting(info: ApplicationExitInfo): Boolean =
        info.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE

    private fun reportOneExit(context: Context, info: ApplicationExitInfo) {
        try {
            val trace = readTrace(info)
            val component = componentFor(info.reason)

            var file = component
            var method = "reason=${reasonName(info.reason)} status=${info.status}"
            var signalLine: String? = null

            if (trace.decoded != null) {
                // Native crash: the tombstone names the culprit .so and symbol directly.
                trace.decoded.culpritLibrary?.let { file = it }
                trace.decoded.culpritSymbol?.let { method = it }
                signalLine = trace.decoded.signalSummary
            } else if (trace.text.isNotBlank()) {
                // ANR / Java exit: the trace is plain text, so fall back to the textual frame regex.
                val matcher = TOMBSTONE_FRAME.matcher(trace.text)
                if (matcher.find()) {
                    matcher.group(1)?.let { path -> file = path.substringAfterLast('/') }
                    matcher.group(2)?.takeIf { it.isNotBlank() }?.let { method = it }
                }
                signalLine = trace.text.lineSequence().firstOrNull { it.contains("signal ") }?.trim()
            }

            val message =
                buildString {
                    append(reasonName(info.reason))
                    if (!signalLine.isNullOrBlank()) {
                        append(" — ").append(signalLine)
                    } else if (!info.description.isNullOrBlank()) {
                        append(": ").append(info.description)
                    }
                }

            val reportContext =
                buildString {
                    append("process=").append(info.processName)
                    append("; pid=").append(info.pid)
                    append("; importance=").append(info.importance)
                    append("; when=").append(TelemetryContext.formatTimestamp(info.timestamp))
                    append("; ").append(TelemetryReporter.deviceContext())
                    TelemetryContext.lastGameSession(context).takeIf { it.isNotEmpty() }
                        ?.let { append("; ").append(it) }
                }

            val logs =
                if (trace.text.isNotBlank()) arrayOf(trace.text) else arrayOf(info.description ?: "no trace available")

            // The process that died is already gone, so nothing is terminal here — send async so
            // app startup is never blocked on the network.
            TelemetryReporter.report(component, file, method, message, reportContext, logs, terminal = false)
        } catch (ignored: Throwable) {
        }
    }

    private fun componentFor(reason: Int): String =
        when (reason) {
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "native"
            ApplicationExitInfo.REASON_ANR -> "anr"
            ApplicationExitInfo.REASON_LOW_MEMORY -> "lowmemory"
            else -> "crash"
        }

    private fun reasonName(reason: Int): String =
        when (reason) {
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "Native crash"
            ApplicationExitInfo.REASON_CRASH -> "Java crash"
            ApplicationExitInfo.REASON_ANR -> "ANR"
            ApplicationExitInfo.REASON_LOW_MEMORY -> "Killed by low memory"
            else -> "Exit reason $reason"
        }

    /** A trace ready to report: always text, plus the decoded tombstone when there was one. */
    private class Trace(val text: String, val decoded: TombstoneParser.Result?)

    /**
     * Reads the exit trace. ANR traces are plain text; native-crash traces are a binary `Tombstone`
     * protobuf on Android 12+, which is decoded here. Raw bytes are never returned — an
     * undecodable blob becomes a short summary, because sending 300 KB of binary as a "log" is what
     * made the sibling app's native reports useless.
     */
    private fun readTrace(info: ApplicationExitInfo): Trace =
        try {
            val bytes =
                info.traceInputStream?.use { input ->
                    val out = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    var total = 0
                    while (total < MAX_TRACE_BYTES) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        out.write(buffer, 0, read)
                        total += read
                    }
                    out.toByteArray()
                }
            when {
                bytes == null || bytes.isEmpty() -> Trace("", null)
                looksLikeText(bytes) -> Trace(String(bytes, Charsets.UTF_8), null)
                else ->
                    TombstoneParser.parse(bytes)
                        ?.let { Trace(it.text, it) }
                        ?: Trace(
                            "Binary tombstone (${bytes.size} bytes) could not be decoded; " +
                                "raw bytes intentionally omitted.",
                            null,
                        )
            }
        } catch (e: Throwable) {
            Trace("", null)
        }

    /** Heuristic over the head of the buffer: ANR dumps are text, tombstones are protobuf. */
    private fun looksLikeText(bytes: ByteArray): Boolean {
        val sample = minOf(bytes.size, 512)
        if (sample == 0) return false
        var printable = 0
        for (i in 0 until sample) {
            val b = bytes[i].toInt() and 0xFF
            if (b == 0x09 || b == 0x0A || b == 0x0D || (b in 0x20..0x7E)) printable++
        }
        return printable * 100 / sample >= 90
    }
}
