package com.swordfish.lemuroid.app.mobile.feature.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.swordfish.lemuroid.app.shared.roms.SaveQueueEntry
import com.swordfish.lemuroid.app.shared.roms.SaveQueueManager
import com.swordfish.lemuroid.app.shared.roms.SaveQueueState
import com.swordfish.lemuroid.lib.library.db.entity.Game
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SaveQueueViewModel(
    private val saveQueueManager: SaveQueueManager,
) : ViewModel() {

    class Factory(
        private val saveQueueManager: SaveQueueManager,
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            SaveQueueViewModel(saveQueueManager) as T
    }

    val entries: StateFlow<List<SaveQueueEntry>> = saveQueueManager.entries

    val justCompleted: SharedFlow<Game> = saveQueueManager.justCompleted

    /** [entries] by `"$systemId/$fileName"`, the same key as `Game.downloadKey`, for the cover badges. */
    val entriesByKey: StateFlow<Map<String, SaveQueueEntry>> = saveQueueManager.entries
        .map { list -> list.associateBy { "${it.systemId}/${it.fileName}" } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    val hasActiveOrQueued: StateFlow<Boolean> = saveQueueManager.entries
        .map { list ->
            list.any { it.state == SaveQueueState.QUEUED || it.state == SaveQueueState.SAVING || it.state == SaveQueueState.PAUSED }
        }
        .stateIn(viewModelScope, SharingStarted.Lazily, false)

    /** Entries still to finish (queued, saving or paused): the count on the landscape rail. */
    val activeCount: StateFlow<Int> =
        saveQueueManager.entries
            .map { list ->
                list.count {
                    it.state == SaveQueueState.QUEUED ||
                        it.state == SaveQueueState.SAVING ||
                        it.state == SaveQueueState.PAUSED
                }
            }
            .stateIn(viewModelScope, SharingStarted.Lazily, 0)

    val activeProgress: StateFlow<Float> = saveQueueManager.entries
        .map { list -> list.firstOrNull { it.state == SaveQueueState.SAVING }?.progress ?: 0f }
        .stateIn(viewModelScope, SharingStarted.Lazily, 0f)

    val hasErrors: StateFlow<Boolean> = saveQueueManager.entries
        .map { list -> list.any { it.state == SaveQueueState.ERROR } }
        .stateIn(viewModelScope, SharingStarted.Lazily, false)

    fun pauseActive() = saveQueueManager.pauseActive()

    fun resumeActive() = saveQueueManager.resumeActive()

    fun cancel(fileName: String) {
        viewModelScope.launch { saveQueueManager.cancelItem(fileName) }
    }

    fun dismissError(fileName: String) = saveQueueManager.dismissError(fileName)

    fun clearErrors() = saveQueueManager.clearErrors()
}
