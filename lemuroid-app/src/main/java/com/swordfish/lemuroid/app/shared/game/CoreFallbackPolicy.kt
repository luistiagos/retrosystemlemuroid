package com.swordfish.lemuroid.app.shared.game

import com.swordfish.lemuroid.lib.library.GameSystem
import com.swordfish.lemuroid.lib.library.SystemCoreConfig

/**
 * Pure decision logic for multi-core fallback selection and non-crashing hang detection.
 */
object CoreFallbackPolicy {
    const val MIN_NO_FRAME_DURATION_MS = 5_000L
    const val MIN_SHORT_SESSION_MS = 8_000L
    const val MAX_SHORT_SESSION_MS = 120_000L

    enum class FallbackPromptReason {
        CORE_STALLED_ON_EXIT,
        NO_FRAME_RENDERED,
        SHORT_SESSION,
    }

    fun findNextFallbackCore(
        system: GameSystem,
        triedCores: Collection<String>,
        allowDeprecated: Boolean,
    ): SystemCoreConfig? =
        system.systemCoreConfigs.firstOrNull { config ->
            config.coreID.coreName !in triedCores && (allowDeprecated || !config.coreID.isDeprecated)
        }

    fun evaluateExitPrompt(
        savesFailed: Boolean,
        firstFrameRendered: Boolean,
        sessionDurationMs: Long,
        isPrimaryCore: Boolean,
        promptAlreadyDismissed: Boolean,
    ): FallbackPromptReason? =
        when {
            savesFailed -> FallbackPromptReason.CORE_STALLED_ON_EXIT
            !firstFrameRendered && sessionDurationMs >= MIN_NO_FRAME_DURATION_MS ->
                FallbackPromptReason.NO_FRAME_RENDERED
            firstFrameRendered &&
                isPrimaryCore &&
                !promptAlreadyDismissed &&
                sessionDurationMs in MIN_SHORT_SESSION_MS..MAX_SHORT_SESSION_MS ->
                FallbackPromptReason.SHORT_SESSION
            else -> null
        }

    fun shouldPersistFallbackCore(
        triedCores: List<String>,
        coreName: String?,
        firstFrameRendered: Boolean,
        savesFailed: Boolean,
    ): Boolean = triedCores.size > 1 && !coreName.isNullOrEmpty() && firstFrameRendered && !savesFailed
}
