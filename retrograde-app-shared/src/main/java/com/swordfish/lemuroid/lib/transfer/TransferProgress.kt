package com.swordfish.lemuroid.lib.transfer

sealed class TransferProgress {
    object Idle : TransferProgress()

    data class InProgress(
        val currentIndex: Int,
        val totalCount: Int,
        val currentGameName: String,
        val phase: Phase = Phase.COPYING_ROMS,
    ) : TransferProgress() {
        enum class Phase { COPYING_APK, COPYING_ROMS, COPYING_SAVES, COPYING_STATES, WRITING_MANIFEST }

        val progressFraction: Float
            get() = if (totalCount > 0) currentIndex.toFloat() / totalCount else 0f
    }

    data class Completed(val gamesExported: Int) : TransferProgress()

    data class Error(val message: String) : TransferProgress()
}
