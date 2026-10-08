package com.swordfish.lemuroid.app.shared.roms

import android.content.Context
import android.database.sqlite.SQLiteException
import android.net.Uri
import com.swordfish.lemuroid.R
import com.swordfish.lemuroid.common.displayToast
import com.swordfish.lemuroid.lib.library.db.dao.GameDao
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
    private val gameDao: GameDao,
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

            try {
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
            } catch (e: SQLiteException) {
                Timber.e(e, "SaveQueueManager: failed to persist queue item for ${game.fileName}")
                appContext.displayToast(R.string.home_download_roms_out_of_space)
                return
            }

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
            try {
                saveQueueDao.deleteByFileName(fileName)
            } catch (e: SQLiteException) {
                Timber.e(e, "SaveQueueManager: failed to delete cancelled item $fileName from DB")
            }
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
        val persisted = try {
            saveQueueDao.getAll()
        } catch (e: SQLiteException) {
            Timber.e(e, "SaveQueueManager: failed to read persisted queue")
            return
        }
        if (persisted.isEmpty()) return

        // Reset any SAVING items to QUEUED (they were interrupted mid-download).
        persisted.filter { it.state == "SAVING" }.forEach {
            try {
                saveQueueDao.updateState(it.fileName, "QUEUED")
            } catch (e: SQLiteException) {
                Timber.e(e, "SaveQueueManager: failed to reset state for ${it.fileName}")
            }
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
        DownloadForegroundService.start(appContext)
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
                        try {
                            saveQueueDao.deleteByFileName(next.fileName)
                        } catch (e: SQLiteException) {
                            Timber.e(e, "SaveQueueManager: failed to delete completed item from DB for ${next.fileName}")
                        }
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
                        try {
                            saveQueueDao.deleteByFileName(next.fileName)
                        } catch (e: SQLiteException) {
                            Timber.e(e, "SaveQueueManager: failed to delete not found item from DB for ${next.fileName}")
                        }
                        _entries.update { list ->
                            list.map {
                                if (it.fileName == next.fileName) {
                                    it.copy(
                                        state = SaveQueueState.ERROR,
                                        errorMessage = appContext.getString(R.string.save_queue_rom_not_found),
                                    )
                                } else {
                                    it
                                }
                            }
                        }
                    }
                    is RomOnDemandManager.DownloadResult.Failure -> {
                        try {
                            saveQueueDao.deleteByFileName(next.fileName)
                        } catch (e: SQLiteException) {
                            Timber.e(e, "SaveQueueManager: failed to delete failed item from DB for ${next.fileName}")
                        }
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
        try {
            saveQueueDao.updateState(fileName, dbState)
        } catch (e: SQLiteException) {
            Timber.e(e, "SaveQueueManager: failed to update state in DB for $fileName")
        }
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

    /**
     * Resolves the authoritative [Game] row for a queue entry.
     *
     * IMPORTANT: return the real DB row (by id) — never a partial reconstruction. This object
     * flows through the whole download → multi-disc extraction → play path, and downstream code
     * persists it back via `game.copy(...).update(...)` (extractMultiDiscZipIfNeeded and
     * GameLaunchTaskHandler.updateGamePlayedTimestamp). Any field missing here would be written
     * back as null/default, wiping the row's coverFrontUrl, popularityIndex, isRepresentative,
     * favorite flag, etc. The fallback (row deleted mid-flight) preserves at least the cover.
     */
    private suspend fun buildGame(entry: SaveQueueEntry): Game =
        try {
            gameDao.selectById(entry.gameId)
        } catch (e: SQLiteException) {
            Timber.e(e, "SaveQueueManager: failed to select game by id ${entry.gameId}")
            null
        } ?: Game(
            id = entry.gameId,
            fileName = entry.fileName,
            fileUri = entry.fileUri,
            title = entry.title,
            systemId = entry.systemId,
            developer = null,
            coverFrontUrl = entry.coverUrl,
            lastIndexedAt = 0L,
        )
}
