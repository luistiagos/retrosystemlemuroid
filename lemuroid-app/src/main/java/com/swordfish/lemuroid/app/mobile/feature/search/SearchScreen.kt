package com.swordfish.lemuroid.app.mobile.feature.search

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import com.swordfish.lemuroid.R
import com.swordfish.lemuroid.app.mobile.shared.compose.ui.LemuroidEmptyView
import com.swordfish.lemuroid.app.mobile.shared.compose.ui.LemuroidGameListRow
import com.swordfish.lemuroid.app.mobile.shared.compose.ui.LocalDimens
import com.swordfish.lemuroid.app.mobile.shared.compose.ui.PillShape
import com.swordfish.lemuroid.app.mobile.shared.compose.ui.rememberCoverSaveState
import com.swordfish.lemuroid.app.shared.roms.SaveQueueEntry
import com.swordfish.lemuroid.lib.library.GameSystem
import com.swordfish.lemuroid.lib.library.db.entity.Game
import com.swordfish.lemuroid.lib.library.metaSystemID

/**
 * @param titlesWithVariants `"systemId/title"` keys with more than one version (variants badge).
 * @param saveQueueEntries save queue by `Game.downloadKey` (`SaveQueueViewModel.entriesByKey`), passed
 * as `State` so that only the row whose state changed recomposes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    modifier: Modifier = Modifier,
    viewModel: SearchViewModel,
    searchQuery: String,
    systemIds: List<String>? = null,
    systemName: String? = null,
    downloadedGameKeys: Set<String> = emptySet(),
    titlesWithVariants: Set<String> = emptySet(),
    saveQueueEntries: State<Map<String, SaveQueueEntry>>? = null,
    onGameClick: (Game) -> Unit,
    onGameLongClick: (Game) -> Unit,
    onGameFavoriteToggle: (Game, Boolean) -> Unit,
    onResetSearchQuery: () -> Unit,
    onClearSystemScope: () -> Unit = {},
) {
    val searchGames = viewModel.searchResults.collectAsLazyPagingItems()
    val isOnlyInstalled by viewModel.onlyInstalled.collectAsState()

    val hasSystemScope = !systemIds.isNullOrEmpty()
    val activeSystemName =
        systemName
            ?: systemIds?.firstOrNull()?.let { id ->
                GameSystem.findByIdOrNull(id)?.metaSystemID()?.titleResId?.let { stringResource(it) }
            }

    val clearSystemScopeAction = {
        viewModel.setSystemIds(null)
        onClearSystemScope()
    }

    LaunchedEffect(Unit) {
        onResetSearchQuery()
    }

    LaunchedEffect(key1 = searchQuery) {
        viewModel.queryString.value = searchQuery
    }

    LaunchedEffect(key1 = systemIds) {
        viewModel.setSystemIds(systemIds)
    }

    // State is derived from query length, the debounce, and paging state:
    //  • fewer than 3 chars      → Idle    (prompt the user to keep typing)
    //  • debounce still pending  → Loading (keep spinner while typing settles)
    //  • paging loading          → Loading (show spinner)
    //  • paging done             → Ready   (results or empty message)
    // isSearchPending covers the debounce window so we never flash a false
    // "no results" state between a keystroke and the query firing.
    val isSearchPending by viewModel.isSearchPending.collectAsState()
    val isPageLoading = searchGames.loadState.refresh is LoadState.Loading
    val displayState =
        when {
            searchQuery.length < 3 -> SearchViewModel.UIState.Idle
            isSearchPending || isPageLoading -> SearchViewModel.UIState.Loading
            else -> SearchViewModel.UIState.Ready
        }

    Column(modifier = modifier.fillMaxSize()) {
        if (isOnlyInstalled || (hasSystemScope && activeSystemName != null)) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = LocalDimens.current.screenMargin, vertical = 6.dp)
                        .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (hasSystemScope && activeSystemName != null) {
                    SearchScopeChip(label = activeSystemName, onClear = clearSystemScopeAction)
                }

                if (isOnlyInstalled) {
                    SearchScopeChip(
                        label = stringResource(R.string.search_scope_installed),
                        onClear = { viewModel.setOnlyInstalled(false) },
                    )
                }
            }
        }

        AnimatedContent(
            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            targetState = displayState,
            label = "SearchContent",
            transitionSpec = { fadeIn() togetherWith fadeOut() },
        ) { state ->
            when {
                state == SearchViewModel.UIState.Idle -> {
                    LemuroidEmptyView(
                        modifier = Modifier.fillMaxSize(),
                        text = stringResource(R.string.game_page_search_suggestion),
                        icon = Icons.Outlined.Search,
                    )
                }

                state == SearchViewModel.UIState.Loading -> {
                    SearchLoadingView(Modifier.fillMaxSize())
                }

                state == SearchViewModel.UIState.Ready && searchGames.itemCount == 0 -> {
                    if (hasSystemScope && activeSystemName != null) {
                        LemuroidEmptyView(
                            modifier = Modifier.fillMaxSize(),
                            text = stringResource(R.string.search_no_games_in_system, activeSystemName),
                            icon = Icons.Outlined.SearchOff,
                            actionText = stringResource(R.string.search_all_systems),
                            onActionClick = clearSystemScopeAction,
                        )
                    } else {
                        LemuroidEmptyView(
                            modifier = Modifier.fillMaxSize(),
                            text = stringResource(id = R.string.empty_view_default),
                            icon = Icons.Outlined.SearchOff,
                        )
                    }
                }

                else -> {
                    SearchResultsView(
                        Modifier.fillMaxSize(),
                        searchGames,
                        downloadedGameKeys,
                        titlesWithVariants,
                        saveQueueEntries,
                        onGameClick,
                        onGameLongClick,
                        onGameFavoriteToggle,
                    )
                }
            }
        }
    }
}

/** Active scope of the search, in a pill; tapping it clears the scope. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchScopeChip(
    label: String,
    onClear: () -> Unit,
) {
    FilterChip(
        selected = true,
        onClick = onClear,
        label = { Text(label) },
        shape = PillShape,
        trailingIcon = {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = stringResource(R.string.clear),
                modifier = Modifier.size(16.dp),
            )
        },
    )
}

@Composable
private fun SearchResultsView(
    modifier: Modifier,
    games: LazyPagingItems<Game>,
    downloadedGameKeys: Set<String>,
    titlesWithVariants: Set<String>,
    saveQueueEntries: State<Map<String, SaveQueueEntry>>?,
    onGameClick: (Game) -> Unit,
    onGameLongClick: (Game) -> Unit,
    onGameFavoriteToggle: (Game, Boolean) -> Unit,
) {
    LazyColumn(modifier = modifier) {
        // Collision-safe keys: prefix loaded-item ids and placeholder indices into
        // distinct namespaces so a game id can never equal a placeholder's index
        // (which would crash LazyColumn with a duplicate-key error). Matches
        // FavoritesScreen's pattern and pairs with the new Paging maxSize.
        items(games.itemCount, key = { games[it]?.id?.let { id -> "id_$id" } ?: "idx_$it" }) { index ->
            val game = games[index] ?: return@items
            val isDownloaded = downloadedGameKeys.contains(game.downloadKey)

            LemuroidGameListRow(
                game = game,
                isDownloaded = isDownloaded,
                hasVariants = "${game.systemId}/${game.title}" in titlesWithVariants,
                saveState = rememberCoverSaveState(game.downloadKey, isDownloaded, saveQueueEntries),
                onClick = { onGameClick(game) },
                onLongClick = { onGameLongClick(game) },
                onFavoriteToggle = { isFavorite ->
                    onGameFavoriteToggle(game, isFavorite)
                },
            )
        }
    }
}

@Composable
private fun SearchLoadingView(modifier: Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator()
    }
}
