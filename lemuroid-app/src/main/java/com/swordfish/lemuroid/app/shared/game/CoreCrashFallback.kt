package com.swordfish.lemuroid.app.shared.game

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import com.swordfish.lemuroid.app.shared.telemetry.TelemetryContext
import com.swordfish.lemuroid.lib.core.CoreVariablesManager
import com.swordfish.lemuroid.lib.preferences.SharedPreferencesHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber

/**
 * Explains — and where possible undoes — a game session that died inside the libretro core.
 *
 * A core that aborts itself takes the whole `:game` process with it: there is no Java exception, no
 * crash screen, nothing the user can act on. The app simply vanishes mid-game and comes back as if
 * nothing had happened. Two things are done about that on the next launch:
 *
 * 1. **A notice** ([pendingNotice]) naming the game and the core, so the disappearance stops being
 *    a mystery. Shown by the main process on its home screen — never during a game boot, where an
 *    enqueued toast is exactly what kills Android 7.1 boxes (pitfall 7 in `CLAUDE.md`).
 * 2. **A core option flipped off**, for the one case where the failing path was traced end to end:
 *    **3DS / citra** (see `documentacao/bugs/open/2026-09-02-crashes-nativos-cores-citra-dolphin.md`):
 *
 * ```
 * citra_use_hw_shaders=enabled  ->  Settings::values.use_hw_shader
 *   -> System::ApplySettings() sets VideoCore::g_hw_shader_enabled
 *   -> command_processor.cpp: accelerate_draw = g_hw_shader_enabled && ...
 *   -> RasterizerOpenGL::AccelerateDrawBatch -> ...Internal -> ShaderProgramManager::ApplyTo
 *   -> OGLProgram::Create -> LoadProgram -> ASSERT_MSG(link == GL_TRUE) -> SIGTRAP
 * ```
 *
 * With the option off, `accelerate_draw` is false and the generated PICA vertex/geometry shader —
 * the program that fails to link on the affected drivers — is never built. `Draw` still calls
 * `ApplyTo` on its trivial path, so this is a mitigation, not a proof: the fragment program is
 * still linked. It costs performance, which is why it is applied only **after** a crash and never
 * pre-emptively.
 *
 * The flip is deliberately one-shot: the flag it writes is the very same preference the game menu
 * switch writes, so the user can turn hardware shaders back on and this will not fight them.
 */
object CoreCrashFallback {
    private const val PREF_HW_SHADERS_FALLBACK_APPLIED = "core_fallback_3ds_hw_shaders_applied"

    /** Serialized [Notice] waiting to be shown. Survives the app being killed before it is read. */
    private const val PREF_PENDING_NOTICE = "core_crash_pending_notice"

    /** Timestamp of the newest native death already reported to the user, so it is told once. */
    private const val PREF_LAST_NOTIFIED_EXIT_AT = "core_crash_last_notified_exit_at"

    private const val SYSTEM_3DS = "3ds"
    private const val CITRA_HW_SHADERS = "citra_use_hw_shaders"

    /** What to tell the user about the session that died. */
    data class Notice(
        val gameTitle: String,
        val coreName: String,
        val optionDisabled: Boolean,
    )

    private val pendingNoticeState = MutableStateFlow<Notice?>(null)

    /**
     * The notice for the last game that died in native code, until [consumeNotice] is called.
     *
     * A [StateFlow] rather than a plain preference read because [applyAsync] runs off the main
     * thread and can finish after the home screen is already composed — a screen that read the
     * preference once, on entry, would simply miss it.
     */
    val pendingNotice: StateFlow<Notice?> = pendingNoticeState.asStateFlow()

    /** Marks the notice as delivered, so the same crash is never announced twice. */
    fun consumeNotice(context: Context) {
        try {
            pendingNoticeState.value = null
            SharedPreferencesHelper.getSharedPreferences(context)
                .edit()
                .remove(PREF_PENDING_NOTICE)
                .apply()
        } catch (ignored: Throwable) {
        }
    }

    /** Runs the check off the main thread. Best-effort: a failure here must never block startup. */
    fun applyAsync(context: Context) {
        try {
            Thread({ apply(context.applicationContext) }, "Lemuroid-CoreCrashFallback")
                .apply { isDaemon = true }
                .start()
        } catch (ignored: Throwable) {
        }
    }

    private fun apply(context: Context) {
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return

            val preferences = SharedPreferencesHelper.getSharedPreferences(context)

            // A notice recorded on an earlier launch that the user never got to see.
            preferences.getString(PREF_PENDING_NOTICE, null)?.let { stored ->
                pendingNoticeState.value = parseNotice(stored)
            }

            // A breadcrumb that is still here means the last game never reached a clean exit.
            val session = TelemetryContext.lastGameSession(context)
            if (session.isEmpty()) return

            val diedAt = nativeCrashDuringLastSession(context) ?: return
            if (preferences.getLong(PREF_LAST_NOTIFIED_EXIT_AT, 0L) >= diedAt) return

            val optionDisabled = applyCitraFallback(preferences, session)

            val notice =
                Notice(
                    gameTitle = fieldOf(session, "game"),
                    coreName = fieldOf(session, "core"),
                    optionDisabled = optionDisabled,
                )

            preferences.edit()
                .putString(PREF_PENDING_NOTICE, serializeNotice(notice))
                .putLong(PREF_LAST_NOTIFIED_EXIT_AT, diedAt)
                .apply()
            pendingNoticeState.value = notice

            Timber.w("Last game session died in native code: $session (option disabled: $optionDisabled)")
        } catch (ignored: Throwable) {
            // never affects app behavior beyond the option it exists to flip
        }
    }

    /** @return true when hardware shaders were turned off as a result of this crash. */
    private fun applyCitraFallback(
        preferences: SharedPreferences,
        session: String,
    ): Boolean {
        if (!session.contains("system=$SYSTEM_3DS")) return false
        if (preferences.getBoolean(PREF_HW_SHADERS_FALLBACK_APPLIED, false)) return false

        val optionKey = CoreVariablesManager.computeSharedPreferenceKey(CITRA_HW_SHADERS, SYSTEM_3DS)
        preferences.edit()
            .putBoolean(optionKey, false)
            .putBoolean(PREF_HW_SHADERS_FALLBACK_APPLIED, true)
            .apply()

        Timber.w("3DS died in native code last session; disabled $CITRA_HW_SHADERS as fallback")
        return true
    }

    /**
     * Timestamp of the `:game` process dying of a native crash at or after the start of the session
     * [TelemetryContext] recorded, or null when it did not. The timestamp comparison is what keeps
     * an older native crash from another core out of the decision.
     */
    private fun nativeCrashDuringLastSession(context: Context): Long? {
        val startedAt = TelemetryContext.lastGameSessionStartedAt(context)
        if (startedAt <= 0L) return null

        val activityManager =
            context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return null
        val exits = activityManager.getHistoricalProcessExitReasons(context.packageName, 0, 0)

        return exits.orEmpty()
            .filter { info ->
                info.reason == ApplicationExitInfo.REASON_CRASH_NATIVE &&
                    info.timestamp >= startedAt &&
                    info.processName.orEmpty().endsWith(":game")
            }
            .maxOfOrNull { it.timestamp }
    }

    /** Reads `key=value` out of the `system=…; core=…; game=…` breadcrumb [TelemetryContext] writes. */
    private fun fieldOf(
        session: String,
        key: String,
    ): String =
        session.split("; ")
            .firstOrNull { it.startsWith("$key=") }
            ?.removePrefix("$key=")
            .orEmpty()

    // A game title can contain anything, so it goes last and is never split on.
    private fun serializeNotice(notice: Notice): String =
        "${notice.optionDisabled}|${notice.coreName}|${notice.gameTitle}"

    private fun parseNotice(stored: String): Notice? {
        val parts = stored.split("|", limit = 3)
        if (parts.size < 3) return null
        return Notice(
            gameTitle = parts[2],
            coreName = parts[1],
            optionDisabled = parts[0].toBoolean(),
        )
    }
}
