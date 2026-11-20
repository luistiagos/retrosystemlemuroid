package com.swordfish.lemuroid.app.shared.main

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.swordfish.lemuroid.R
import com.swordfish.lemuroid.app.shared.game.BaseGameActivity
import com.swordfish.lemuroid.app.shared.game.CoreCrashFallback
import com.swordfish.lemuroid.app.shared.game.CoreFallbackPolicy
import com.swordfish.lemuroid.app.shared.game.GameProcessSession
import com.swordfish.lemuroid.app.shared.gamecrash.GameCrashActivity
import com.swordfish.lemuroid.app.shared.roms.RomOnDemandManager
import com.swordfish.lemuroid.app.shared.savesync.SaveSyncWork
import com.swordfish.lemuroid.app.shared.storage.cache.CacheCleanerWork
import com.swordfish.lemuroid.app.shared.telemetry.TelemetryContext
import com.swordfish.lemuroid.common.displayToast
import com.swordfish.lemuroid.ext.feature.review.ReviewManager
import com.swordfish.lemuroid.lib.bios.BiosManager
import com.swordfish.lemuroid.lib.core.CoresSelection
import com.swordfish.lemuroid.lib.library.GameSystem
import com.swordfish.lemuroid.lib.library.SystemCoreConfig
import com.swordfish.lemuroid.lib.library.db.RetrogradeDatabase
import com.swordfish.lemuroid.lib.library.db.entity.Game
import com.swordfish.lemuroid.lib.library.findByName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.util.zip.ZipFile

class GameLaunchTaskHandler(
    private val reviewManager: ReviewManager,
    private val retrogradeDb: RetrogradeDatabase,
    private val romOnDemandManager: RomOnDemandManager,
    private val biosManager: BiosManager,
    private val coresSelection: CoresSelection,
) {
    data class ActiveLaunch(
        val game: Game,
        val coreConfig: SystemCoreConfig,
        val triedCores: List<String>,
        val leanback: Boolean,
        val startedAtMs: Long,
    )

    @Volatile
    private var activeLaunch: ActiveLaunch? = null

    fun recordActiveLaunch(
        game: Game,
        coreConfig: SystemCoreConfig,
        triedCores: List<String>,
        leanback: Boolean,
    ) {
        activeLaunch =
            ActiveLaunch(
                game = game,
                coreConfig = coreConfig,
                triedCores = triedCores,
                leanback = leanback,
                startedAtMs = System.currentTimeMillis(),
            )
    }

    fun handleGameStart(context: Context) {
        cancelBackgroundWork(context)
    }

    suspend fun prepareGameForLaunch(game: Game): Game = romOnDemandManager.prepareGameForLaunch(game)

    /**
     * Handles the result of a `:game` activity.
     *
     * @return true when a follow-up flow (relaunch or interactive prompt dialog) keeps the caller
     * active; false when post-game handling is complete.
     */
    suspend fun handleGameFinish(
        enableRatingFlow: Boolean,
        activity: Activity,
        resultCode: Int,
        data: Intent?,
        onPromptDismissed: (() -> Unit)? = null,
    ): Boolean {
        val lastLaunch = activeLaunch
        activeLaunch = null
        rescheduleBackgroundWork(activity.applicationContext)
        return when (resultCode) {
            Activity.RESULT_OK ->
                handleSuccessfulGameFinish(activity, enableRatingFlow, data, onPromptDismissed)
            BaseGameActivity.RESULT_SWITCH_CORE ->
                handleSwitchCoreFinish(activity, data)
            Activity.RESULT_CANCELED ->
                handleCanceledGameFinish(activity, data, lastLaunch)
            BaseGameActivity.RESULT_ERROR -> {
                val game = data?.extras?.getSerializable(BaseGameActivity.PLAY_GAME_RESULT_GAME) as? Game
                val errorMessage =
                    data?.getStringExtra(BaseGameActivity.PLAY_GAME_RESULT_ERROR)
                        ?: activity.getString(R.string.lemuroid_crash_disclamer)
                val isRomLoadFailure =
                    data?.getBooleanExtra(
                        BaseGameActivity.PLAY_GAME_RESULT_IS_ROM_LOAD_FAILURE,
                        false,
                    ) ?: false
                val triedCores =
                    data?.getStringArrayListExtra(BaseGameActivity.PLAY_GAME_RESULT_TRIED_CORES) ?: arrayListOf()
                val leanback = data?.getBooleanExtra(BaseGameActivity.PLAY_GAME_RESULT_LEANBACK, false) ?: false
                handleErrorWithCorruptionCheck(activity, game, errorMessage, isRomLoadFailure, triedCores, leanback)
            }
            BaseGameActivity.RESULT_UNEXPECTED_ERROR -> {
                val game = data?.extras?.getSerializable(BaseGameActivity.PLAY_GAME_RESULT_GAME) as? Game
                val triedCores =
                    data?.getStringArrayListExtra(BaseGameActivity.PLAY_GAME_RESULT_TRIED_CORES) ?: arrayListOf()
                val leanback = data?.getBooleanExtra(BaseGameActivity.PLAY_GAME_RESULT_LEANBACK, false) ?: false
                val errorDetail = data?.getStringExtra(BaseGameActivity.PLAY_GAME_RESULT_ERROR)
                // Default true = comportamento antigo, para o caso de o extra não vir.
                val isEmulatorFailure =
                    data?.getBooleanExtra(BaseGameActivity.PLAY_GAME_RESULT_IS_EMULATOR_FAILURE, true) ?: true
                val isCoreStall =
                    data?.getBooleanExtra(BaseGameActivity.PLAY_GAME_RESULT_IS_CORE_STALL, false) ?: false
                if (isCoreStall) {
                    Timber.w("Core stalled the GL thread: $errorDetail")
                    if (tryFallbackCore(
                            activity = activity,
                            game = game,
                            triedCores = triedCores,
                            leanback = leanback,
                            allowDeprecated = false,
                            stallFallback = true,
                        )
                    ) {
                        true
                    } else {
                        handleUnsuccessfulGameFinish(
                            activity,
                            activity.getString(R.string.lemuroid_core_stalled_disclamer),
                            errorDetail,
                        )
                        false
                    }
                } else if (!isEmulatorFailure) {
                    // Bug de app, não do núcleo: trocar de core só repete o mesmo crash com outro
                    // núcleo (e faz o usuário esperar 2-3 vezes), e o disclaimer de core mandaria
                    // limpar cache / resetar de fábrica por um problema que não é dele.
                    Timber.w("Non-emulator failure in game process: $errorDetail")
                    handleUnsuccessfulGameFinish(
                        activity,
                        activity.getString(R.string.lemuroid_app_error_disclamer),
                        errorDetail,
                    )
                    false
                } else if (!tryFallbackCore(activity, game, triedCores, leanback, allowDeprecated = true)) {
                    handleUnsuccessfulGameFinish(
                        activity,
                        activity.getString(R.string.lemuroid_crash_disclamer),
                        errorDetail,
                    )
                    false
                } else {
                    true
                }
            }
            BaseGameActivity.RESULT_RESTART_IN_FRESH_PROCESS -> relaunchInFreshProcess(activity, data)
            else -> false
        }
    }

    private suspend fun handleSwitchCoreFinish(
        activity: Activity,
        data: Intent?,
    ): Boolean {
        if (data?.getBooleanExtra(BaseGameActivity.PLAY_GAME_RESULT_SAVES_FAILED, false) == true) {
            Timber.w("Game exited without persisting its saves before switching core")
            activity.displayToast(R.string.game_toast_exit_save_failed)
        }
        val game = data?.extras?.getSerializable(BaseGameActivity.PLAY_GAME_RESULT_GAME) as? Game ?: return false
        val targetCoreName = data.getStringExtra(BaseGameActivity.PLAY_GAME_RESULT_TARGET_CORE_ID) ?: return false
        val leanback = data.getBooleanExtra(BaseGameActivity.PLAY_GAME_RESULT_LEANBACK, false)
        val system = GameSystem.findByIdOrNull(game.systemId) ?: return false
        val targetConfig =
            system.systemCoreConfigs.firstOrNull { it.coreID.coreName == targetCoreName } ?: return false

        coresSelection.updateCoreConfigForGame(game, targetConfig.coreID)
        coresSelection.clearAllFallbackPromptsDismissed(game)
        relaunchWithCore(activity, game, targetConfig, leanback, arrayListOf())
        return true
    }

    private suspend fun handleCanceledGameFinish(
        activity: Activity,
        data: Intent?,
        lastLaunch: ActiveLaunch?,
    ): Boolean {
        if (data != null || lastLaunch == null) return false
        val appContext = activity.applicationContext
        GameProcessSession.awaitGameProcessExit(appContext)

        val crashTimestamp =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                CoreCrashFallback.findNativeCrashSince(appContext, lastLaunch.startedAtMs)
                    ?: run {
                        delay(NATIVE_CRASH_RETRY_DELAY_MS)
                        CoreCrashFallback.findNativeCrashSince(appContext, lastLaunch.startedAtMs)
                    }
            } else {
                val hasMatchingBreadcrumb =
                    TelemetryContext.lastGameSession(appContext).isNotEmpty() &&
                        TelemetryContext.lastGameSessionStartedAt(appContext) >=
                        lastLaunch.startedAtMs - NATIVE_CRASH_CLOCK_SLOP_MS
                if (hasMatchingBreadcrumb) System.currentTimeMillis() else null
            }
                ?: return false

        val updatedTriedCores =
            ArrayList((lastLaunch.triedCores + lastLaunch.coreConfig.coreID.coreName).distinct())
        val hasFallback =
            GameSystem.findByIdOrNull(lastLaunch.game.systemId)?.let { system ->
                CoreFallbackPolicy.findNextFallbackCore(system, updatedTriedCores, allowDeprecated = true)
            } != null

        if (hasFallback) {
            CoreCrashFallback.markExitHandled(appContext, crashTimestamp)
            if (tryFallbackCore(
                    activity = activity,
                    game = lastLaunch.game,
                    triedCores = updatedTriedCores,
                    leanback = lastLaunch.leanback,
                    allowDeprecated = true,
                    notifyPreviousCoreName = lastLaunch.coreConfig.coreID.coreDisplayName,
                )
            ) {
                return true
            }
        }

        CoreCrashFallback.applyAsync(appContext)
        return false
    }

    /**
     * O `:game` recusou a sessao por ja ter hospedado outra (ver [GameProcessSession]) e se matou.
     * Relanca o mesmo jogo, com os mesmos parametros, depois que aquele processo sumir.
     */
    private suspend fun relaunchInFreshProcess(
        activity: Activity,
        data: Intent?,
    ): Boolean {
        val extras = data?.extras ?: return false
        val game = extras.getSerializable(BaseGameActivity.PLAY_GAME_RESULT_GAME) as? Game ?: return false
        val coreConfig =
            extras.getSerializable(BaseGameActivity.PLAY_GAME_RESULT_SYSTEM_CORE_CONFIG) as? SystemCoreConfig
                ?: return false
        val restarts = extras.getInt(BaseGameActivity.PLAY_GAME_RESULT_PROCESS_RESTARTS, 0)
        if (restarts >= MAX_PROCESS_RESTARTS) {
            // O processo novo nao veio. Relancar de novo so repetiria o ciclo.
            handleUnsuccessfulGameFinish(
                activity,
                activity.getString(R.string.lemuroid_app_error_disclamer),
                "Game process could not be restarted ($restarts attempts)",
            )
            return false
        }

        val pid = extras.getInt(BaseGameActivity.PLAY_GAME_RESULT_PID, 0).takeIf { it > 0 }
        val useLeanback = extras.getBoolean(BaseGameActivity.PLAY_GAME_RESULT_LEANBACK, false)
        val triedCores =
            extras.getStringArrayList(BaseGameActivity.PLAY_GAME_RESULT_TRIED_CORES) ?: arrayListOf()
        GameProcessSession.awaitGameProcessExit(activity.applicationContext, pid)
        recordActiveLaunch(game, coreConfig, triedCores, useLeanback)
        BaseGameActivity.launchGame(
            activity = activity,
            systemCoreConfig = coreConfig,
            game = game,
            loadSave = extras.getBoolean(BaseGameActivity.PLAY_GAME_RESULT_LOAD_SAVE, false),
            useLeanback = useLeanback,
            triedCores = triedCores,
            processRestarts = restarts + 1,
        )
        return true
    }

    private suspend fun handleErrorWithCorruptionCheck(
        activity: Activity,
        game: Game?,
        errorMessage: String,
        isRomLoadFailure: Boolean,
        triedCores: ArrayList<String>,
        leanback: Boolean,
    ): Boolean {
        // Only treat as corruption if the error signal indicates the ROM file itself failed to load.
        // Other errors (e.g. missing BIOS) are user-actionable and should be shown as-is.
        if (game != null && isRomLoadFailure) {
            // A "ROM load failed" signal can actually be a missing BIOS that the core only
            // discovers after loading (e.g. Sega CD / 32X-CD disc images). Never delete a
            // downloaded ROM in that case — surface the BIOS error so the user can provide it
            // and keep the ROM. Only genuine load failures fall through to corruption handling.
            val missingBios =
                withContext(Dispatchers.IO) {
                    GameSystem.findByIdOrNull(game.systemId)
                        ?.systemCoreConfigs
                        ?.flatMap { biosManager.getMissingBiosFiles(it, game) }
                        ?.distinct()
                        .orEmpty()
                }
            if (missingBios.isNotEmpty()) {
                Timber.w("Load failed for ${game.fileName} but BIOS is missing $missingBios — keeping ROM")
                handleUnsuccessfulGameFinish(
                    activity,
                    activity.getString(R.string.game_loader_error_missing_bios, missingBios.joinToString(", ")),
                    null,
                )
                return false
            }

            // Check missing arcade dependencies (clone parents, BIOS, devices)
            val missingArcadeDeps =
                withContext(Dispatchers.IO) {
                    romOnDemandManager.getMissingArcadeDependencies(game)
                }
            if (missingArcadeDeps.isNotEmpty()) {
                Timber.w(
                    "Load failed for ${game.fileName} but arcade dependencies are missing: " +
                        "$missingArcadeDeps — keeping ROM",
                )
                handleUnsuccessfulGameFinish(
                    activity,
                    "Arquivos de arcade necessários ausentes: ${missingArcadeDeps.joinToString(", ")}",
                    null,
                )
                return false
            }

            // Verify if the ROM file is genuinely corrupt before deleting
            val isCorrupted =
                withContext(Dispatchers.IO) {
                    isRomFileActuallyCorrupted(game)
                }
            if (isCorrupted) {
                val wasDownloaded = retrogradeDb.downloadedRomDao().isDownloaded(game.systemId, game.fileName)
                if (wasDownloaded || romOnDemandManager.isManagedRom(game)) {
                    romOnDemandManager.deleteRom(game)
                    handleUnsuccessfulGameFinish(
                        activity,
                        activity.getString(R.string.rom_corruption_error_message),
                        null,
                    )
                    return false
                }
            } else {
                Timber.w(
                    "Load failed for ${game.fileName} with isRomLoadFailure, but file is valid on disk. Keeping ROM.",
                )
            }
        }
        if (!tryFallbackCore(activity, game, triedCores, leanback, allowDeprecated = true)) {
            handleUnsuccessfulGameFinish(activity, errorMessage, null)
            return false
        }
        return true
    }

    private suspend fun tryFallbackCore(
        activity: Activity,
        game: Game?,
        triedCores: List<String>,
        leanback: Boolean,
        allowDeprecated: Boolean = true,
        stallFallback: Boolean = false,
        notifyPreviousCoreName: String? = null,
    ): Boolean {
        if (game == null) return false
        val system = GameSystem.findByIdOrNull(game.systemId) ?: return false
        val nextCore =
            CoreFallbackPolicy.findNextFallbackCore(system, triedCores, allowDeprecated)
                ?: return false
        Timber.i("Core fallback: tried=$triedCores, trying=${nextCore.coreID.coreName}")
        val previousCoreName =
            notifyPreviousCoreName
                ?: triedCores.lastOrNull()?.let { findByName(it)?.coreDisplayName ?: it }
        if (previousCoreName != null) {
            val toastRes =
                if (stallFallback) {
                    R.string.game_core_stalled_relaunching
                } else {
                    R.string.game_core_fallback_relaunching
                }
            activity.displayToast(
                activity.getString(toastRes, previousCoreName, nextCore.coreID.coreDisplayName),
            )
        }
        relaunchWithCore(activity, game, nextCore, leanback, ArrayList(triedCores))
        return true
    }

    private suspend fun relaunchWithCore(
        activity: Activity,
        game: Game,
        coreConfig: SystemCoreConfig,
        leanback: Boolean,
        triedCores: ArrayList<String>,
    ) {
        // O resultado chega no finish(), e o :game que falhou so sai 400 ms (ate 2,4 s, com
        // telemetria em envio) depois. Relancar antes disso cai nele — e o exitProcess pendente
        // mata a sessao nova no meio da carga.
        GameProcessSession.awaitGameProcessExit(activity.applicationContext)
        if (activity.isFinishing || activity.isDestroyed) return
        cancelBackgroundWork(activity.applicationContext)
        recordActiveLaunch(game, coreConfig, triedCores, leanback)
        BaseGameActivity.launchGame(activity, coreConfig, game, false, leanback, triedCores)
    }

    private fun cancelBackgroundWork(context: Context) {
        SaveSyncWork.cancelAutoWork(context)
        SaveSyncWork.cancelManualWork(context)
        CacheCleanerWork.cancelCleanCacheLRU(context)
    }

    private fun rescheduleBackgroundWork(context: Context) {
        // Let's slightly delay the sync. Maybe the user wants to play another game.
        SaveSyncWork.enqueueAutoWork(context, 5)
        CacheCleanerWork.enqueueCleanCacheLRU(context)
    }

    private fun handleUnsuccessfulGameFinish(
        activity: Activity,
        message: String,
        messageDetail: String?,
    ) {
        GameCrashActivity.launch(activity, message, messageDetail)
    }

    private suspend fun handleSuccessfulGameFinish(
        activity: Activity,
        enableRatingFlow: Boolean,
        data: Intent?,
        onPromptDismissed: (() -> Unit)?,
    ): Boolean {
        val savesFailed = data?.getBooleanExtra(BaseGameActivity.PLAY_GAME_RESULT_SAVES_FAILED, false) == true
        // O aviso vem do processo `:game`, que sai com `finishAndExitProcess()` logo depois de
        // mandar o resultado — um toast de la nunca chegaria a aparecer. Ver
        // `docs/bugs/open/2026-08-09-anr-inicializar-jogo-runongl-thread.md`.
        if (savesFailed) {
            Timber.w("Game exited without persisting its saves")
            activity.displayToast(R.string.game_toast_exit_save_failed)
        }

        val duration =
            data?.extras?.getLong(BaseGameActivity.PLAY_GAME_RESULT_SESSION_DURATION)
                ?: 0L
        val game =
            data?.extras?.getSerializable(BaseGameActivity.PLAY_GAME_RESULT_GAME) as? Game
                ?: run {
                    Timber.w(
                        "handleSuccessfulGameFinish: game extra is null or wrong type, skipping lastPlayedAt update",
                    )
                    return false
                }

        val leanback = data.getBooleanExtra(BaseGameActivity.PLAY_GAME_RESULT_LEANBACK, false)
        val coreName = data.getStringExtra(BaseGameActivity.PLAY_GAME_RESULT_CORE_ID)
        val firstFrameRendered =
            data.getBooleanExtra(BaseGameActivity.PLAY_GAME_RESULT_FIRST_FRAME_RENDERED, true)
        val triedCores =
            data.getStringArrayListExtra(BaseGameActivity.PLAY_GAME_RESULT_TRIED_CORES).orEmpty()

        if (CoreFallbackPolicy.shouldPersistFallbackCore(triedCores, coreName, firstFrameRendered, savesFailed)) {
            findByName(coreName!!)?.let { coreId ->
                coresSelection.updateCoreConfigForGame(game, coreId)
            }
        }

        val system = GameSystem.findByIdOrNull(game.systemId)
        val effectiveTried =
            if (coreName != null && coreName !in triedCores) triedCores + coreName else triedCores
        val nextCore =
            system?.let {
                CoreFallbackPolicy.findNextFallbackCore(it, effectiveTried, allowDeprecated = false)
            }

        if (system != null && nextCore != null && coreName != null) {
            val isPrimaryCore = system.systemCoreConfigs.firstOrNull()?.coreID?.coreName == coreName
            val dismissed = coresSelection.isFallbackPromptDismissed(game, coreName)
            val reason =
                CoreFallbackPolicy.evaluateExitPrompt(
                    savesFailed = savesFailed,
                    firstFrameRendered = firstFrameRendered,
                    sessionDurationMs = duration,
                    isPrimaryCore = isPrimaryCore,
                    promptAlreadyDismissed = dismissed,
                )
            if (reason != null) {
                val currentDisplayName = findByName(coreName)?.coreDisplayName ?: coreName
                val shown =
                    showFallbackCoreDialog(
                        activity = activity,
                        game = game,
                        currentCoreName = coreName,
                        currentCoreDisplayName = currentDisplayName,
                        nextCore = nextCore,
                        triedCores = effectiveTried,
                        reason = reason,
                        leanback = leanback,
                        onPromptDismissed = onPromptDismissed,
                    )
                if (shown) {
                    return true
                }
            }
        }

        if (enableRatingFlow) {
            displayReviewRequest(activity, duration)
        }
        return false
    }

    private fun showFallbackCoreDialog(
        activity: Activity,
        game: Game,
        currentCoreName: String,
        currentCoreDisplayName: String,
        nextCore: SystemCoreConfig,
        triedCores: List<String>,
        reason: CoreFallbackPolicy.FallbackPromptReason,
        leanback: Boolean,
        onPromptDismissed: (() -> Unit)?,
    ): Boolean {
        if (activity.isFinishing || activity.isDestroyed) return false

        val nextDisplayName = nextCore.coreID.coreDisplayName
        val messageRes =
            when (reason) {
                CoreFallbackPolicy.FallbackPromptReason.CORE_STALLED_ON_EXIT ->
                    R.string.game_core_fallback_prompt_stalled
                CoreFallbackPolicy.FallbackPromptReason.NO_FRAME_RENDERED ->
                    R.string.game_core_fallback_prompt_no_frame
                CoreFallbackPolicy.FallbackPromptReason.SHORT_SESSION ->
                    R.string.game_core_fallback_prompt_short_session
            }
        val negativeRes =
            if (reason == CoreFallbackPolicy.FallbackPromptReason.SHORT_SESSION) {
                R.string.game_core_fallback_prompt_was_fine
            } else {
                R.string.game_core_fallback_prompt_not_now
            }

        val dismissAction: () -> Unit = {
            if (reason == CoreFallbackPolicy.FallbackPromptReason.SHORT_SESSION) {
                val owner = activity as? LifecycleOwner
                if (owner != null) {
                    owner.lifecycleScope.launch {
                        try {
                            coresSelection.dismissFallbackPrompt(game, currentCoreName)
                        } finally {
                            onPromptDismissed?.invoke()
                        }
                    }
                } else {
                    onPromptDismissed?.invoke()
                }
            } else {
                onPromptDismissed?.invoke()
            }
        }

        val builder =
            if (leanback) {
                AlertDialog.Builder(activity, R.style.LemuroidTVDialog)
            } else {
                AlertDialog.Builder(activity)
            }
        val positiveLabel = activity.getString(R.string.game_core_fallback_prompt_try_other, nextDisplayName)
        builder
            .setTitle(R.string.game_core_fallback_prompt_title)
            .setMessage(activity.getString(messageRes, game.title, currentCoreDisplayName, nextDisplayName))
            .setPositiveButton(positiveLabel) { _, _ ->
                (activity as? LifecycleOwner)?.lifecycleScope?.launch {
                    coresSelection.updateCoreConfigForGame(game, nextCore.coreID)
                    coresSelection.clearFallbackPromptDismissed(game, currentCoreName)
                    relaunchWithCore(activity, game, nextCore, leanback, ArrayList(triedCores))
                }
            }
            .setNegativeButton(negativeRes) { _, _ -> dismissAction() }
            .setOnCancelListener { dismissAction() }
            .show()
        return true
    }

    private suspend fun displayReviewRequest(
        activity: Activity,
        durationMillis: Long,
    ) {
        delay(500)
        reviewManager.launchReviewFlow(activity, durationMillis)
    }

    private fun isRomFileActuallyCorrupted(game: Game): Boolean {
        val uri = Uri.parse(game.fileUri)
        if (uri.scheme == "content") return false // Cannot verify SAF content reliably, do not delete
        val file = File(uri.path ?: return false)
        if (!file.exists() || file.length() == 0L) return true
        if (file.extension.equals("zip", ignoreCase = true)) {
            return try {
                ZipFile(file).use { zf ->
                    !zf.entries().hasMoreElements()
                }
            } catch (e: Exception) {
                Timber.e(e, "ROM zip file is genuinely corrupt: ${file.name}")
                true
            }
        }
        return false
    }

    companion object {
        /**
         * Um relancamento basta quando o `:game` velho morre. O segundo e terceiro cobrem aparelhos em que
         * nao da para listar processos via ActivityManager e o relancamento pode competir com a saida.
         */
        private const val MAX_PROCESS_RESTARTS = 3
        private const val NATIVE_CRASH_RETRY_DELAY_MS = 200L
        private const val NATIVE_CRASH_CLOCK_SLOP_MS = 1_000L
    }
}
