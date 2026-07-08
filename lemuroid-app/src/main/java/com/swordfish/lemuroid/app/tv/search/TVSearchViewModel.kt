package com.swordfish.lemuroid.app.tv.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import com.swordfish.lemuroid.common.paging.buildFlowPaging
import com.swordfish.lemuroid.lib.library.db.RetrogradeDatabase
import com.swordfish.lemuroid.lib.library.db.entity.Game
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlin.time.Duration.Companion.milliseconds

class TVSearchViewModel(private val retrogradeDb: RetrogradeDatabase) : ViewModel() {
    class Factory(val retrogradeDb: RetrogradeDatabase) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return TVSearchViewModel(retrogradeDb) as T
        }
    }

    val queryString = MutableStateFlow("")

    // Debounce so each keystroke doesn't tear down and rebuild the FTS
    // PagingSource (same slowdown that hit the mobile search screen).
    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    val searchResults: Flow<PagingData<Game>> =
        queryString
            .debounce(400.milliseconds)
            .flatMapLatest {
                buildFlowPaging(20, viewModelScope) { retrogradeDb.gameSearchDao().search(it) }
            }
}
