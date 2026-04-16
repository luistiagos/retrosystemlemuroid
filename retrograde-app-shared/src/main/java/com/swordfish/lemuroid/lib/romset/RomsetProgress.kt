package com.swordfish.lemuroid.lib.romset

sealed class RomsetProgress {
    object Idle : RomsetProgress()

    data class InProgress(
        val currentIndex: Int,
        val totalCount: Int,
        val currentFileName: String,
        val phase: Phase = Phase.COMPRESSING,
    ) : RomsetProgress() {
        enum class Phase { COMPRESSING, EXTRACTING }

        val progressFraction: Float
            get() = if (totalCount > 0) currentIndex.toFloat() / totalCount else 0f
    }

    data class Completed(val fileCount: Int) : RomsetProgress()

    data class Error(val message: String) : RomsetProgress()
}
