package com.swordfish.lemuroid.app.tv.home

import android.content.Context
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.swordfish.lemuroid.app.mobile.feature.installed.InstalledGameGroupUiModel
import com.swordfish.lemuroid.app.mobile.feature.installed.StorageAvailabilityMonitor
import com.swordfish.lemuroid.app.shared.library.PendingOperationsMonitor
import com.swordfish.lemuroid.app.shared.systems.MetaSystemInfo
import com.swordfish.lemuroid.app.utils.android.isTvDevice
import com.swordfish.lemuroid.common.coroutines.debounceAfterFirst
import com.swordfish.lemuroid.lib.library.GameSystem
import com.swordfish.lemuroid.lib.library.MetaSystemID
import com.swordfish.lemuroid.lib.library.db.RetrogradeDatabase
import com.swordfish.lemuroid.lib.library.db.entity.Game
import com.swordfish.lemuroid.lib.library.metaSystemID
import com.swordfish.lemuroid.lib.storage.DirectoriesManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(FlowPreview::class)
class TVHomeViewModel(
    retrogradeDb: RetrogradeDatabase,
    appContext: Context,
    directoriesManager: DirectoriesManager,
) : ViewModel() {
    companion object {
        const val CAROUSEL_MAX_ITEMS = 10
        const val DEBOUNCE_TIME = 100L

        private val TV_HIDDEN_SYSTEMS = setOf(MetaSystemID.PSP, MetaSystemID.NINTENDO_3DS)
    }

    class Factory(
        val retrogradeDb: RetrogradeDatabase,
        val appContext: Context,
        val directoriesManager: DirectoriesManager,
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            @Suppress("UNCHECKED_CAST")
            return TVHomeViewModel(retrogradeDb, appContext, directoriesManager) as T
        }
    }

    private val gameDao = retrogradeDb.gameDao()
    private val storageAvailabilityMonitor = StorageAvailabilityMonitor(appContext)

    // Resolving the ROMs folder touches the storage: read only from the IO dispatcher.
    private val romsDirPrefix: String by lazy {
        runCatching {
            directoriesManager.getInternalRomsDirectory().toUri().toString().trimEnd('/')
        }.getOrDefault("")
    }
    private val managedMarker: String by lazy { directoriesManager.getManagedRomsMarker() }

    /**
     * The "Saved games" row (redesign 3.6): the [CAROUSEL_MAX_ITEMS] most recent groups of the phone's Saved games
     * tab, same query and media check as `HomeViewModel.savedGames`. `null` until the first result.
     *
     * Kept out of the home `combine`: the query walks the whole `games` table and would hold back the first state.
     * The fragment subscribes after its first frame; from then on it stays hot, so coming back from a game does not
     * run the query again.
     */
    val savedGames: StateFlow<List<InstalledGameGroupUiModel>?> =
        flow {
            emitAll(
                gameDao.observeInstalledGameGroups(romsDirPrefix, managedMarker)
                    .combine(storageAvailabilityMonitor.mediaStateChanges) { groups, _ ->
                        groups.take(CAROUSEL_MAX_ITEMS).map { group ->
                            InstalledGameGroupUiModel(
                                group = group,
                                isAvailable = storageAvailabilityMonitor.isGameAvailable(group.game),
                            )
                        }
                    },
            )
        }
            .flowOn(Dispatchers.IO)
            .stateIn(viewModelScope, SharingStarted.Lazily, null)

    /** Saved versions of the group of [game], most recent first: the picker of a group with more than one. */
    suspend fun savedVariants(game: Game): List<Game> =
        withContext(Dispatchers.IO) {
            gameDao.getInstalledVariantsForGroup(game.systemId, game.title, romsDirPrefix, managedMarker)
        }

    /** Same check as the phone's Saved games: touches the storage, so it runs on the IO dispatcher. */
    suspend fun isAvailable(game: Game): Boolean =
        withContext(Dispatchers.IO) { storageAvailabilityMonitor.isGameAvailable(game) }

    data class HomeViewState(
        val favoritesGames: List<Game> = emptyList(),
        val recentGames: List<Game> = emptyList(),
        val metaSystems: List<MetaSystemInfo> = emptyList(),
        val indexInProgress: Boolean = false,
        val scanInProgress: Boolean = false,
    )

    private val viewStates = MutableStateFlow(HomeViewState())

    fun getViewStates(): Flow<HomeViewState> {
        return viewStates
    }

    private fun buildViewState(
        favoritesGames: List<Game>,
        recentGames: List<Game>,
        metaSystems: List<MetaSystemInfo>,
        indexInProgress: Boolean,
        scanInProgress: Boolean,
    ): HomeViewState {
        return HomeViewState(
            favoritesGames,
            recentGames,
            metaSystems,
            indexInProgress,
            scanInProgress,
        )
    }

    init {
        viewModelScope.launch {
            val uiStatesFlow =
                combine(
                    favoriteGames(retrogradeDb).distinctUntilChanged(),
                    recentGames(retrogradeDb).distinctUntilChanged(),
                    availableSystems(retrogradeDb, appContext).distinctUntilChanged(),
                    indexingInProgress(appContext).onStart { emit(false) }.distinctUntilChanged(),
                    directoryScanInProgress(appContext).onStart { emit(false) }.distinctUntilChanged(),
                    ::buildViewState,
                )

            uiStatesFlow
                .debounceAfterFirst(DEBOUNCE_TIME)
                .flowOn(Dispatchers.IO)
                .collect { viewStates.value = it }
        }
    }

    private fun directoryScanInProgress(appContext: Context) =
        PendingOperationsMonitor(appContext).isDirectoryScanInProgress()

    private fun indexingInProgress(appContext: Context) =
        PendingOperationsMonitor(appContext).anyLibraryOperationInProgress()

    private fun availableSystems(
        retrogradeDb: RetrogradeDatabase,
        appContext: Context,
    ) = retrogradeDb.gameDao()
        .selectSystemsWithCount()
        .map { systemCounts ->
            val hideSystems = appContext.isTvDevice()
            systemCounts.asSequence()
                .filter { (_, count) -> count > 0 }
                .mapNotNull { (systemId, count) ->
                    GameSystem.findByIdOrNull(systemId)?.let { it.metaSystemID() to count }
                }
                .filter { (metaSystemId, _) -> !hideSystems || metaSystemId !in TV_HIDDEN_SYSTEMS }
                .groupBy { (metaSystemId, _) -> metaSystemId }
                .map { (metaSystemId, counts) -> MetaSystemInfo(metaSystemId, counts.sumOf { it.second }) }
                .sortedBy { it.getName(appContext) }
                .toList()
        }

    private fun favoriteGames(retrogradeDb: RetrogradeDatabase) =
        retrogradeDb.gameDao()
            .selectFirstFavoritesRecents(CAROUSEL_MAX_ITEMS + 1)

    private fun recentGames(retrogradeDb: RetrogradeDatabase) =
        retrogradeDb.gameDao()
            .selectFirstUnfavoriteRecents(CAROUSEL_MAX_ITEMS)
}
