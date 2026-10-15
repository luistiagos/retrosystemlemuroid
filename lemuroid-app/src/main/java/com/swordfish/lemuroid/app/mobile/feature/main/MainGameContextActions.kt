package com.swordfish.lemuroid.app.mobile.feature.main

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContent
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AppShortcut
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.swordfish.lemuroid.R
import com.swordfish.lemuroid.app.mobile.shared.compose.ui.LemuroidBottomSheet
import com.swordfish.lemuroid.app.mobile.shared.compose.ui.LemuroidColors
import com.swordfish.lemuroid.app.mobile.shared.compose.ui.LemuroidGameImage
import com.swordfish.lemuroid.app.mobile.shared.compose.ui.LocalLemuroidTypography
import com.swordfish.lemuroid.app.mobile.shared.compose.ui.SheetMargin
import com.swordfish.lemuroid.app.mobile.shared.compose.ui.SystemChip
import com.swordfish.lemuroid.lib.library.db.entity.Game

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainGameContextActions(
    selectedGameState: MutableState<Game?>,
    shortcutSupported: Boolean,
    isGameDownloaded: Boolean = true,
    onGamePlay: (Game) -> Unit,
    onGameRestart: (Game) -> Unit,
    onFavoriteToggle: (Game, Boolean) -> Unit,
    onCreateShortcut: (Game) -> Unit,
    onDeleteRom: ((Game) -> Unit)? = null,
    onDeleteFromCatalog: ((Game) -> Unit)? = null,
) {
    val modalSheetState = rememberModalBottomSheetState(true)
    val selectedGame = selectedGameState.value

    LaunchedEffect(selectedGame) {
        if (selectedGame != null) {
            modalSheetState.show()
        } else {
            modalSheetState.hide()
        }
    }

    if (selectedGame != null) {
        LemuroidBottomSheet(
            sheetState = modalSheetState,
            onDismissRequest = { selectedGameState.value = null },
        ) {
            ContextActionContent(
                selectedGame = selectedGame,
                onGamePlay = onGamePlay,
                selectedGameState = selectedGameState,
                onGameRestart = onGameRestart,
                onFavoriteToggle = onFavoriteToggle,
                shortcutSupported = shortcutSupported,
                onCreateShortcut = onCreateShortcut,
                isGameDownloaded = isGameDownloaded,
                onDeleteRom = onDeleteRom,
                onDeleteFromCatalog = onDeleteFromCatalog,
            )
        }
    }
}

@Composable
private fun ContextActionContent(
    selectedGame: Game,
    onGamePlay: (Game) -> Unit,
    selectedGameState: MutableState<Game?>,
    onGameRestart: (Game) -> Unit,
    onFavoriteToggle: (Game, Boolean) -> Unit,
    shortcutSupported: Boolean,
    onCreateShortcut: (Game) -> Unit,
    isGameDownloaded: Boolean = true,
    onDeleteRom: ((Game) -> Unit)? = null,
    onDeleteFromCatalog: ((Game) -> Unit)? = null,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeContent.only(WindowInsetsSides.Bottom)),
    ) {
        ContextActionHeader(game = selectedGame)
        HorizontalDivider(color = LemuroidColors.Border, modifier = Modifier.padding(bottom = 4.dp))
        ContextActionEntry(
            label = stringResource(id = R.string.game_context_menu_resume),
            icon = Icons.Default.PlayArrow,
            onClick = {
                onGamePlay(selectedGame)
                selectedGameState.value = null
            },
        )
        ContextActionEntry(
            label = stringResource(id = R.string.game_context_menu_restart),
            icon = Icons.Default.RestartAlt,
            onClick = {
                onGameRestart(selectedGame)
                selectedGameState.value = null
            },
        )

        if (selectedGame.isFavorite) {
            ContextActionEntry(
                label = stringResource(id = R.string.game_context_menu_remove_from_favorites),
                icon = Icons.Default.FavoriteBorder,
                onClick = {
                    onFavoriteToggle(selectedGame, false)
                    selectedGameState.value = null
                },
            )
        } else {
            ContextActionEntry(
                label = stringResource(id = R.string.game_context_menu_add_to_favorites),
                icon = Icons.Default.Favorite,
                onClick = {
                    onFavoriteToggle(selectedGame, true)
                    selectedGameState.value = null
                },
            )
        }

        if (shortcutSupported) {
            ContextActionEntry(
                label = stringResource(id = R.string.game_context_menu_create_shortcut),
                icon = Icons.Default.AppShortcut,
                onClick = {
                    onCreateShortcut(selectedGame)
                    selectedGameState.value = null
                },
            )
        }

        // Frees the disk space but keeps the catalog entry, so the game can be downloaded
        // again later. Hidden when there is no downloaded file to delete.
        if (isGameDownloaded && onDeleteRom != null) {
            ContextActionEntry(
                label = stringResource(id = R.string.game_context_menu_delete_rom),
                icon = Icons.Default.Delete,
                destructive = true,
                onClick = {
                    onDeleteRom(selectedGame)
                    selectedGameState.value = null
                },
            )
        }

        // Drops the game from the catalog too. Always available — a placeholder the user
        // never wants to see again is exactly the case where this is useful.
        if (onDeleteFromCatalog != null) {
            ContextActionEntry(
                label = stringResource(id = R.string.game_context_menu_delete_from_catalog),
                icon = Icons.Default.DeleteForever,
                destructive = true,
                onClick = {
                    onDeleteFromCatalog(selectedGame)
                    selectedGameState.value = null
                },
            )
        }
    }
}

@Composable
private fun ContextActionHeader(game: Game) {
    Row(
        modifier = Modifier.padding(start = SheetMargin, end = SheetMargin, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier
                    .width(HEADER_COVER_WIDTH)
                    .clip(RoundedCornerShape(6.dp)),
        ) {
            LemuroidGameImage(game = game, compact = true)
        }
        Column(
            modifier =
                Modifier
                    .weight(1f)
                    .padding(start = 12.dp),
        ) {
            Text(
                text = game.title,
                style = LocalLemuroidTypography.current.section,
                color = LemuroidColors.Text,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            SystemChip(game.systemId, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

/**
 * One action of the menu. D-pad focus paints the accent pill behind the whole line, like the list
 * rows. [destructive] actions (deleting) take the error text colour.
 */
@Composable
private fun ContextActionEntry(
    modifier: Modifier = Modifier,
    label: String,
    icon: ImageVector,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val tint = if (destructive) MaterialTheme.colorScheme.error else LemuroidColors.Text
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .onFocusChanged { focused = it.hasFocus }
                .background(if (focused) LemuroidColors.AccentPill else Color.Transparent)
                .clickable(onClick = onClick)
                .height(52.dp)
                .padding(horizontal = SheetMargin),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (destructive) tint else LemuroidColors.TextSupport,
        )
        Text(
            modifier = Modifier.padding(start = 16.dp),
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = tint,
        )
    }
}

private val HEADER_COVER_WIDTH = 48.dp

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun FakeScrim(modalSheetState: SheetState) {
    AnimatedVisibility(
        visible = modalSheetState.targetValue != SheetValue.Hidden,
        enter = fadeIn(),
        exit = fadeOut(),
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(BottomSheetDefaults.ScrimColor),
        )
    }
}
