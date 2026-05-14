package com.swordfish.lemuroid.app.mobile.feature.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import com.swordfish.lemuroid.common.paging.buildFlowPaging
import com.swordfish.lemuroid.lib.library.db.RetrogradeDatabase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn

@OptIn(ExperimentalCoroutinesApi::class)
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

    val searchResults =
        combine(queryString, systemIdsFlow) { query, systemIds -> query to systemIds }
            .filter { (query, _) -> query.length >= 3 }
            .flatMapLatest { (query, systemIds) ->
                buildFlowPaging(30, viewModelScope) {
                    retrogradeDb.gameSearchDao().search(query, systemIds)
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PagingData.empty())
}
