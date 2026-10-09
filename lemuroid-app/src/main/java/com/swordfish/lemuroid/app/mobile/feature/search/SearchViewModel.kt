package com.swordfish.lemuroid.app.mobile.feature.search

import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import com.swordfish.lemuroid.common.paging.buildFlowPaging
import com.swordfish.lemuroid.lib.library.db.RetrogradeDatabase
import com.swordfish.lemuroid.lib.storage.DirectoriesManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class SearchViewModel(
    private val retrogradeDb: RetrogradeDatabase,
    private val directoriesManager: DirectoriesManager? = null,
) : ViewModel() {
    class Factory(
        val retrogradeDb: RetrogradeDatabase,
        val directoriesManager: DirectoriesManager? = null,
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return SearchViewModel(retrogradeDb, directoriesManager) as T
        }
    }

    val queryString = MutableStateFlow("")
    private val systemIdsFlow = MutableStateFlow<List<String>?>(null)
    val onlyInstalled = MutableStateFlow(false)

    fun setSystemIds(ids: List<String>?) {
        systemIdsFlow.value = ids
    }

    fun setOnlyInstalled(only: Boolean) {
        onlyInstalled.value = only
    }

    enum class UIState { Idle, Loading, Ready }

    // The debounced query that actually drives the DB search. Without this, every
    // keystroke tore down and rebuilt the FTS PagingSource, queueing a burst of
    // SQLite work that flatMapLatest cancels mid-flight — the root cause of the
    // "search is very slow" regression. SharingStarted.Eagerly keeps it warm even
    // when the Search screen is not mounted, so opening the screen doesn't pay a
    // fresh 400 ms debounce delay.
    private val activeQuery: StateFlow<String> =
        queryString
            .debounce(400.milliseconds)
            .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    // True while the user has typed something the debounce hasn't propagated to
    // activeQuery yet. The UI uses this to keep the spinner up during the debounce
    // wait so the user never sees a false "no results" flash while still typing.
    val isSearchPending: StateFlow<Boolean> =
        combine(queryString, activeQuery) { live, debounced ->
            live != debounced
        }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    // Driven by activeQuery (already debounced) rather than queryString, so
    // subscribing/resubscribing the UI does not restart the debounce timer.
    val searchResults =
        combine(activeQuery, systemIdsFlow, onlyInstalled) { query, systemIds, onlyInst ->
            Triple(query, systemIds, onlyInst)
        }
            .filter { (query, _, _) -> query.length >= 3 }
            .flatMapLatest { (query, systemIds, onlyInst) ->
                val romsPrefix = runCatching {
                    directoriesManager?.getInternalRomsDirectory()?.toUri()?.toString()?.trimEnd('/') ?: ""
                }.getOrDefault("")
                val managedMarker = directoriesManager?.getManagedRomsMarker() ?: ""
                buildFlowPaging(30, viewModelScope) {
                    retrogradeDb.gameSearchDao().search(
                        query = query,
                        systemIds = systemIds,
                        onlyInstalled = onlyInst,
                        romsPrefix = romsPrefix,
                        managedMarker = managedMarker,
                    )
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PagingData.empty())
}
