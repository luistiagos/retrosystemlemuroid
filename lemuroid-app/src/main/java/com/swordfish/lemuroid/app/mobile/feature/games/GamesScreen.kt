package com.swordfish.lemuroid.app.mobile.feature.games

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import com.swordfish.lemuroid.R
import com.swordfish.lemuroid.app.mobile.shared.compose.ui.AmbientBackdrop
import com.swordfish.lemuroid.app.mobile.shared.compose.ui.CoverGridCells
import com.swordfish.lemuroid.app.mobile.shared.compose.ui.LemuroidColors
import com.swordfish.lemuroid.app.mobile.shared.compose.ui.LemuroidEmptyView
import com.swordfish.lemuroid.app.mobile.shared.compose.ui.LemuroidGameGridItem
import com.swordfish.lemuroid.app.mobile.shared.compose.ui.LemuroidGameListRow
import com.swordfish.lemuroid.app.mobile.shared.compose.ui.LemuroidSystemImage
import com.swordfish.lemuroid.app.mobile.shared.compose.ui.LocalDimens
import com.swordfish.lemuroid.app.mobile.shared.compose.ui.LocalLemuroidTypography
import com.swordfish.lemuroid.app.mobile.shared.compose.ui.PillShape
import com.swordfish.lemuroid.app.mobile.shared.compose.ui.SystemPalette
import com.swordfish.lemuroid.app.mobile.shared.compose.ui.rememberCoverSaveState
import com.swordfish.lemuroid.app.shared.roms.SaveQueueEntry
import com.swordfish.lemuroid.app.utils.android.settings.booleanPreferenceState
import com.swordfish.lemuroid.lib.library.MetaSystemID
import com.swordfish.lemuroid.lib.library.db.entity.Game
import kotlinx.coroutines.launch

/**
 * Catalog of one system.
 *
 * @param metaSystem shows the system header (art, name, number of games); null = only the sort chips.
 * @param saveQueueEntries save queue by `Game.downloadKey` (`SaveQueueViewModel.entriesByKey`), passed
 * as `State` so that only the item whose state changed recomposes.
 */
@Composable
fun GamesScreen(
    modifier: Modifier = Modifier,
    viewModel: GamesViewModel,
    downloadedGameKeys: Set<String> = emptySet(),
    titlesWithVariants: Set<String> = emptySet(),
    metaSystem: MetaSystemID? = null,
    saveQueueEntries: State<Map<String, SaveQueueEntry>>? = null,
    onGameClick: (Game) -> Unit,
    onGameLongClick: (Game) -> Unit,
    onGameFavoriteToggle: (Game, Boolean) -> Unit,
) {
    val games = viewModel.games.collectAsLazyPagingItems()
    val sortOrder by viewModel.sortOrder.collectAsState()
    val totalGames by viewModel.totalGames.collectAsState()
    val coroutineScope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    val dimens = LocalDimens.current

    val isGridView =
        booleanPreferenceState(
            id = R.string.pref_key_catalog_layout_grid,
            default = true,
        ).value

    val isLoading = games.loadState.refresh is LoadState.Loading
    if (!isLoading && games.itemCount == 0) {
        LemuroidEmptyView()
        return
    }

    if (isGridView) {
        val gridState = rememberLazyGridState()

        DisposableEffect(lifecycleOwner) {
            val observer =
                LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_RESUME) {
                        coroutineScope.launch { gridState.scrollToItem(0) }
                    }
                }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }

        LazyVerticalGrid(
            columns = remember(dimens.gridCoverMinWidth) { CoverGridCells(dimens.gridCoverMinWidth) },
            state = gridState,
            modifier = modifier.fillMaxSize(),
            // No top padding: the header is drawn edge to edge from the top.
            contentPadding =
                PaddingValues(
                    start = dimens.screenMargin,
                    end = dimens.screenMargin,
                    bottom = dimens.screenMargin,
                ),
            verticalArrangement = Arrangement.spacedBy(dimens.cardSpacing),
            horizontalArrangement = Arrangement.spacedBy(dimens.cardSpacing),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "sort_header") {
                GamesCatalogHeader(
                    metaSystem = metaSystem,
                    gameCount = totalGames,
                    sortOrder = sortOrder,
                    onSortChange = { viewModel.setSortOrder(it) },
                    modifier = Modifier.bleedHorizontally(dimens.screenMargin),
                )
            }

            items(games.itemCount, key = { games[it]?.id?.let { id -> "id_$id" } ?: "idx_$it" }) { index ->
                val game = games[index] ?: return@items
                val variantKey = "${game.systemId}/${game.title}"
                val isDownloaded = downloadedGameKeys.contains(game.downloadKey)

                LemuroidGameGridItem(
                    game = game,
                    isDownloaded = isDownloaded,
                    hasVariants = variantKey in titlesWithVariants,
                    saveState = rememberCoverSaveState(game.downloadKey, isDownloaded, saveQueueEntries),
                    onClick = { onGameClick(game) },
                    onLongClick = { onGameLongClick(game) },
                )
            }
        }
    } else {
        val listState = rememberLazyListState()

        DisposableEffect(lifecycleOwner) {
            val observer =
                LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_RESUME) {
                        coroutineScope.launch { listState.scrollToItem(0) }
                    }
                }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }

        LazyColumn(state = listState, modifier = modifier.fillMaxSize()) {
            item(key = "sort_header") {
                GamesCatalogHeader(
                    metaSystem = metaSystem,
                    gameCount = totalGames,
                    sortOrder = sortOrder,
                    onSortChange = { viewModel.setSortOrder(it) },
                )
            }

            items(games.itemCount, key = { games[it]?.id?.let { id -> "id_$id" } ?: "idx_$it" }) { index ->
                val game = games[index] ?: return@items
                val variantKey = "${game.systemId}/${game.title}"
                val isDownloaded = downloadedGameKeys.contains(game.downloadKey)

                LemuroidGameListRow(
                    game = game,
                    isDownloaded = isDownloaded,
                    hasVariants = variantKey in titlesWithVariants,
                    saveState = rememberCoverSaveState(game.downloadKey, isDownloaded, saveQueueEntries),
                    onClick = { onGameClick(game) },
                    onLongClick = { onGameLongClick(game) },
                    onFavoriteToggle = { isFavorite -> onGameFavoriteToggle(game, isFavorite) },
                )
            }
        }
    }
}

/**
 * System art over the ambient backdrop, name, number of games and the sort chips (on the same
 * line when landscape, where height is short).
 */
@Composable
private fun GamesCatalogHeader(
    metaSystem: MetaSystemID?,
    gameCount: Int?,
    sortOrder: GameSortOrder,
    onSortChange: (GameSortOrder) -> Unit,
    modifier: Modifier = Modifier,
) {
    val dimens = LocalDimens.current

    if (metaSystem == null) {
        GamesSortChips(
            sortOrder = sortOrder,
            onSortChange = onSortChange,
            modifier =
                modifier
                    .fillMaxWidth()
                    .padding(horizontal = dimens.screenMargin, vertical = 8.dp),
        )
        return
    }

    val systemColor = remember(metaSystem) { SystemPalette.color(metaSystem) }
    AmbientBackdrop(
        model = metaSystem.imageResId,
        systemColor = systemColor,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(start = dimens.screenMargin, end = dimens.screenMargin, top = 16.dp, bottom = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LemuroidSystemImage(
                    metaSystem = metaSystem,
                    modifier =
                        Modifier
                            .width(if (dimens.isLandscape) 88.dp else 104.dp)
                            .clip(RoundedCornerShape(dimens.cardCornerRadius)),
                )
                Column(
                    modifier =
                        Modifier
                            .weight(1f)
                            .padding(start = 12.dp, end = 8.dp),
                ) {
                    Text(
                        text = stringResource(metaSystem.titleResId),
                        style = LocalLemuroidTypography.current.screenTitle,
                        color = LemuroidColors.Text,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    // Total with variants (GamesViewModel.totalGames), as in every game count of the app.
                    if (gameCount != null && gameCount > 0) {
                        Text(
                            text = stringResource(R.string.system_grid_details, gameCount.toString()),
                            style = LocalLemuroidTypography.current.support,
                            color = LemuroidColors.TextSupportOnBackdrop,
                            maxLines = 1,
                        )
                    }
                }
                if (dimens.isLandscape) {
                    GamesSortChips(sortOrder = sortOrder, onSortChange = onSortChange)
                }
            }
            if (!dimens.isLandscape) {
                GamesSortChips(
                    sortOrder = sortOrder,
                    onSortChange = onSortChange,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun GamesSortChips(
    sortOrder: GameSortOrder,
    onSortChange: (GameSortOrder) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilterChip(
            selected = sortOrder == GameSortOrder.POPULARITY,
            onClick = { onSortChange(GameSortOrder.POPULARITY) },
            label = { Text(stringResource(R.string.sort_popularity)) },
            shape = PillShape,
        )
        FilterChip(
            selected = sortOrder == GameSortOrder.ALPHABETICAL,
            onClick = { onSortChange(GameSortOrder.ALPHABETICAL) },
            label = { Text(stringResource(R.string.sort_alphabetical)) },
            shape = PillShape,
        )
    }
}

/** Grid item wider than its slot by [margin] on each side: draws over the grid's content padding. */
private fun Modifier.bleedHorizontally(margin: Dp) =
    layout { measurable, constraints ->
        val bleed = margin.roundToPx()
        val placeable =
            measurable.measure(
                constraints.copy(
                    minWidth = constraints.minWidth + 2 * bleed,
                    maxWidth = constraints.maxWidth + 2 * bleed,
                ),
            )
        layout(constraints.maxWidth, placeable.height) { placeable.place(-bleed, 0) }
    }
