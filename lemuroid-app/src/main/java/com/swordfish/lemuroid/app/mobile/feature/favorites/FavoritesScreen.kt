package com.swordfish.lemuroid.app.mobile.feature.favorites

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import com.swordfish.lemuroid.app.mobile.shared.compose.ui.CoverGridCells
import com.swordfish.lemuroid.app.mobile.shared.compose.ui.LemuroidEmptyView
import com.swordfish.lemuroid.app.mobile.shared.compose.ui.LemuroidGameGridItem
import com.swordfish.lemuroid.app.mobile.shared.compose.ui.LocalDimens
import com.swordfish.lemuroid.app.mobile.shared.compose.ui.rememberCoverSaveState
import com.swordfish.lemuroid.app.shared.roms.SaveQueueEntry
import com.swordfish.lemuroid.lib.library.db.entity.Game

/**
 * Favourite games of every system, in the cover grid of the catalog with the system chip.
 *
 * @param titlesWithVariants `"systemId/title"` keys with more than one version (variants badge).
 * @param saveQueueEntries save queue by `Game.downloadKey` (`SaveQueueViewModel.entriesByKey`), passed
 * as `State` so that only the item whose state changed recomposes.
 */
@Composable
fun FavoritesScreen(
    modifier: Modifier = Modifier,
    viewModel: FavoritesViewModel,
    downloadedGameKeys: Set<String> = emptySet(),
    titlesWithVariants: Set<String> = emptySet(),
    saveQueueEntries: State<Map<String, SaveQueueEntry>>? = null,
    onGameClick: (Game) -> Unit,
    onGameLongClick: (Game) -> Unit,
) {
    val games = viewModel.favorites.collectAsLazyPagingItems()
    val dimens = LocalDimens.current

    // Only once the first page arrived: before it, itemCount is 0 even with favourites.
    val isLoading = games.loadState.refresh is LoadState.Loading
    if (!isLoading && games.itemCount == 0) {
        LemuroidEmptyView(icon = Icons.Outlined.FavoriteBorder)
        return
    }

    LazyVerticalGrid(
        modifier = modifier.fillMaxSize(),
        columns = remember(dimens.gridCoverMinWidth) { CoverGridCells(dimens.gridCoverMinWidth) },
        contentPadding = PaddingValues(dimens.screenMargin),
        horizontalArrangement = Arrangement.spacedBy(dimens.cardSpacing),
        verticalArrangement = Arrangement.spacedBy(dimens.cardSpacing),
    ) {
        items(games.itemCount, key = { games[it]?.id?.let { id -> "id_$id" } ?: "idx_$it" }) { index ->
            val game = games[index] ?: return@items
            val isDownloaded = downloadedGameKeys.contains(game.downloadKey)

            LemuroidGameGridItem(
                game = game,
                isDownloaded = isDownloaded,
                hasVariants = "${game.systemId}/${game.title}" in titlesWithVariants,
                saveState = rememberCoverSaveState(game.downloadKey, isDownloaded, saveQueueEntries),
                showSystemChip = true,
                onClick = { onGameClick(game) },
                onLongClick = { onGameLongClick(game) },
            )
        }
    }
}
