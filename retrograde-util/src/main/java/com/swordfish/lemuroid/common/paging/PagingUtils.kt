package com.swordfish.lemuroid.common.paging

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.cachedIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow

fun <T : Any> buildFlowPaging(
    pageSize: Int,
    coroutineScope: CoroutineScope,
    source: () -> PagingSource<Int, T>,
): Flow<PagingData<T>> {
    // NOTE: do NOT set PagingConfig.maxSize here. With placeholders enabled (default) and
    // the position-dependent composite keys the catalog uses (loaded item → "id_<id>",
    // placeholder → "idx_<index>"), dropping pages once a list exceeds maxSize flips those
    // keys back and forth as pages are dropped/reloaded — making the list visibly reshuffle
    // ("dancing") on any system with more items than maxSize. The memory maxSize would save
    // here is negligible: Game rows are lightweight, and the heavy part (cover bitmaps) is
    // already bounded by Coil's own memory cache, independent of Paging. See
    // docs/bugs/done/2026-06-19-catalogo-reordena-constantemente.md.
    return Pager(PagingConfig(pageSize), pagingSourceFactory = source)
        .flow
        .cachedIn(coroutineScope)
}
