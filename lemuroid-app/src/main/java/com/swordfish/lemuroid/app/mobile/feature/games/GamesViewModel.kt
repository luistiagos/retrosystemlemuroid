package com.swordfish.lemuroid.app.mobile.feature.games

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import com.swordfish.lemuroid.common.paging.buildFlowPaging
import com.swordfish.lemuroid.lib.library.MetaSystemID
import com.swordfish.lemuroid.lib.library.db.RetrogradeDatabase
import com.swordfish.lemuroid.lib.library.db.entity.Game
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest

/**
 * Sort order for the per-system game catalog list.
 *
 * - [POPULARITY] — default; sorts by [Game.popularityIndex] DESC so the most-played/known
 *   titles appear first. Downloaded ROMs are always pinned above non-downloaded ones.
 * - [ALPHABETICAL] — sorts by title ASC (legacy behavior, user-selectable via the list header).
 */
enum class GameSortOrder { POPULARITY, ALPHABETICAL }

class GamesViewModel(
    private val retrogradeDb: RetrogradeDatabase,
    initialMetaSystem: MetaSystemID,
) : ViewModel() {
    class Factory(
        private val retrogradeDb: RetrogradeDatabase,
        private val initialMetaSystem: MetaSystemID,
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return GamesViewModel(retrogradeDb, initialMetaSystem) as T
        }
    }

    private val metaSystemId = MutableStateFlow(initialMetaSystem)
    val sortOrder = MutableStateFlow(GameSortOrder.POPULARITY)

    fun setSortOrder(order: GameSortOrder) {
        sortOrder.value = order
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val games: Flow<PagingData<Game>> =
        combine(metaSystemId, sortOrder) { meta, sort -> meta to sort }
            .flatMapLatest { (metaSystem, sort) ->
                val systemIds = metaSystem.systemIDs.map { it.dbname }
                when (systemIds.size) {
                    0 -> emptyFlow()
                    1 -> buildFlowPaging(20, viewModelScope) {
                        if (sort == GameSortOrder.POPULARITY)
                            retrogradeDb.gameDao().selectGroupedBySystemSortedByPopularity(systemIds.first())
                        else
                            retrogradeDb.gameDao().selectGroupedBySystem(systemIds.first())
                    }
                    else -> buildFlowPaging(20, viewModelScope) {
                        if (sort == GameSortOrder.POPULARITY)
                            retrogradeDb.gameDao().selectGroupedBySystemsSortedByPopularity(systemIds)
                        else
                            retrogradeDb.gameDao().selectGroupedBySystems(systemIds)
                    }
                }
            }
}
