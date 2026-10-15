package com.swordfish.lemuroid.app.mobile.feature.main

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.navigation.NavHostController
import com.swordfish.lemuroid.R
import com.swordfish.lemuroid.app.mobile.shared.compose.ui.LemuroidColors
import com.swordfish.lemuroid.app.mobile.shared.compose.ui.LocalLemuroidTypography
import com.swordfish.lemuroid.app.mobile.shared.compose.ui.PillShape
import com.swordfish.lemuroid.app.mobile.shared.compose.ui.effects.focusHighlight
import com.swordfish.lemuroid.app.shared.savesync.SaveSyncWork

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainTopBar(
    currentRoute: MainRoute,
    navController: NavHostController,
    onHelpPressed: () -> Unit,
    onUpdateQueryString: (String) -> Unit,
    mainUIState: MainViewModel.UiState,
    hasSaveQueueActive: Boolean = false,
    saveQueueProgress: Float = 0f,
    onOpenSaveQueue: () -> Unit = {},
    showSaveQueueAction: Boolean = true,
    scrollBehavior: TopAppBarScrollBehavior? = null,
    windowInsets: WindowInsets = TopAppBarDefaults.windowInsets,
) {
    Column(modifier = Modifier.background(LemuroidColors.Bars)) {
        LemuroidTopAppBar(
            route = currentRoute,
            navController = navController,
            mainUIState = mainUIState,
            onHelpPressed = onHelpPressed,
            onUpdateQueryString = onUpdateQueryString,
            hasSaveQueueActive = hasSaveQueueActive && showSaveQueueAction,
            saveQueueProgress = saveQueueProgress,
            onOpenSaveQueue = onOpenSaveQueue,
            scrollBehavior = scrollBehavior,
            windowInsets = windowInsets,
        )

        AnimatedVisibility(mainUIState.operationInProgress) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                color = LemuroidColors.Accent,
                trackColor = LemuroidColors.Border,
            )
        }

        AnimatedVisibility(hasSaveQueueActive && !mainUIState.operationInProgress) {
            LinearProgressIndicator(
                progress = { saveQueueProgress },
                modifier = Modifier.fillMaxWidth(),
                color = LemuroidColors.Accent,
                trackColor = LemuroidColors.Border,
            )
        }

        HorizontalDivider(color = LemuroidColors.Border)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LemuroidTopAppBar(
    route: MainRoute,
    navController: NavController,
    mainUIState: MainViewModel.UiState,
    onHelpPressed: () -> Unit,
    onUpdateQueryString: (String) -> Unit,
    hasSaveQueueActive: Boolean = false,
    saveQueueProgress: Float = 0f,
    onOpenSaveQueue: () -> Unit = {},
    scrollBehavior: TopAppBarScrollBehavior? = null,
    windowInsets: WindowInsets = TopAppBarDefaults.windowInsets,
) {
    val context = LocalContext.current

    TopAppBar(
        scrollBehavior = scrollBehavior,
        windowInsets = windowInsets,
        title = {
            when (route) {
                MainRoute.SEARCH ->
                    LemuroidSearchView(
                        mainUIState = mainUIState,
                        onUpdateQueryString = onUpdateQueryString,
                    )
                // The catalog header right below already names the system in large type.
                MainRoute.SYSTEM_GAMES -> Unit
                else ->
                    Text(
                        text = stringResource(route.titleId),
                        style = LocalLemuroidTypography.current.screenTitle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
            }
        },
        colors =
            TopAppBarDefaults.topAppBarColors(
                scrolledContainerColor = LemuroidColors.Bars,
                containerColor = LemuroidColors.Bars,
                titleContentColor = LemuroidColors.Text,
                navigationIconContentColor = LemuroidColors.Text,
                actionIconContentColor = LemuroidColors.TextSupport,
            ),
        navigationIcon = {
            AnimatedVisibility(
                visible = route.parent != null,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                TopBarIconButton(onClick = { navController.popBackStack() }) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        stringResource(id = R.string.back),
                    )
                }
            }
        },
        actions = {
            LemuroidTopBarActions(
                route = route,
                navController = navController,
                context = context,
                saveSyncEnabled = mainUIState.saveSyncEnabled,
                onHelpPressed = onHelpPressed,
                operationsInProgress = mainUIState.operationInProgress,
                hasSaveQueueActive = hasSaveQueueActive,
                saveQueueProgress = saveQueueProgress,
                onOpenSaveQueue = onOpenSaveQueue,
            )
        },
    )
}

@Composable
fun LemuroidTopBarActions(
    route: MainRoute,
    navController: NavController,
    context: Context,
    saveSyncEnabled: Boolean,
    operationsInProgress: Boolean,
    onHelpPressed: () -> Unit,
    hasSaveQueueActive: Boolean = false,
    saveQueueProgress: Float = 0f,
    onOpenSaveQueue: () -> Unit = {},
) {
    Row {
        if (hasSaveQueueActive) {
            TopBarIconButton(onClick = onOpenSaveQueue) {
                // The same ring as the foot of the landscape rail, around the download arrow.
                Box(modifier = Modifier.size(30.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        progress = { saveQueueProgress },
                        modifier = Modifier.matchParentSize(),
                        color = LemuroidColors.Accent,
                        trackColor = LemuroidColors.Text.copy(alpha = 0.25f),
                        strokeWidth = 2.5.dp,
                    )
                    Icon(
                        Icons.Default.Download,
                        contentDescription = stringResource(R.string.save_queue_title),
                        tint = LemuroidColors.Accent,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
        TopBarIconButton(
            onClick = { onHelpPressed() },
        ) {
            Icon(
                Icons.Outlined.Info,
                stringResource(R.string.mobile_settings_help),
            )
        }
        if (saveSyncEnabled) {
            TopBarIconButton(
                onClick = { SaveSyncWork.enqueueManualWork(context.applicationContext) },
                enabled = !operationsInProgress,
            ) {
                Icon(
                    Icons.Outlined.CloudSync,
                    stringResource(R.string.save_sync),
                )
            }
        }
        if (route.showBottomNavigation) {
            TopBarIconButton(
                onClick = { navController.navigate(MainRoute.SETTINGS.route) },
            ) {
                Icon(
                    Icons.Outlined.Settings,
                    stringResource(R.string.settings),
                )
            }
        }
    }
}

/** [IconButton] with the accent focus outline, so the D-pad focus is visible on the bars. */
@Composable
private fun TopBarIconButton(
    onClick: () -> Unit,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.focusHighlight(cornerRadius = 20.dp),
        content = content,
    )
}

@Composable
private fun LemuroidSearchView(
    mainUIState: MainViewModel.UiState,
    onUpdateQueryString: (String) -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(56.dp),
    ) {
        Surface(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(top = 8.dp, bottom = 8.dp, end = 8.dp),
            shape = PillShape,
            color = LemuroidColors.Surface,
        ) { }

        TextField(
            value = mainUIState.searchQuery,
            modifier =
                Modifier
                    .fillMaxSize()
                    .focusRequester(focusRequester),
            textStyle = MaterialTheme.typography.bodyMedium,
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = LemuroidColors.TextSupport) },
            onValueChange = { onUpdateQueryString(it) },
            singleLine = true,
            keyboardActions =
                KeyboardActions(
                    onDone = { focusManager.clearFocus(true) },
                ),
            colors =
                TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    focusedTextColor = LemuroidColors.Text,
                    unfocusedTextColor = LemuroidColors.Text,
                    cursorColor = LemuroidColors.Accent,
                ),
        )
    }
}
