package com.swordfish.lemuroid.app.mobile.feature.installed

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.swordfish.lemuroid.R
import com.swordfish.lemuroid.app.mobile.shared.compose.ui.CoverGridCells
import com.swordfish.lemuroid.app.mobile.shared.compose.ui.LemuroidColors
import com.swordfish.lemuroid.app.mobile.shared.compose.ui.LemuroidEmptyView
import com.swordfish.lemuroid.app.mobile.shared.compose.ui.LocalDimens
import com.swordfish.lemuroid.app.mobile.shared.compose.ui.LocalLemuroidTypography
import com.swordfish.lemuroid.app.mobile.shared.compose.ui.PillShape
import com.swordfish.lemuroid.lib.library.db.entity.Game

@Composable
fun InstalledGamesScreen(
    modifier: Modifier = Modifier,
    viewModel: InstalledGamesViewModel,
    onGameClick: (InstalledGameGroupUiModel) -> Unit,
    onGameLongClick: (Game) -> Unit,
) {
    val items by viewModel.installedGames.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val dimens = LocalDimens.current

    Column(modifier = modifier.fillMaxSize()) {
        // Instant search on the tab itself (title, file name or system name).
        InstalledSearchField(
            query = searchQuery,
            onQueryChange = { viewModel.onSearchQueryChanged(it) },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = dimens.screenMargin, vertical = 8.dp),
        )

        if (items.isEmpty()) {
            if (searchQuery.isNotEmpty()) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.installed_no_search_results, searchQuery),
                        style = LocalLemuroidTypography.current.section,
                        color = LemuroidColors.TextSupport,
                        textAlign = TextAlign.Center,
                    )
                }
            } else {
                LemuroidEmptyView()
            }
        } else {
            LazyVerticalGrid(
                modifier = Modifier.fillMaxSize(),
                columns = remember(dimens.gridCoverMinWidth) { CoverGridCells(dimens.gridCoverMinWidth) },
                contentPadding =
                    PaddingValues(
                        start = dimens.screenMargin,
                        end = dimens.screenMargin,
                        top = 8.dp,
                        bottom = dimens.screenMargin,
                    ),
                horizontalArrangement = Arrangement.spacedBy(dimens.cardSpacing),
                verticalArrangement = Arrangement.spacedBy(dimens.cardSpacing),
            ) {
                items(
                    items = items,
                    key = { "${it.group.game.systemId}_${it.group.game.title}_${it.group.game.id}" },
                ) { item ->
                    LemuroidInstalledGameCard(
                        item = item,
                        onClick = { onGameClick(item) },
                        onLongClick = { onGameLongClick(item.group.game) },
                    )
                }
            }
        }
    }
}

/** Pill search field on the surface colour (amber when focused), no outline. */
@Composable
private fun InstalledSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusManager = LocalFocusManager.current
    TextField(
        value = query,
        onValueChange = onQueryChange,
        // A single-line field eats the D-pad arrows to move its cursor, so a TV box could never reach
        // the grid below: down/up always leave the field, left leaves it when there is no text.
        modifier =
            modifier.onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val direction =
                    when (event.key) {
                        Key.DirectionDown -> FocusDirection.Down
                        Key.DirectionUp -> FocusDirection.Up
                        Key.DirectionLeft -> if (query.isEmpty()) FocusDirection.Left else null
                        else -> null
                    }
                direction != null && focusManager.moveFocus(direction)
            },
        placeholder = { Text(text = stringResource(R.string.installed_search_hint)) },
        leadingIcon = {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = null,
                tint = LemuroidColors.TextSupport,
            )
        },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(
                        imageVector = Icons.Default.Clear,
                        contentDescription = stringResource(R.string.clear),
                        tint = LemuroidColors.TextSupport,
                    )
                }
            }
        },
        singleLine = true,
        shape = PillShape,
        colors =
            TextFieldDefaults.colors(
                // Focus (typing or D-pad) shows as the amber pill of the selected items.
                focusedContainerColor = LemuroidColors.AccentPillOpaque,
                unfocusedContainerColor = LemuroidColors.Surface,
                focusedTextColor = LemuroidColors.Text,
                unfocusedTextColor = LemuroidColors.Text,
                focusedPlaceholderColor = LemuroidColors.TextSupport,
                unfocusedPlaceholderColor = LemuroidColors.TextSupport,
                cursorColor = LemuroidColors.Accent,
                // The pill is the outline: no underline.
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
            ),
    )
}
