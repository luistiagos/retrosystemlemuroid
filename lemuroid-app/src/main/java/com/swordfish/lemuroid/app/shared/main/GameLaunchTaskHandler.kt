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
import com.swordfish.lemuroid.ext.feature.review.ReviewManager
import com.swordfish.lemuroid.lib.library.db.RetrogradeDatabase
import com.swordfish.lemuroid.lib.library.db.entity.Game
import kotlinx.coroutines.delay

class GameLaunchTaskHandler(
    private val reviewManager: ReviewManager,
    private val retrogradeDb: RetrogradeDatabase,
    private val romOnDemandManager: RomOnDemandManager,
) {
    fun handleGameStart(context: Context) {
        cancelBackgroundWork(context)
    }

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
                handleErrorWithCorruptionCheck(activity, game, errorMessage, isRomLoadFailure)
            }
            BaseGameActivity.RESULT_UNEXPECTED_ERROR ->
                handleUnsuccessfulGameFinish(
                    activity,
                    activity.getString(R.string.lemuroid_crash_disclamer),
                    data?.getStringExtra(BaseGameActivity.PLAY_GAME_RESULT_ERROR),
                )
        }
    }

    private suspend fun handleErrorWithCorruptionCheck(
        activity: Activity,
        game: Game?,
        errorMessage: String,
        isRomLoadFailure: Boolean,
    ) {
        // Only treat as corruption if the error signal indicates the ROM file itself failed to load.
        // Other errors (e.g. missing BIOS) are user-actionable and should be shown as-is.
        if (game != null && isRomLoadFailure) {
            val wasDownloaded = retrogradeDb.downloadedRomDao().isDownloaded(game.fileName)
            if (wasDownloaded) {
                romOnDemandManager.deleteRom(game)
                handleUnsuccessfulGameFinish(
                    activity,
                    activity.getString(R.string.rom_corruption_error_message),
                    null,
                )
                return
            }
        }
        handleUnsuccessfulGameFinish(activity, errorMessage, null)
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
        val duration =
            data?.extras?.getLong(BaseGameActivity.PLAY_GAME_RESULT_SESSION_DURATION)
                ?: 0L
        val game = data?.extras?.getSerializable(BaseGameActivity.PLAY_GAME_RESULT_GAME) as? Game
            ?: return

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
        retrogradeDb.gameDao().update(game.copy(lastPlayedAt = System.currentTimeMillis()))
    }
}
