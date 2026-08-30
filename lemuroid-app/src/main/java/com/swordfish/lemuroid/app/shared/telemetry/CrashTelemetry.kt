package com.swordfish.lemuroid.app.shared.telemetry

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
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

    /**
     * Installs the uncaught-exception handler for the current process, chaining to whatever handler
     * was already there so existing behavior (crash screen, process teardown) is preserved.
     *
     * [component] tags the report — `main` or `game` — so the panel shows which process died.
     */
    @Synchronized
    fun installUncaughtHandler(context: Context, component: String) {
        if (installed) return
        try {
            TelemetryReporter.init(context)
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, error ->
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
                previous?.uncaughtException(thread, error)
            }
            installed = true
        } catch (ignored: Throwable) {
            // telemetry never affects app behavior
        }
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
                if (!isInteresting(info.reason)) return@forEach
                reportOneExit(context, info)
            }

            // Advance past everything seen so old exits are never reprocessed.
            prefs.edit().putLong(PREF_LAST_EXIT_TS, maxTs).apply()
        } catch (ignored: Throwable) {
        }
    }

    private fun isInteresting(reason: Int): Boolean =
        reason == ApplicationExitInfo.REASON_CRASH_NATIVE ||
            reason == ApplicationExitInfo.REASON_CRASH ||
            reason == ApplicationExitInfo.REASON_ANR ||
            reason == ApplicationExitInfo.REASON_LOW_MEMORY

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
