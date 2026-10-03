package com.swordfish.lemuroid.app.shared.telemetry

import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Collections

/**
 * Client for the DigitalStoreGames `/logErr` triage service — the same endpoint the ARMSX2 app
 * reports to, so errors from this app land in the shared admin panel and are picked up by the
 * `triagem-bugs-prod` agent. Reports are filed under `retrogamesystem/<component>`.
 *
 * **Inviolable rule: telemetry must never change app behavior.** Every path is wrapped in
 * try/catch, network failures are swallowed, timeouts are short, and the same error is not re-sent
 * within a session. Being offline is tolerated — the report is simply lost.
 *
 * Kill-switch: SharedPreferences `telemetry_prefs` / `telemetry_error_reporting` (bool, default
 * true); optional endpoint override in `telemetry_endpoint`.
 */
object TelemetryReporter {
    private const val PROJECT_BASE = "retrogamesystem"
    private const val DEFAULT_ENDPOINT = "https://digitalstoregames.pythonanywhere.com/logErr"

    const val PREFS_NAME = "telemetry_prefs"
    private const val PREF_ENABLED = "telemetry_error_reporting"
    private const val PREF_ENDPOINT = "telemetry_endpoint"

    // Client-imposed limits (mirror the reference contract in ARMSX2's TelemetryReporter).
    private const val MAX_MESSAGE = 4000
    private const val MAX_LOG_BYTES = 256 * 1024
    private const val MAX_LOGS = 20
    private const val TIMEOUT_MS = 5000

    /** Hard cap on how long a crash path may wait for its report. See [report]. */
    private const val TERMINAL_JOIN_MS = 2500L

    @Volatile private var appContext: Context? = null

    @Volatile private var enabled = true

    @Volatile private var endpoint = DEFAULT_ENDPOINT

    /** hash(component + "|" + message) already sent this session. */
    private val seen = Collections.synchronizedSet(HashSet<Int>())

    /** POSTs em voo, terminais ou nao — ver [awaitPending]. */
    private val workers = TelemetryWorkers()

    /** Reads the kill-switch/endpoint config. Safe to call very early in onCreate. Never throws. */
    fun init(context: Context) {
        try {
            appContext = context.applicationContext
            val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            enabled = prefs.getBoolean(PREF_ENABLED, true)
            endpoint = prefs.getString(PREF_ENDPOINT, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_ENDPOINT
        } catch (ignored: Throwable) {
            // default = on, default endpoint
        }
    }

    fun isEnabled(): Boolean = enabled

    /**
     * Reports a Java throwable. Sent synchronously when [terminal] because crash paths are about to
     * kill the process and a detached thread would die before finishing. Never throws.
     */
    fun reportThrowable(
        component: String,
        thread: Thread?,
        error: Throwable?,
        extraContext: String = "",
        extraLog: String? = null,
        terminal: Boolean = true,
    ) {
        try {
            val top = error?.stackTrace?.firstOrNull()
            val file = top?.fileName ?: top?.className ?: "unknown"
            val method = top?.let { "${it.className}.${it.methodName}" }
            val context =
                buildString {
                    append("thread=").append(thread?.name ?: "?")
                    append("; process=").append(TelemetryContext.processName(appContext))
                    append("; ").append(deviceContext())
                    if (extraContext.isNotEmpty()) append("; ").append(extraContext)
                }
            val logs = listOfNotNull(stackToString(error), extraLog).toTypedArray()
            report(component, file, method, describe(error), context, logs, terminal)
        } catch (ignored: Throwable) {
            // telemetry never affects app behavior
        }
    }

    /**
     * General report. [component] becomes `retrogamesystem/<component>`. When [terminal] is false
     * the POST runs on a detached daemon thread so the caller is never blocked — whoever kills the
     * process right after must call [awaitPending] first. Never throws.
     */
    fun report(
        component: String,
        file: String?,
        method: String?,
        message: String?,
        contextPath: String?,
        logs: Array<String>?,
        terminal: Boolean,
    ) {
        try {
            if (!enabled) return
            val comp = component.ifBlank { "android" }
            val msg = message ?: ""
            if (!seen.add("$comp|$msg".hashCode())) return // dedup within session

            val body =
                JSONObject().apply {
                    put("project", "$PROJECT_BASE/$comp")
                    put("file", file?.takeIf { it.isNotBlank() } ?: "unknown")
                    put("method", method?.takeIf { it.isNotBlank() } ?: comp)
                    put("message", cap(msg, MAX_MESSAGE))
                    put("user_agent", "RetroGameSystem/$comp ${safeVersion()}")
                    put("platform", "Android ${Build.VERSION.RELEASE} (sdk ${Build.VERSION.SDK_INT})")
                    put("screen", "")
                    put("page_url", contextPath ?: "")
                    put(
                        "logs",
                        JSONArray().apply {
                            logs.orEmpty()
                                .asSequence()
                                .filter { it.isNotBlank() }
                                .take(MAX_LOGS)
                                .forEach { put(capTail(it, MAX_LOG_BYTES)) }
                        },
                    )
                }

            val worker = workers.start("Lemuroid-Telemetry") { send(body) }
            if (terminal) {
                // Crash paths can't just detach — the process is about to die and would take the
                // thread with it. But they must not hang either: BaseGameActivity shows its error
                // screen right after this, so a blocking POST + GET fallback would freeze the app
                // for up to 10s first. Give the report a bounded window and move on; if it doesn't
                // make it, the report is lost, which is the telemetry contract.
                try {
                    worker.join(TERMINAL_JOIN_MS)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
        } catch (ignored: Throwable) {
            // telemetry never affects app behavior
        }
    }

    /**
     * Espera os reports ainda em envio, por no maximo [timeoutMs] no total. Para quem vai matar o
     * processo logo em seguida (`BaseGameActivity.finishAndExitProcess`): um report nao-terminal
     * roda num thread daemon e morreria junto. Sem report pendente volta na hora. Never throws.
     */
    fun awaitPending(timeoutMs: Long) {
        workers.awaitAll(timeoutMs)
    }

    private fun send(body: JSONObject) {
        if (!postJson(body)) getFallback(body) // POST failed (proxy/old server); retry via GET
    }

    private fun postJson(body: JSONObject): Boolean {
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(endpoint).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.useCaches = false
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.outputStream.use { os: OutputStream ->
                os.write(body.toString().toByteArray(StandardCharsets.UTF_8))
            }
            conn.responseCode in 200..299
        } catch (e: Throwable) {
            false
        } finally {
            try { conn?.disconnect() } catch (ignored: Throwable) {}
        }
    }

    private fun getFallback(body: JSONObject) {
        var conn: HttpURLConnection? = null
        try {
            val keys = listOf("project", "file", "method", "message", "user_agent", "platform", "screen", "page_url")
            val qs =
                keys.joinToString("&", prefix = "$endpoint?") { k ->
                    "$k=${URLEncoder.encode(body.optString(k, ""), "UTF-8")}"
                }
            conn = URL(qs).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.responseCode
        } catch (ignored: Throwable) {
            // offline tolerated
        } finally {
            try { conn?.disconnect() } catch (ignored: Throwable) {}
        }
    }

    fun deviceContext(): String =
        "device=${Build.MANUFACTURER} ${Build.MODEL}; abi=${Build.SUPPORTED_ABIS.firstOrNull()}; app=${safeVersion()}"

    private fun describe(e: Throwable?): String {
        if (e == null) return "unknown error"
        val msg = e.message
        return if (msg.isNullOrBlank()) e.javaClass.name else "${e.javaClass.name}: $msg"
    }

    private fun stackToString(e: Throwable?): String {
        if (e == null) return ""
        val sw = StringWriter()
        PrintWriter(sw).use { e.printStackTrace(it) }
        return sw.toString()
    }

    private fun cap(s: String, max: Int): String = if (s.length <= max) s else s.substring(0, max)

    /** Keep the TAIL — the end of a trace is what matters for diagnosis. */
    private fun capTail(s: String, maxBytes: Int): String {
        val b = s.toByteArray(StandardCharsets.UTF_8)
        if (b.size <= maxBytes) return s
        val marker = "...[truncated ${b.size - maxBytes} bytes]...\n"
        return marker + String(b, b.size - maxBytes, maxBytes, StandardCharsets.UTF_8)
    }

    private fun safeVersion(): String =
        try {
            val ctx = appContext!!
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "unknown"
        } catch (e: Throwable) {
            "unknown"
        }
}
