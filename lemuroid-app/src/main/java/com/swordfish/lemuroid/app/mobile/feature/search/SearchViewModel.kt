package com.swordfish.lemuroid.app.mobile.feature.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import com.swordfish.lemuroid.common.paging.buildFlowPaging
import com.swordfish.lemuroid.lib.library.db.RetrogradeDatabase
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
class SearchViewModel(private val retrogradeDb: RetrogradeDatabase) : ViewModel() {
    class Factory(val retrogradeDb: RetrogradeDatabase) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return SearchViewModel(retrogradeDb) as T
        }
    }

    val queryString = MutableStateFlow("")
    private val systemIdsFlow = MutableStateFlow<List<String>?>(null)

    fun setSystemIds(ids: List<String>?) {
        systemIdsFlow.value = ids
    }

    enum class UIState { Idle, Loading, Ready }

    // The debounced query that actually drives the DB search.
    // SharingStarted.Eagerly ensures it is always up-to-date regardless of
    // whether the Search screen is currently visible, avoiding a 400 ms
    // "re-debounce" delay every time the screen is mounted.
    private val activeQuery: StateFlow<String> =
        queryString
            .debounce(400.milliseconds)
            .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    // True while the user has typed something that the debounce hasn't
    // propagated to activeQuery yet. The UI uses this to keep the spinner
    // visible during the debounce wait, so the user never sees a false
    // "no results" state while they are still typing.
    val isSearchPending: StateFlow<Boolean> =
        combine(queryString, activeQuery) { live, debounced ->
            live != debounced
        }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    // Uses activeQuery directly (no extra debounce inside this chain) so that
    // subscribing/resubscribing the UI does not restart the debounce timer.
    // Results are capped at 100 rows for fast initial display.
    val searchResults =
        combine(activeQuery, systemIdsFlow) { query, systemIds -> query to systemIds }
            .filter { (query, _) -> query.isNotEmpty() }
            .flatMapLatest { (query, systemIds) ->
                buildFlowPaging(100, viewModelScope) {
                    retrogradeDb.gameSearchDao().search(query, systemIds)
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), PagingData.empty())
}
