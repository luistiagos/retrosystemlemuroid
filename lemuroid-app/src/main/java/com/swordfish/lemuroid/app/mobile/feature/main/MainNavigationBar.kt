package com.swordfish.lemuroid.app.mobile.feature.main

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import com.swordfish.lemuroid.R
import com.swordfish.lemuroid.app.mobile.shared.compose.ui.LemuroidColors
import com.swordfish.lemuroid.app.mobile.shared.compose.ui.LocalDimens
import com.swordfish.lemuroid.app.mobile.shared.compose.ui.LocalLemuroidTypography
import com.swordfish.lemuroid.app.mobile.shared.compose.ui.PillShape
import com.swordfish.lemuroid.app.mobile.shared.compose.ui.effects.focusHighlight

@Composable
fun MainNavigationBar(
    currentRoute: MainRoute?,
    navController: NavHostController,
) {
    AnimatedVisibility(
        visible = currentRoute?.showBottomNavigation != false,
        enter = expandVertically(),
        exit = shrinkVertically(),
    ) {
        LemuroidNavigationBar(currentRoute, navController)
    }
}

@Composable
private fun LemuroidNavigationBar(
    currentRoute: MainRoute?,
    navController: NavHostController,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(LemuroidColors.Bars),
    ) {
        HorizontalDivider(color = LemuroidColors.Border)
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(NavigationBarDefaults.windowInsets)
                    .height(BAR_HEIGHT)
                    .selectableGroup(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MainNavigationRoutes.values().forEach { destination ->
                BarItem(
                    destination = destination,
                    selected = currentRoute?.root == destination.route,
                    onClick = { navController.navigateToTab(destination) },
                )
            }
        }
    }
}

/**
 * One tab of the bottom bar: the whole cell is the touch target, the icon sits in the accent pill
 * when selected. Not the material3 `NavigationBarItem`: its D-pad focus is only a 12% state layer,
 * hard to see on a TV box; here the pill gets the accent outline.
 */
@Composable
private fun RowScope.BarItem(
    destination: MainNavigationRoutes,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val dimens = LocalDimens.current
    Box(
        modifier =
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .selectable(
                    selected = selected,
                    interactionSource = interactionSource,
                    indication = LocalIndication.current,
                    role = Role.Tab,
                    onClick = onClick,
                ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier =
                Modifier
                    .size(width = BAR_PILL_WIDTH, height = BAR_PILL_HEIGHT)
                    .then(
                        if (focused) {
                            Modifier.border(dimens.focusBorderWidth, LemuroidColors.Accent, PillShape)
                        } else {
                            Modifier
                        },
                    )
                    .clip(PillShape)
                    .background(if (selected) LemuroidColors.AccentPill else Color.Transparent),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (selected) destination.selectedIcon else destination.unselectedIcon,
                contentDescription = stringResource(destination.titleId),
                tint = if (selected) LemuroidColors.Accent else LemuroidColors.TextSupport,
            )
        }
    }
}

private val BAR_HEIGHT = 64.dp
private val BAR_PILL_WIDTH = 64.dp
private val BAR_PILL_HEIGHT = 32.dp

/** Navigation of the tabs, shared by the bottom bar, the landscape rail and the Home shortcuts. */
fun NavHostController.navigateToTab(destination: MainNavigationRoutes) {
    navigate(destination.route.route) {
        // Pop up to the start destination of the graph to
        // avoid building up a large stack of destinations
        // on the back stack as users select items
        popUpTo(graph.findStartDestination().id) {
            saveState = false
        }
        // Avoid multiple copies of the same destination when
        // reselecting the same item
        launchSingleTop = true
        // Restore state when reselecting a previously selected item
        restoreState = false
    }
}

/**
 * Landscape replacement of the bottom bar: the same 5 tabs in a side rail, and the save queue
 * indicator pinned to its foot (ring with the percentage and the number of games left), always
 * visible while the queue is active.
 *
 * Not the material3 `NavigationRail`: its items are 56 dp tall, and 5 of them plus the status bar
 * and the queue do not fit in the ~360 dp of a phone held sideways. Here the tabs scroll if they
 * must and the queue stays put.
 */
@Composable
fun MainNavigationRail(
    currentRoute: MainRoute?,
    navController: NavHostController,
    hasSaveQueueActive: Boolean,
    saveQueueProgress: Float,
    saveQueueCount: Int,
    onOpenSaveQueue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dimens = LocalDimens.current
    Column(
        modifier =
            modifier
                .fillMaxHeight()
                .background(LemuroidColors.Bars)
                .windowInsetsPadding(
                    WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical + WindowInsetsSides.Start),
                )
                .width(dimens.navRailWidth),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier =
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 8.dp)
                    .selectableGroup(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            MainNavigationRoutes.values().forEach { destination ->
                RailItem(
                    destination = destination,
                    selected = currentRoute?.root == destination.route,
                    onClick = { navController.navigateToTab(destination) },
                )
            }
        }
        if (hasSaveQueueActive) {
            RailSaveQueue(
                progress = saveQueueProgress,
                count = saveQueueCount,
                onClick = onOpenSaveQueue,
            )
        }
    }
}

@Composable
private fun RailItem(
    destination: MainNavigationRoutes,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val title = stringResource(destination.titleId)
    Box(
        modifier =
            Modifier
                .focusHighlight(cornerRadius = RAIL_ITEM_HEIGHT / 2)
                .size(width = RAIL_ITEM_WIDTH, height = RAIL_ITEM_HEIGHT)
                .clip(PillShape)
                .background(if (selected) LemuroidColors.AccentPillOpaque else Color.Transparent)
                .selectable(selected = selected, role = Role.Tab, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (selected) destination.selectedIcon else destination.unselectedIcon,
            contentDescription = title,
            tint = if (selected) LemuroidColors.Accent else LemuroidColors.TextSupport,
        )
    }
}

@Composable
private fun RailSaveQueue(
    progress: Float,
    count: Int,
    onClick: () -> Unit,
) {
    val percent = (progress * 100).toInt().coerceIn(0, 100)
    val description = stringResource(R.string.save_queue_title)
    Column(
        modifier =
            Modifier
                .padding(bottom = 8.dp)
                .focusHighlight(cornerRadius = 12.dp)
                .clip(RoundedCornerShape(12.dp))
                .clickable(role = Role.Button, onClick = onClick)
                .semantics(mergeDescendants = true) { contentDescription = description }
                .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(modifier = Modifier.size(40.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(
                progress = { percent / 100f },
                modifier = Modifier.matchParentSize(),
                color = LemuroidColors.Accent,
                trackColor = LemuroidColors.Text.copy(alpha = 0.25f),
                strokeWidth = 3.dp,
            )
            Text(
                text = "$percent%",
                style = LocalLemuroidTypography.current.support,
                fontWeight = FontWeight.Bold,
                color = LemuroidColors.Text,
            )
        }
        Text(
            // Games left in the queue, the active one included.
            text = "$count",
            style = LocalLemuroidTypography.current.support,
            color = LemuroidColors.TextSupport,
            maxLines = 1,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

private val RAIL_ITEM_WIDTH = 56.dp
private val RAIL_ITEM_HEIGHT = 40.dp
