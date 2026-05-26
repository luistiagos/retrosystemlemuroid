package com.swordfish.lemuroid.app.shared.roms

import android.content.Context
import android.net.Uri
import com.swordfish.lemuroid.lib.library.db.dao.SaveQueueDao
import com.swordfish.lemuroid.lib.library.db.entity.Game
import com.swordfish.lemuroid.lib.library.db.entity.SaveQueueItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber

enum class SaveQueueState { QUEUED, SAVING, PAUSED, SAVED, ERROR }

data class SaveQueueEntry(
    val fileName: String,
    val gameId: Int,
    val title: String,
    val coverUrl: String?,
    val fileUri: String,
    val systemId: String,
    val state: SaveQueueState,
    val progress: Float = 0f,
    val errorMessage: String? = null,
)

/**
 * Manages a persistent save (download) queue.
 *
 * One ROM downloads at a time; others wait as QUEUED.
 * State is persisted in Room so the queue survives app restarts.
 * On start, pending QUEUED/SAVING/PAUSED items are automatically re-enqueued.
 */
class SaveQueueManager(
    context: Context,
    private val saveQueueDao: SaveQueueDao,
    private val romOnDemandManager: RomOnDemandManager,
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mutex = Mutex()

    private val _entries = MutableStateFlow<List<SaveQueueEntry>>(emptyList())
    val entries: StateFlow<List<SaveQueueEntry>> = _entries.asStateFlow()

    private val _justCompleted = MutableSharedFlow<Game>(extraBufferCapacity = 1)
    val justCompleted: SharedFlow<Game> = _justCompleted.asSharedFlow()

    private var processorJob: kotlinx.coroutines.Job? = null

    init {
        scope.launch { restorePersistedQueue() }
    }

    // ──────────────────────────────────────────────────────────────
    // Public API
    // ──────────────────────────────────────────────────────────────

    suspend fun enqueue(game: Game) {
        mutex.withLock {
            val alreadyQueued = _entries.value.any { it.fileName == game.fileName }
            if (alreadyQueued) return

            val position = (saveQueueDao.maxPosition() ?: -1) + 1
            val item = SaveQueueItem(
                fileName = game.fileName,
                gameId = game.id,
                gameTitle = game.title,
                gameCoverUrl = game.coverFrontUrl,
                gameFileUri = game.fileUri,
                systemId = game.systemId,
                state = "QUEUED",
                addedAt = System.currentTimeMillis(),
                position = position,
            )
            saveQueueDao.insert(item)

            _entries.update { current ->
                current + SaveQueueEntry(
                    fileName = game.fileName,
                    gameId = game.id,
                    title = game.title,
                    coverUrl = game.coverFrontUrl,
                    fileUri = game.fileUri,
                    systemId = game.systemId,
                    state = SaveQueueState.QUEUED,
                )
            }
        }
        ensureProcessorRunning()
    }

    fun pauseActive() {
        romOnDemandManager.pauseDownload()
        updateActiveState(SaveQueueState.PAUSED)
    }

    fun resumeActive() {
        val paused = _entries.value.firstOrNull { it.state == SaveQueueState.PAUSED }
            ?: return
        romOnDemandManager.resumeDownload()
        updateEntryState(paused.fileName, SaveQueueState.SAVING)
    }

    suspend fun cancelItem(fileName: String) {
        val active = _entries.value.firstOrNull {
            it.fileName == fileName && (it.state == SaveQueueState.SAVING || it.state == SaveQueueState.PAUSED)
        }
        if (active != null) {
            romOnDemandManager.cancelActiveDownload()
            // Processor loop will detect cancellation and move to next item.
        }
        mutex.withLock {
            saveQueueDao.deleteByFileName(fileName)
            _entries.update { it.filter { e -> e.fileName != fileName } }
        }
    }

    fun isQueued(fileName: String): Boolean =
        _entries.value.any { it.fileName == fileName }

    /**
     * Removes a single entry that is in ERROR state. Errored items have already been
     * deleted from the DB; this just dismisses them from the in-memory list.
     */
    fun dismissError(fileName: String) {
        _entries.update { list ->
            list.filterNot { it.fileName == fileName && it.state == SaveQueueState.ERROR }
        }
    }

    /**
     * Removes all entries in ERROR state from the list.
     */
    fun clearErrors() {
        _entries.update { list -> list.filterNot { it.state == SaveQueueState.ERROR } }
    }

    // ──────────────────────────────────────────────────────────────
    // Internal helpers
    // ──────────────────────────────────────────────────────────────

    private suspend fun restorePersistedQueue() {
        val persisted = saveQueueDao.getAll()
        if (persisted.isEmpty()) return

        // Reset any SAVING items to QUEUED (they were interrupted mid-download).
        persisted.filter { it.state == "SAVING" }.forEach {
            saveQueueDao.updateState(it.fileName, "QUEUED")
        }

        val restored = persisted.map { item ->
            val state = if (item.state == "PAUSED") SaveQueueState.PAUSED else SaveQueueState.QUEUED
            SaveQueueEntry(
                fileName = item.fileName,
                gameId = item.gameId,
                title = item.gameTitle,
                coverUrl = item.gameCoverUrl,
                fileUri = item.gameFileUri,
                systemId = item.systemId,
                state = state,
            )
        }
        _entries.value = restored
        Timber.d("SaveQueueManager: restored ${restored.size} items from DB")
        ensureProcessorRunning()
    }

    private fun ensureProcessorRunning() {
        if (processorJob?.isActive == true) return
        processorJob = scope.launch(Dispatchers.IO) { processQueue() }
    }

    private suspend fun processQueue() {
        while (true) {
            val next = mutex.withLock {
                _entries.value.firstOrNull { it.state == SaveQueueState.QUEUED }
            } ?: break

            Timber.d("SaveQueueManager: starting save for ${next.fileName}")
            setEntryState(next.fileName, "SAVING", SaveQueueState.SAVING)

            val game = buildGame(next)
            val result = try {
                romOnDemandManager.downloadRom(game) { progress ->
                    _entries.update { list ->
                        list.map { if (it.fileName == next.fileName) it.copy(progress = progress) else it }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Item was cancelled externally — it's already been removed from _entries.
                Timber.d("SaveQueueManager: ${next.fileName} cancelled")
                continue
            } catch (e: Exception) {
                Timber.e(e, "SaveQueueManager: unexpected error for ${next.fileName}")
                RomOnDemandManager.DownloadResult.Failure(e.message ?: "Unknown error")
            }

            mutex.withLock {
                when (result) {
                    is RomOnDemandManager.DownloadResult.Success -> {
                        saveQueueDao.deleteByFileName(next.fileName)
                        _entries.update { list ->
                            list.map {
                                if (it.fileName == next.fileName)
                                    it.copy(state = SaveQueueState.SAVED, progress = 1f)
                                else it
                            }
                        }
                        // Use result.game instead of buildGame(next) so that multi-disc
                        // zip extractions (which update fileUri/fileName in the DB) are
                        // reflected in the game passed to the "play now?" prompt.
                        _justCompleted.tryEmit(result.game)
                        // Remove SAVED entry after a short display delay on main thread.
                        scope.launch {
                            kotlinx.coroutines.delay(3_000)
                            _entries.update { it.filter { e -> e.fileName != next.fileName } }
                        }
                        Timber.d("SaveQueueManager: ${next.fileName} saved successfully")
                    }
                    is RomOnDemandManager.DownloadResult.NotFound -> {
                        saveQueueDao.deleteByFileName(next.fileName)
                        _entries.update { list ->
                            list.map {
                                if (it.fileName == next.fileName)
                                    it.copy(state = SaveQueueState.ERROR, errorMessage = "ROM not found")
                                else it
                            }
                        }
                    }
                    is RomOnDemandManager.DownloadResult.Failure -> {
                        saveQueueDao.deleteByFileName(next.fileName)
                        _entries.update { list ->
                            list.map {
                                if (it.fileName == next.fileName)
                                    it.copy(state = SaveQueueState.ERROR, errorMessage = result.message)
                                else it
                            }
                        }
                    }
                }
            }
        }
    }

    private suspend fun setEntryState(fileName: String, dbState: String, uiState: SaveQueueState) {
        saveQueueDao.updateState(fileName, dbState)
        updateEntryState(fileName, uiState)
    }

    private fun updateEntryState(fileName: String, state: SaveQueueState) {
        _entries.update { list ->
            list.map { if (it.fileName == fileName) it.copy(state = state) else it }
        }
    }

    private fun updateActiveState(state: SaveQueueState) {
        _entries.update { list ->
            list.map {
                if (it.state == SaveQueueState.SAVING) it.copy(state = state) else it
            }
        }
    }

    private fun buildGame(entry: SaveQueueEntry): Game = Game(
        id = entry.gameId,
        fileName = entry.fileName,
        fileUri = entry.fileUri,
        title = entry.title,
        systemId = entry.systemId,
        developer = null,
        coverFrontUrl = null,
        lastIndexedAt = 0L,
    )
}
