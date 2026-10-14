package com.swordfish.lemuroid.app.mobile.feature.home

import android.net.Uri
import androidx.annotation.StringRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.swordfish.lemuroid.R
import com.swordfish.lemuroid.app.mobile.feature.installed.InstalledGameGroupUiModel
import com.swordfish.lemuroid.app.mobile.shared.compose.ui.AmbientBackdrop
import com.swordfish.lemuroid.app.mobile.shared.compose.ui.CoverSaveState
import com.swordfish.lemuroid.app.mobile.shared.compose.ui.LemuroidColors
import com.swordfish.lemuroid.app.mobile.shared.compose.ui.LemuroidGameImage
import com.swordfish.lemuroid.app.mobile.shared.compose.ui.LemuroidSystemImage
import com.swordfish.lemuroid.app.mobile.shared.compose.ui.LocalDimens
import com.swordfish.lemuroid.app.mobile.shared.compose.ui.LocalLemuroidTypography
import com.swordfish.lemuroid.app.mobile.shared.compose.ui.PillShape
import com.swordfish.lemuroid.app.mobile.shared.compose.ui.SaveStateIndicator
import com.swordfish.lemuroid.app.mobile.shared.compose.ui.SaveStateOverlay
import com.swordfish.lemuroid.app.mobile.shared.compose.ui.SectionHeader
import com.swordfish.lemuroid.app.mobile.shared.compose.ui.SystemPalette
import com.swordfish.lemuroid.app.mobile.shared.compose.ui.effects.focusHighlight
import com.swordfish.lemuroid.app.mobile.shared.compose.ui.rememberCoverSaveState
import com.swordfish.lemuroid.app.shared.roms.SaveQueueEntry
import com.swordfish.lemuroid.app.shared.roms.SaveQueueState
import com.swordfish.lemuroid.app.shared.systems.MetaSystemInfo
import com.swordfish.lemuroid.lib.library.GameSystem
import com.swordfish.lemuroid.lib.library.db.entity.Game
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Everything the Home layouts draw. The lists come from [HomeViewModel] (and, for the carousel and
 * the saved games, from the extra sources the route passes in); the callbacks are the ones of today.
 */
@Immutable
internal class HomeLayoutData(
    val recentGames: List<Game>,
    val favoritesGames: List<Game>,
    val discoveryGames: List<Game>,
    val savedGames: List<InstalledGameGroupUiModel>,
    /** Null = no carousel (portrait, or the route did not provide the systems). */
    val metaSystems: List<MetaSystemInfo>?,
    val downloadedGameKeys: Set<String>,
    /** Kept as `State`: read per item, see [rememberCoverSaveState]. */
    val saveQueueEntries: State<Map<String, SaveQueueEntry>>?,
    val onGameClick: (Game) -> Unit,
    val onGameLongClick: (Game) -> Unit,
    /** Null = no "Saved games" row. */
    val onSavedGameClick: ((InstalledGameGroupUiModel) -> Unit)?,
    val onShowAllSavedGames: (() -> Unit)?,
    val onSystemClick: (MetaSystemInfo) -> Unit,
    val onOpenSaveQueue: () -> Unit,
) {
    /** The featured game: the last one played or, with no recents, the first of Discover. */
    val heroGame: Game? = recentGames.firstOrNull() ?: discoveryGames.firstOrNull()
    val heroFromRecents: Boolean = recentGames.isNotEmpty()
}

/** What the landscape header shows: a game (featured or focused) or a focused system. */
private sealed interface Featured {
    /**
     * @param labelId line above the title: "Continue playing" or the name of the focused row.
     * @param onDevice the game is probably saved already (recents, saved games): first guess of
     * the button label until [HeroButton] checks the file.
     */
    data class OfGame(
        val game: Game,
        @StringRes val labelId: Int,
        val onDevice: Boolean,
    ) : Featured

    data class OfSystem(val system: MetaSystemInfo) : Featured
}

// region Portrait

/**
 * Home held upright (direction A): notices, featured game, "Saving now" strip, then the rows Saved
 * games, Recents, Favorites and Discover.
 */
@Composable
internal fun HomePortrait(
    data: HomeLayoutData,
    notices: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dimens = LocalDimens.current
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = dimens.screenMargin),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "notices") { notices() }

        data.heroGame?.let { game ->
            item(key = "hero") {
                PortraitHero(
                    game = game,
                    labelId = heroLabelId(data.heroFromRecents),
                    onDevice = data.heroFromRecents,
                    data = data,
                )
            }
        }

        if (data.saveQueueEntries != null) {
            item(key = "saving_strip") {
                SavingStrip(
                    entries = data.saveQueueEntries,
                    onClick = data.onOpenSaveQueue,
                    modifier = Modifier.padding(horizontal = dimens.screenMargin),
                )
            }
        }

        savedGamesRow(data, showTitles = true)
        gamesRow("recent", R.string.recent, data.recentGames, data, showTitles = true)
        gamesRow("favorites", R.string.favorites, data.favoritesGames, data, showTitles = true)
        gamesRow("discover", R.string.discover, data.discoveryGames, data, showTitles = true)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PortraitHero(
    game: Game,
    @StringRes labelId: Int,
    onDevice: Boolean,
    data: HomeLayoutData,
) {
    val dimens = LocalDimens.current
    val shape = RoundedCornerShape(dimens.cardCornerRadius)
    val saveState = rememberGameSaveState(game, data)

    AmbientBackdrop(
        model = game.coverFrontUrl,
        systemColor = SystemPalette.colorForSystemId(game.systemId),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = dimens.screenMargin, vertical = 20.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Box(
                modifier =
                    Modifier
                        .width(HERO_COVER_WIDTH)
                        .focusHighlight()
                        .clip(shape)
                        .combinedClickable(
                            onClick = { data.onGameClick(game) },
                            onLongClick = { data.onGameLongClick(game) },
                        ),
            ) {
                LemuroidGameImage(game = game, modifier = Modifier.fillMaxWidth())
                SaveStateOverlay(saveState)
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                HeroLabel(labelId)
                Text(
                    text = game.title,
                    style = LocalLemuroidTypography.current.heroTitle,
                    color = LemuroidColors.Text,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
                )
                SystemChip(game.systemId)
                Spacer(modifier = Modifier.height(14.dp))
                HeroButton(
                    game = game,
                    onDevice = onDevice,
                    saveState = saveState,
                    onClick = { data.onGameClick(game) },
                )
            }
        }
    }
}

/**
 * "Saving now": the game being saved (or the next one waiting) with its progress, and how many
 * more are queued. Opens the same queue sheet as the top bar. Draws nothing without an active queue.
 */
@Composable
private fun SavingStrip(
    entries: State<Map<String, SaveQueueEntry>>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Read here, not in the column: the progress ticks several times per second.
    val active = entries.value.values.filter { it.state in ACTIVE_QUEUE_STATES }
    val current =
        active.firstOrNull { it.state == SaveQueueState.SAVING }
            ?: active.firstOrNull()
            ?: return
    val state = CoverSaveState.of(current, isDownloaded = false)
    val label =
        when (state) {
            is CoverSaveState.Saving -> stringResource(R.string.save_queue_saving_progress, state.percent)
            CoverSaveState.Paused -> stringResource(R.string.save_queue_paused)
            else -> stringResource(R.string.save_queue_waiting)
        }

    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .focusHighlight(cornerRadius = 12.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(LemuroidColors.Surface)
                .clickable(role = Role.Button, onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (state is CoverSaveState.Saving) {
            // The percentage is already in the label: the ring alone.
            CircularProgressIndicator(
                progress = { state.percent / 100f },
                modifier = Modifier.size(20.dp),
                color = LemuroidColors.Accent,
                trackColor = LemuroidColors.Text.copy(alpha = 0.25f),
                strokeWidth = 2.dp,
            )
        } else {
            SaveStateIndicator(state)
        }
        Column(
            modifier =
                Modifier
                    .weight(1f)
                    .padding(start = 12.dp),
        ) {
            Text(
                text = label,
                style = LocalLemuroidTypography.current.support,
                color = LemuroidColors.Accent,
                maxLines = 1,
            )
            Text(
                text = current.title,
                style = LocalLemuroidTypography.current.cardTitle,
                color = LemuroidColors.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (active.size > 1) {
            Text(
                text = "+${active.size - 1}",
                style = LocalLemuroidTypography.current.support,
                fontWeight = FontWeight.Bold,
                color = LemuroidColors.TextSupport,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

// endregion

// region Landscape

/**
 * Home with the window wider than tall: the TV layout. Ambient background over the whole screen,
 * the featured (or focused) item at the top, then rows of covers only: Recents, Systems, Saved
 * games, Favorites, Discover.
 *
 * With the D-pad the header follows the focus at once and the background follows it after
 * [BACKDROP_DELAY_MS] standing still (the same rule as the TV, delivery 3.3).
 */
@Composable
internal fun HomeLandscape(
    data: HomeLayoutData,
    notices: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dimens = LocalDimens.current
    val default = data.heroGame?.let { Featured.OfGame(it, heroLabelId(data.heroFromRecents), data.heroFromRecents) }
    var focused by remember { mutableStateOf<Featured?>(null) }
    val featured = focused ?: default

    var backdrop by remember { mutableStateOf(featured) }
    LaunchedEffect(featured) {
        if (backdrop != null) delay(BACKDROP_DELAY_MS)
        backdrop = featured
    }

    val onFocusGame: FocusGame = { game, labelId, onDevice -> focused = Featured.OfGame(game, labelId, onDevice) }

    Box(modifier = modifier.fillMaxSize()) {
        AmbientBackdrop(
            model =
                when (val item = backdrop) {
                    is Featured.OfGame -> item.game.coverFrontUrl
                    is Featured.OfSystem -> item.system.metaSystem.imageResId
                    null -> null
                },
            systemColor =
                when (val item = backdrop) {
                    is Featured.OfGame -> SystemPalette.colorForSystemId(item.game.systemId)
                    is Featured.OfSystem -> SystemPalette.color(item.system.metaSystem)
                    null -> LemuroidColors.Surface
                },
            modifier = Modifier.matchParentSize(),
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 16.dp),
        ) {
            item(key = "notices") { notices() }

            if (featured != null) {
                item(key = "hero") {
                    LandscapeHeader(
                        featured = featured,
                        data = data,
                        modifier = Modifier.padding(horizontal = dimens.screenMargin, vertical = 12.dp),
                    )
                }
            }

            gamesRow("recent", R.string.recent, data.recentGames, data, showTitles = false, onFocusGame)
            systemsRow(data, onFocusSystem = { focused = Featured.OfSystem(it) })
            savedGamesRow(data, showTitles = false, onFocusGame)
            gamesRow("favorites", R.string.favorites, data.favoritesGames, data, showTitles = false, onFocusGame)
            gamesRow("discover", R.string.discover, data.discoveryGames, data, showTitles = false, onFocusGame)
        }
    }
}

@Composable
private fun LandscapeHeader(
    featured: Featured,
    data: HomeLayoutData,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        when (featured) {
            is Featured.OfGame -> {
                val game = featured.game
                Row(modifier = Modifier.height(HEADER_LABEL_HEIGHT), verticalAlignment = Alignment.CenterVertically) {
                    HeroLabel(featured.labelId)
                    Spacer(modifier = Modifier.width(8.dp))
                    SystemChip(game.systemId)
                }
                Text(
                    text = game.title,
                    style = LocalLemuroidTypography.current.heroTitle,
                    color = LemuroidColors.Text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
                )
                HeroButton(
                    game = game,
                    onDevice = featured.onDevice,
                    saveState = rememberGameSaveState(game, data),
                    onClick = { data.onGameClick(game) },
                )
            }
            is Featured.OfSystem -> {
                val context = LocalContext.current
                val system = featured.system
                Text(
                    text = stringResource(R.string.title_systems),
                    style = LocalLemuroidTypography.current.support,
                    color = LemuroidColors.TextSupportOnBackdrop,
                    // Same height as the label row of a game (it has the system chip): the rows below stay put.
                    modifier = Modifier.height(HEADER_LABEL_HEIGHT).wrapContentHeight(Alignment.CenterVertically),
                )
                Text(
                    text = remember(system.metaSystem) { system.getName(context) },
                    style = LocalLemuroidTypography.current.heroTitle,
                    color = LemuroidColors.Text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
                )
                // Same height as the button of a game, so the rows below do not jump.
                Box(modifier = Modifier.height(HERO_BUTTON_HEIGHT), contentAlignment = Alignment.CenterStart) {
                    Text(
                        text = stringResource(R.string.system_grid_details, system.count.toString()),
                        style = LocalLemuroidTypography.current.cardTitle,
                        color = LemuroidColors.TextSupportOnBackdrop,
                    )
                }
            }
        }
    }
}

/** The carousel of systems: the same list as the Systems tab, art only (the name goes to the header). */
private fun LazyListScope.systemsRow(
    data: HomeLayoutData,
    onFocusSystem: (MetaSystemInfo) -> Unit,
) {
    val systems = data.metaSystems
    if (systems.isNullOrEmpty()) return

    item(key = "header_systems") { RowHeader(stringResource(R.string.title_systems)) }
    item(key = "row_systems") {
        val dimens = LocalDimens.current
        LazyRow(
            contentPadding = rowContentPadding(dimens.screenMargin),
            horizontalArrangement = Arrangement.spacedBy(dimens.cardSpacing),
        ) {
            items(systems, key = { it.metaSystem.name }) { system ->
                SystemTile(
                    system = system,
                    width = dimens.systemCardWidth,
                    onClick = { data.onSystemClick(system) },
                    onFocused = { onFocusSystem(system) },
                )
            }
        }
    }
}

@Composable
private fun SystemTile(
    system: MetaSystemInfo,
    width: Dp,
    onClick: () -> Unit,
    onFocused: () -> Unit,
) {
    val context = LocalContext.current
    val dimens = LocalDimens.current
    var isFocused by remember { mutableStateOf(false) }
    val name = remember(system.metaSystem) { system.getName(context) }

    Box(
        modifier =
            Modifier
                .width(width)
                .focusHighlight()
                .onFocusChanged {
                    isFocused = it.hasFocus
                    if (it.hasFocus) onFocused()
                }
                .clip(RoundedCornerShape(dimens.cardCornerRadius))
                .clickable(role = Role.Button, onClick = onClick)
                .semantics { contentDescription = name },
    ) {
        LemuroidSystemImage(
            metaSystem = system.metaSystem,
            modifier = Modifier.fillMaxWidth(),
            hovered = isFocused,
        )
    }
}

// endregion

// region Rows shared by both layouts

private fun LazyListScope.gamesRow(
    sectionKey: String,
    titleId: Int,
    games: List<Game>,
    data: HomeLayoutData,
    showTitles: Boolean,
    onFocusGame: FocusGame? = null,
) {
    if (games.isEmpty()) return
    // The recents are games already played: the header says "Continue playing" for them.
    val isRecents = titleId == R.string.recent
    val labelId = if (isRecents) R.string.home_hero_continue else titleId

    item(key = "header_$sectionKey") { RowHeader(stringResource(titleId)) }
    item(key = "row_$sectionKey") {
        val dimens = LocalDimens.current
        LazyRow(
            contentPadding = rowContentPadding(dimens.screenMargin),
            horizontalArrangement = Arrangement.spacedBy(dimens.cardSpacing),
        ) {
            items(games, key = { "$sectionKey/${it.id}" }) { game ->
                RowCover(
                    game = game,
                    saveState = rememberGameSaveState(game, data),
                    showTitle = showTitles,
                    onClick = { data.onGameClick(game) },
                    onLongClick = { data.onGameLongClick(game) },
                    onFocused = onFocusGame?.let { { it(game, labelId, isRecents) } },
                )
            }
        }
    }
}

/** First 10 groups of the Saved games tab; the tap follows the rules of the tab. */
private fun LazyListScope.savedGamesRow(
    data: HomeLayoutData,
    showTitles: Boolean,
    onFocusGame: FocusGame? = null,
) {
    val onClick = data.onSavedGameClick ?: return
    if (data.savedGames.isEmpty()) return

    item(key = "header_saved") {
        RowHeader(
            title = stringResource(R.string.title_installed),
            actionLabel = data.onShowAllSavedGames?.let { stringResource(R.string.show_all) },
            onAction = data.onShowAllSavedGames,
        )
    }
    item(key = "row_saved") {
        val dimens = LocalDimens.current
        LazyRow(
            contentPadding = rowContentPadding(dimens.screenMargin),
            horizontalArrangement = Arrangement.spacedBy(dimens.cardSpacing),
        ) {
            items(data.savedGames, key = { "saved/${it.group.game.id}" }) { item ->
                SavedCover(
                    item = item,
                    showTitle = showTitles,
                    onClick = { onClick(item) },
                    onLongClick = { data.onGameLongClick(item.group.game) },
                    onFocused = onFocusGame?.let { { it(item.group.game, R.string.title_installed, true) } },
                )
            }
        }
    }
}

@Composable
private fun RowHeader(
    title: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    SectionHeader(
        title = title,
        actionLabel = actionLabel,
        onAction = onAction,
        modifier = Modifier.padding(top = 4.dp),
    )
}

/** Vertical room so the focus outline (gap + stroke, outside the cover) is not clipped by the row. */
private fun rowContentPadding(horizontal: Dp) = PaddingValues(horizontal = horizontal, vertical = 8.dp)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RowCover(
    game: Game,
    saveState: CoverSaveState,
    showTitle: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onFocused: (() -> Unit)?,
) {
    CoverCell(
        title = game.title,
        showTitle = showTitle,
        onClick = onClick,
        onLongClick = onLongClick,
        onFocused = onFocused,
    ) {
        LemuroidGameImage(
            game = game,
            modifier = Modifier.fillMaxSize(),
            // Landscape rows have no title under the cover: the generated cover keeps its own.
            generatedCoverTitle = !showTitle,
        )
        SaveStateOverlay(saveState)
    }
}

@Composable
private fun SavedCover(
    item: InstalledGameGroupUiModel,
    showTitle: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onFocused: (() -> Unit)?,
) {
    val game = item.group.game
    CoverCell(
        title = game.title,
        showTitle = showTitle,
        onClick = onClick,
        onLongClick = onLongClick,
        onFocused = onFocused,
        coverAlpha = if (item.isAvailable) 1f else UNAVAILABLE_ALPHA,
    ) {
        LemuroidGameImage(
            game = game,
            modifier = Modifier.fillMaxSize(),
            generatedCoverTitle = !showTitle,
        )
        if (!item.isAvailable) {
            Box(
                modifier =
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(22.dp)
                        .background(LemuroidColors.ErrorBadge, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.CloudOff,
                    contentDescription = stringResource(R.string.installed_media_unavailable),
                    tint = Color.White,
                    modifier = Modifier.size(13.dp),
                )
            }
        }
        if (item.group.installedVariantsCount > 1) {
            Row(
                modifier =
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                        .background(LemuroidColors.Bars, PillShape)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Default.Layers,
                    contentDescription = null,
                    tint = LemuroidColors.Text,
                    modifier = Modifier.size(11.dp),
                )
                Text(
                    text = " ${item.group.installedVariantsCount}",
                    style = LocalLemuroidTypography.current.support,
                    fontWeight = FontWeight.Bold,
                    color = LemuroidColors.Text,
                )
            }
        }
    }
}

/**
 * A cover of the rows: [LocalDimens] row size, focus outline, tap and long press, and the title
 * below when [showTitle] (portrait). [onFocused] feeds the landscape header.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CoverCell(
    title: String,
    showTitle: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onFocused: (() -> Unit)?,
    coverAlpha: Float = 1f,
    cover: @Composable BoxScope.() -> Unit,
) {
    val dimens = LocalDimens.current
    val shape = RoundedCornerShape(dimens.cardCornerRadius)
    Column(
        modifier =
            Modifier
                .width(dimens.rowCoverWidth)
                .focusHighlight()
                .let { modifier ->
                    if (onFocused == null) modifier else modifier.onFocusChanged { if (it.hasFocus) onFocused() }
                }
                .clip(shape)
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .let { modifier ->
                    // Without the title under the cover, TalkBack still needs the name.
                    if (showTitle) modifier else modifier.semantics { contentDescription = title }
                },
    ) {
        Box(
            modifier =
                Modifier
                    .width(dimens.rowCoverWidth)
                    .height(dimens.rowCoverHeight)
                    .clip(shape)
                    .alpha(coverAlpha),
            content = cover,
        )
        if (showTitle) {
            Text(
                text = title,
                style = LocalLemuroidTypography.current.cardTitle,
                color = LemuroidColors.Text,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 2.dp, end = 2.dp, top = 6.dp),
            )
        }
    }
}

// endregion

// region Hero pieces

private fun heroLabelId(fromRecents: Boolean) = if (fromRecents) R.string.home_hero_continue else R.string.discover

@Composable
private fun HeroLabel(
    @StringRes labelId: Int,
) {
    Text(
        text = stringResource(labelId),
        style = LocalLemuroidTypography.current.support,
        color = LemuroidColors.TextSupportOnBackdrop,
        maxLines = 1,
    )
}

/** Short name of the system in a pill of its colour. */
@Composable
private fun SystemChip(systemId: String) {
    val context = LocalContext.current
    val name =
        remember(systemId) {
            GameSystem.findByIdOrNull(systemId)?.shortTitleResId?.let { context.getString(it) } ?: systemId
        }
    val color = remember(systemId) { SystemPalette.colorForSystemId(systemId) }
    Text(
        text = name,
        style = LocalLemuroidTypography.current.support,
        fontWeight = FontWeight.Bold,
        color = Color.White,
        maxLines = 1,
        modifier =
            Modifier
                .background(color, PillShape)
                .padding(horizontal = 10.dp, vertical = 3.dp),
    )
}

/**
 * "Play" (filled), "Save" (light) or the queue state ("Saving 42%" with the bar inside the
 * button). Whatever the label, the tap is the same `onGameClick` as the cover.
 *
 * "Play" also covers games that were never saved through the queue (ROMs imported from a folder):
 * they are not in `downloadedGameKeys`, so the button runs the same test as
 * `MainActivity.isGamePlaceholder` for its single game, off the main thread.
 */
@Composable
private fun HeroButton(
    game: Game,
    onDevice: Boolean,
    saveState: CoverSaveState,
    onClick: () -> Unit,
) {
    // First guess until the stat below answers (keyed: a new game never shows the previous answer).
    var hasRom by remember(game.fileUri) { mutableStateOf(onDevice || saveState == CoverSaveState.Saved) }
    LaunchedEffect(game.fileUri) {
        hasRom =
            withContext(Dispatchers.IO) {
                val uri = Uri.parse(game.fileUri)
                !(uri.scheme == "file" && uri.path?.let { File(it).length() == 0L } == true)
            }
    }

    val (label, filled) =
        when (saveState) {
            is CoverSaveState.Saving -> stringResource(R.string.save_queue_saving_progress, saveState.percent) to false
            CoverSaveState.Queued -> stringResource(R.string.save_queue_waiting) to false
            CoverSaveState.Paused -> stringResource(R.string.save_queue_paused) to false
            CoverSaveState.Error -> stringResource(R.string.save_queue_error) to false
            CoverSaveState.Saved -> stringResource(R.string.download_complete_play) to true
            CoverSaveState.NotSaved ->
                if (hasRom) {
                    stringResource(R.string.download_complete_play) to true
                } else {
                    stringResource(R.string.save_action_save) to false
                }
        }

    val savingFraction = (saveState as? CoverSaveState.Saving)?.percent?.div(100f)
    Box(
        modifier =
            Modifier
                .height(HERO_BUTTON_HEIGHT)
                .focusHighlight(cornerRadius = HERO_BUTTON_HEIGHT / 2)
                .clip(PillShape)
                .background(if (filled) LemuroidColors.Accent else LemuroidColors.Text)
                // "Saving 42%": the progress fills the button from the left.
                .drawBehind {
                    if (savingFraction != null) {
                        drawRect(LemuroidColors.Accent, size = Size(size.width * savingFraction, size.height))
                    }
                }
                .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = label,
            style = LocalLemuroidTypography.current.cardTitle,
            fontWeight = FontWeight.Bold,
            color = LemuroidColors.OnAccent,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 22.dp),
        )
    }
}

@Composable
private fun rememberGameSaveState(
    game: Game,
    data: HomeLayoutData,
): CoverSaveState =
    rememberCoverSaveState(
        downloadKey = game.downloadKey,
        isDownloaded = data.downloadedGameKeys.contains(game.downloadKey),
        entries = data.saveQueueEntries,
    )

// endregion

/** A row item got the D-pad focus: the game, the line above its title, whether it is probably saved. */
private typealias FocusGame = (Game, Int, Boolean) -> Unit

private val ACTIVE_QUEUE_STATES = setOf(SaveQueueState.QUEUED, SaveQueueState.SAVING, SaveQueueState.PAUSED)
private val HERO_COVER_WIDTH = 112.dp
private val HERO_BUTTON_HEIGHT = 36.dp
private val HEADER_LABEL_HEIGHT = 24.dp
private const val BACKDROP_DELAY_MS = 300L
private const val UNAVAILABLE_ALPHA = 0.6f
