package com.swordfish.lemuroid.app.shared.main

import android.app.Activity
import android.content.Context
import android.content.Intent
import com.swordfish.lemuroid.R
import com.swordfish.lemuroid.app.shared.game.BaseGameActivity
import com.swordfish.lemuroid.app.shared.gamecrash.GameCrashActivity
import com.swordfish.lemuroid.app.shared.roms.RomOnDemandManager
import com.swordfish.lemuroid.app.shared.savesync.SaveSyncWork
import com.swordfish.lemuroid.app.shared.storage.cache.CacheCleanerWork
import com.swordfish.lemuroid.common.displayToast
import com.swordfish.lemuroid.ext.feature.review.ReviewManager
import com.swordfish.lemuroid.lib.bios.BiosManager
import com.swordfish.lemuroid.lib.library.GameSystem
import com.swordfish.lemuroid.lib.library.db.RetrogradeDatabase
import com.swordfish.lemuroid.lib.library.db.entity.Game
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import timber.log.Timber

class GameLaunchTaskHandler(
    private val reviewManager: ReviewManager,
    private val retrogradeDb: RetrogradeDatabase,
    private val romOnDemandManager: RomOnDemandManager,
    private val biosManager: BiosManager,
) {
    fun handleGameStart(context: Context) {
        cancelBackgroundWork(context)
    }

    suspend fun prepareGameForLaunch(game: Game): Game =
        romOnDemandManager.prepareGameForLaunch(game)

    suspend fun handleGameFinish(
        enableRatingFlow: Boolean,
        activity: Activity,
        resultCode: Int,
        data: Intent?,
    ) {
        rescheduleBackgroundWork(activity.applicationContext)
        when (resultCode) {
            Activity.RESULT_OK -> handleSuccessfulGameFinish(activity, enableRatingFlow, data)
            BaseGameActivity.RESULT_ERROR -> {
                val game = data?.extras?.getSerializable(BaseGameActivity.PLAY_GAME_RESULT_GAME) as? Game
                val errorMessage = data?.getStringExtra(BaseGameActivity.PLAY_GAME_RESULT_ERROR)
                    ?: activity.getString(R.string.lemuroid_crash_disclamer)
                val isRomLoadFailure = data?.getBooleanExtra(
                    BaseGameActivity.PLAY_GAME_RESULT_IS_ROM_LOAD_FAILURE, false
                ) ?: false
                val triedCores = data?.getStringArrayListExtra(BaseGameActivity.PLAY_GAME_RESULT_TRIED_CORES) ?: arrayListOf()
                val leanback = data?.getBooleanExtra(BaseGameActivity.PLAY_GAME_RESULT_LEANBACK, false) ?: false
                handleErrorWithCorruptionCheck(activity, game, errorMessage, isRomLoadFailure, triedCores, leanback)
            }
            BaseGameActivity.RESULT_UNEXPECTED_ERROR -> {
                val game = data?.extras?.getSerializable(BaseGameActivity.PLAY_GAME_RESULT_GAME) as? Game
                val triedCores = data?.getStringArrayListExtra(BaseGameActivity.PLAY_GAME_RESULT_TRIED_CORES) ?: arrayListOf()
                val leanback = data?.getBooleanExtra(BaseGameActivity.PLAY_GAME_RESULT_LEANBACK, false) ?: false
                val errorDetail = data?.getStringExtra(BaseGameActivity.PLAY_GAME_RESULT_ERROR)
                // Default true = comportamento antigo, para o caso de o extra não vir.
                val isEmulatorFailure =
                    data?.getBooleanExtra(BaseGameActivity.PLAY_GAME_RESULT_IS_EMULATOR_FAILURE, true) ?: true
                val isCoreStall =
                    data?.getBooleanExtra(BaseGameActivity.PLAY_GAME_RESULT_IS_CORE_STALL, false) ?: false
                if (isCoreStall) {
                    // Nucleo parou de responder: trocar de core so repetiria a espera de 30 s, e
                    // nem o disclaimer de core (limpar dados / resetar) nem o de app ("o problema
                    // nao e do nucleo de emulacao") descrevem o que aconteceu.
                    Timber.w("Core stalled the GL thread: $errorDetail")
                    handleUnsuccessfulGameFinish(
                        activity,
                        activity.getString(R.string.lemuroid_core_stalled_disclamer),
                        errorDetail,
                    )
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
                } else if (!tryFallbackCore(activity, game, triedCores, leanback)) {
                    handleUnsuccessfulGameFinish(
                        activity,
                        activity.getString(R.string.lemuroid_crash_disclamer),
                        errorDetail,
                    )
                }
            }
        }
    }

    private suspend fun handleErrorWithCorruptionCheck(
        activity: Activity,
        game: Game?,
        errorMessage: String,
        isRomLoadFailure: Boolean,
        triedCores: ArrayList<String>,
        leanback: Boolean,
    ) {
        // Only treat as corruption if the error signal indicates the ROM file itself failed to load.
        // Other errors (e.g. missing BIOS) are user-actionable and should be shown as-is.
        if (game != null && isRomLoadFailure) {
            // A "ROM load failed" signal can actually be a missing BIOS that the core only
            // discovers after loading (e.g. Sega CD / 32X-CD disc images). Never delete a
            // downloaded ROM in that case — surface the BIOS error so the user can provide it
            // and keep the ROM. Only genuine load failures fall through to corruption handling.
            val missingBios = withContext(Dispatchers.IO) {
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
                return
            }
            val wasDownloaded = retrogradeDb.downloadedRomDao().isDownloaded(game.systemId, game.fileName)
            if (wasDownloaded || romOnDemandManager.isManagedRom(game)) {
                romOnDemandManager.deleteRom(game)
                handleUnsuccessfulGameFinish(
                    activity,
                    activity.getString(R.string.rom_corruption_error_message),
                    null,
                )
                return
            }
        }
        if (!tryFallbackCore(activity, game, triedCores, leanback)) {
            handleUnsuccessfulGameFinish(activity, errorMessage, null)
        }
    }

    private fun tryFallbackCore(
        activity: Activity,
        game: Game?,
        triedCores: List<String>,
        leanback: Boolean,
    ): Boolean {
        if (game == null) return false
        val system = GameSystem.findByIdOrNull(game.systemId) ?: return false
        val nextCore = system.systemCoreConfigs.firstOrNull { it.coreID.coreName !in triedCores }
            ?: return false
        Timber.i("Core fallback: tried=$triedCores, trying=${nextCore.coreID.coreName}")
        BaseGameActivity.launchGame(activity, nextCore, game, false, leanback, ArrayList(triedCores))
        return true
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
    ) {
        // O aviso vem do processo `:game`, que sai com `finishAndExitProcess()` logo depois de
        // mandar o resultado — um toast de la nunca chegaria a aparecer. Ver
        // `documentacao/bugs/open/2026-08-09-anr-inicializar-jogo-runongl-thread.md`.
        if (data?.getBooleanExtra(BaseGameActivity.PLAY_GAME_RESULT_SAVES_FAILED, false) == true) {
            Timber.w("Game exited without persisting its saves")
            activity.displayToast(R.string.game_toast_exit_save_failed)
        }

        val duration =
            data?.extras?.getLong(BaseGameActivity.PLAY_GAME_RESULT_SESSION_DURATION)
                ?: 0L
        val game = data?.extras?.getSerializable(BaseGameActivity.PLAY_GAME_RESULT_GAME) as? Game
            ?: run {
                Timber.w("handleSuccessfulGameFinish: game extra is null or wrong type, skipping lastPlayedAt update")
                return
            }

        updateGamePlayedTimestamp(game)
        if (enableRatingFlow) {
            displayReviewRequest(activity, duration)
        }
    }

    private suspend fun displayReviewRequest(
        activity: Activity,
        durationMillis: Long,
    ) {
        delay(500)
        reviewManager.launchReviewFlow(activity, durationMillis)
    }

    private suspend fun updateGamePlayedTimestamp(game: Game) {
        try {
            retrogradeDb.gameDao().update(game.copy(lastPlayedAt = System.currentTimeMillis()))
        } catch (e: android.database.sqlite.SQLiteException) {
            Timber.e(e, "Failed to update lastPlayedAt for ${game.title} due to database error")
        }
    }
}
