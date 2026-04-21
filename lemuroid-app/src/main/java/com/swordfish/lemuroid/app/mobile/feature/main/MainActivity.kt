package com.swordfish.lemuroid.app.mobile.feature.main

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.res.stringResource
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.fredporciuncula.flow.preferences.FlowSharedPreferences
import com.swordfish.lemuroid.R
import com.swordfish.lemuroid.app.mobile.feature.favorites.FavoritesScreen
import com.swordfish.lemuroid.app.mobile.feature.favorites.FavoritesViewModel
import com.swordfish.lemuroid.app.mobile.feature.games.GamesScreen
import com.swordfish.lemuroid.app.mobile.feature.games.GamesViewModel
import com.swordfish.lemuroid.app.mobile.feature.home.HomeScreen
import com.swordfish.lemuroid.app.mobile.feature.home.HomeViewModel
import com.swordfish.lemuroid.app.mobile.feature.search.SearchScreen
import com.swordfish.lemuroid.app.mobile.feature.search.SearchViewModel
import com.swordfish.lemuroid.app.mobile.feature.settings.advanced.AdvancedSettingsScreen
import com.swordfish.lemuroid.app.mobile.feature.settings.advanced.AdvancedSettingsViewModel
import com.swordfish.lemuroid.app.mobile.feature.settings.bios.BiosScreen
import com.swordfish.lemuroid.app.mobile.feature.settings.bios.BiosSettingsViewModel
import com.swordfish.lemuroid.app.mobile.feature.settings.coreselection.CoresSelectionScreen
import com.swordfish.lemuroid.app.mobile.feature.settings.coreselection.CoresSelectionViewModel
import com.swordfish.lemuroid.app.mobile.feature.settings.general.SettingsScreen
import com.swordfish.lemuroid.app.mobile.feature.settings.general.SettingsViewModel
import com.swordfish.lemuroid.app.mobile.feature.settings.inputdevices.InputDevicesSettingsScreen
import com.swordfish.lemuroid.app.mobile.feature.settings.inputdevices.InputDevicesSettingsViewModel
import com.swordfish.lemuroid.app.mobile.feature.settings.inputdevices.PortAssignmentScreen
import com.swordfish.lemuroid.app.mobile.feature.settings.inputdevices.PortAssignmentViewModel
import com.swordfish.lemuroid.app.mobile.feature.settings.savesync.SaveSyncSettingsScreen
import com.swordfish.lemuroid.app.mobile.feature.settings.savesync.SaveSyncSettingsViewModel
import com.swordfish.lemuroid.app.mobile.feature.settings.romset.RomsetExportScreen
import com.swordfish.lemuroid.app.mobile.feature.settings.romset.RomsetImportScreen
import com.swordfish.lemuroid.app.mobile.feature.settings.romset.RomsetSettingsScreen
import com.swordfish.lemuroid.app.mobile.feature.settings.romset.RomsetViewModel
import com.swordfish.lemuroid.app.mobile.feature.settings.transfer.TransferExportScreen
import com.swordfish.lemuroid.app.mobile.feature.settings.transfer.TransferImportScreen
import com.swordfish.lemuroid.app.mobile.feature.settings.transfer.TransferSettingsScreen
import com.swordfish.lemuroid.app.mobile.feature.settings.transfer.TransferViewModel
import com.swordfish.lemuroid.lib.romset.RomsetExportManager
import com.swordfish.lemuroid.lib.romset.RomsetImportManager
import com.swordfish.lemuroid.app.mobile.feature.shortcuts.ShortcutsGenerator
import com.swordfish.lemuroid.app.mobile.feature.systems.MetaSystemsScreen
import com.swordfish.lemuroid.app.mobile.feature.systems.MetaSystemsViewModel
import com.swordfish.lemuroid.app.mobile.shared.compose.ui.AppTheme
import com.swordfish.lemuroid.app.shared.GameInteractor
import com.swordfish.lemuroid.app.shared.game.BaseGameActivity
import com.swordfish.lemuroid.app.shared.game.GameLauncher
import com.swordfish.lemuroid.app.shared.input.InputDeviceManager
import com.swordfish.lemuroid.app.shared.main.BusyActivity
import com.swordfish.lemuroid.app.shared.main.GameLaunchTaskHandler
import com.swordfish.lemuroid.app.shared.roms.RomOnDemandManager
import com.swordfish.lemuroid.app.shared.updates.AppUpdateViewModel
import com.swordfish.lemuroid.app.shared.settings.SettingsInteractor
import com.swordfish.lemuroid.common.coroutines.safeLaunch
import com.swordfish.lemuroid.ext.feature.review.ReviewManager
import com.swordfish.lemuroid.lib.android.RetrogradeComponentActivity
import com.swordfish.lemuroid.lib.bios.BiosManager
import com.swordfish.lemuroid.lib.core.CoresSelection
import com.swordfish.lemuroid.lib.injection.PerActivity
import com.swordfish.lemuroid.lib.library.MetaSystemID
import com.swordfish.lemuroid.lib.library.SystemID
import com.swordfish.lemuroid.lib.library.db.RetrogradeDatabase
import com.swordfish.lemuroid.lib.library.db.entity.Game
import com.swordfish.lemuroid.lib.preferences.SharedPreferencesHelper
import com.swordfish.lemuroid.lib.savesync.SaveSyncManager
import com.swordfish.lemuroid.lib.storage.DirectoriesManager
import com.swordfish.lemuroid.lib.transfer.GameExportManager
import com.swordfish.lemuroid.lib.transfer.GameImportManager
import dagger.Lazy
import dagger.Provides
import de.charlex.compose.material3.HtmlText
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

class MainActivity : RetrogradeComponentActivity(), BusyActivity {
    @Inject
    lateinit var gameLaunchTaskHandler: GameLaunchTaskHandler

    @Inject
    lateinit var saveSyncManager: SaveSyncManager

    @Inject
    lateinit var retrogradeDb: RetrogradeDatabase

    @Inject
    lateinit var gameInteractor: GameInteractor

    @Inject
    lateinit var biosManager: Lazy<BiosManager>

    @Inject
    lateinit var coresSelection: CoresSelection

    @Inject
    lateinit var settingsInteractor: Lazy<SettingsInteractor>

    @Inject
    lateinit var inputDeviceManager: Lazy<InputDeviceManager>

    @Inject
    lateinit var romOnDemandManager: RomOnDemandManager

    @Inject
    lateinit var gameExportManager: Lazy<GameExportManager>

    @Inject
    lateinit var gameImportManager: Lazy<GameImportManager>

    @Inject
    lateinit var romsetExportManager: Lazy<RomsetExportManager>

    @Inject
    lateinit var romsetImportManager: Lazy<RomsetImportManager>

    private val reviewManager = ReviewManager()

    private val mainViewModel: MainViewModel by viewModels {
        MainViewModel.Factory(applicationContext, saveSyncManager, retrogradeDb)
    }

    private val updateViewModel: AppUpdateViewModel by viewModels {
        AppUpdateViewModel.Factory(applicationContext)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge(
            SystemBarStyle.dark(Color.TRANSPARENT),
            SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)

        setContent {
            val navController = rememberNavController()
            MainScreen(navController)
        }

        // Post-UI: these run after the first frame is scheduled
        lifecycleScope.safeLaunch {
            reviewManager.initialize(applicationContext)
        }
        updateViewModel.checkOnStartup()
        requestBatteryOptimizationExemption()
    }

    private fun requestBatteryOptimizationExemption() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            try {
                startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                )
            } catch (e: android.content.ActivityNotFoundException) {
                // Some OEM firmware removes this activity — ignore silently.
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun MainScreen(navController: NavHostController) {
        AppTheme {
            val navBackStackEntry = navController.currentBackStackEntryAsState()
            val currentDestination = navBackStackEntry.value?.destination
            val currentRoute =
                currentDestination?.route
                    ?.let { MainRoute.findByRoute(it) }
                    ?: MainRoute.HOME

            val infoDialogDisplayed =
                remember {
                    mutableStateOf(false)
                }

            LaunchedEffect(currentRoute) {
                mainViewModel.changeRoute(currentRoute)
            }

            val selectedGameState =
                remember {
                    mutableStateOf<Game?>(null)
                }

            val selectedGameForDownload =
                remember {
                    mutableStateOf<Game?>(null)
                }

            val downloadedFileNames =
                mainViewModel.downloadedFileNames
                    .collectAsState()
                    .value

            val onGameLongClick = { game: Game ->
                selectedGameState.value = game
            }

            // A game is a streaming placeholder (needs download) when its file:// URI
            // points to a 0-byte file on disk. SAF (content://) and already-downloaded
            // games always have content, so they play directly.
            val isGamePlaceholder = { game: Game ->
                val uri = Uri.parse(game.fileUri)
                uri.scheme == "file" &&
                    uri.path?.let { File(it).length() == 0L } == true
            }

            val onGameClick = { game: Game ->
                if (!isGamePlaceholder(game)) {
                    gameInteractor.onGamePlay(game)
                } else {
                    selectedGameForDownload.value = game
                }
            }

            val onGameFavoriteToggle = { game: Game, isFavorite: Boolean ->
                gameInteractor.onFavoriteToggle(game, isFavorite)
            }

            val onHelpPressed = {
                infoDialogDisplayed.value = true
            }

            val mainUIState =
                mainViewModel.state
                    .collectAsState(MainViewModel.UiState())
                    .value

            Scaffold(
                topBar = {
                    MainTopBar(
                        currentRoute = currentRoute,
                        navController = navController,
                        onHelpPressed = onHelpPressed,
                        mainUIState = mainUIState,
                        onUpdateQueryString = { mainViewModel.changeQueryString(it) },
                    )
                },
                bottomBar = { MainNavigationBar(currentRoute, navController) },
            ) { padding ->
                NavHost(
                    modifier = Modifier.fillMaxSize(),
                    navController = navController,
                    startDestination = MainRoute.HOME.route,
                ) {
                    composable(MainRoute.HOME) {
                        HomeScreen(
                            modifier = Modifier.padding(padding),
                            viewModel =
                                viewModel(
                                    factory =
                                        HomeViewModel.Factory(
                                            applicationContext,
                                            retrogradeDb,
                                            coresSelection,
                                        ),
                                ),
                            downloadedFileNames = downloadedFileNames,
                            onGameClick = onGameClick,
                            onGameLongClick = onGameLongClick,
                            onOpenCoreSelection = { navController.navigateToRoute(MainRoute.SETTINGS_CORES_SELECTION) },
                        )
                    }
                    composable(MainRoute.FAVORITES) {
                        FavoritesScreen(
                            modifier = Modifier.padding(padding),
                            viewModel =
                                viewModel(
                                    factory = FavoritesViewModel.Factory(retrogradeDb),
                                ),
                            downloadedFileNames = downloadedFileNames,
                            onGameClick = onGameClick,
                            onGameLongClick = onGameLongClick,
                        )
                    }
                    composable(MainRoute.SEARCH) {
                        SearchScreen(
                            modifier = Modifier.padding(padding),
                            viewModel =
                                viewModel(
                                    factory = SearchViewModel.Factory(retrogradeDb),
                                ),
                            searchQuery = mainUIState.searchQuery,
                            downloadedFileNames = downloadedFileNames,
                            onGameClick = onGameClick,
                            onGameLongClick = onGameLongClick,
                            onGameFavoriteToggle = onGameFavoriteToggle,
                            onResetSearchQuery = { mainViewModel.changeQueryString("") },
                        )
                    }
                    composable(MainRoute.SYSTEMS) {
                        MetaSystemsScreen(
                            modifier = Modifier.padding(padding),
                            navController = navController,
                            viewModel =
                                viewModel(
                                    factory =
                                        MetaSystemsViewModel.Factory(
                                            retrogradeDb,
                                            applicationContext,
                                        ),
                                ),
                        )
                    }
                    composable(MainRoute.SYSTEM_GAMES) { entry ->
                        val metaSystemId = entry.arguments?.getString("metaSystemId") ?: return@composable
                        GamesScreen(
                            modifier = Modifier.padding(padding),
                            viewModel =
                                viewModel(
                                    factory =
                                        GamesViewModel.Factory(
                                            retrogradeDb,
                                            MetaSystemID.valueOf(metaSystemId),
                                        ),
                                ),
                            onGameClick = onGameClick,
                            onGameLongClick = onGameLongClick,
                            onGameFavoriteToggle = onGameFavoriteToggle,
                            downloadedFileNames = downloadedFileNames,
                        )
                    }
                    composable(MainRoute.SETTINGS) {
                        SettingsScreen(
                            modifier = Modifier.padding(padding),
                            viewModel =
                                viewModel(
                                    factory =
                                        SettingsViewModel.Factory(
                                            applicationContext,
                                            settingsInteractor.get(),
                                            saveSyncManager,
                                            FlowSharedPreferences(
                                                SharedPreferencesHelper.getLegacySharedPreferences(
                                                    applicationContext,
                                                ),
                                            ),
                                        ),
                                ),
                            navController = navController,
                            onCheckUpdate = { updateViewModel.checkManually() },
                        )
                    }
                    composable(MainRoute.SETTINGS_ADVANCED) {
                        AdvancedSettingsScreen(
                            modifier = Modifier.padding(padding),
                            viewModel =
                                viewModel(
                                    factory =
                                        AdvancedSettingsViewModel.Factory(
                                            applicationContext,
                                            settingsInteractor.get(),
                                        ),
                                ),
                            navController = navController,
                        )
                    }
                    composable(MainRoute.SETTINGS_BIOS) {
                        BiosScreen(
                            modifier = Modifier.padding(padding),
                            viewModel =
                                viewModel(
                                    factory = BiosSettingsViewModel.Factory(biosManager.get()),
                                ),
                        )
                    }
                    composable(MainRoute.SETTINGS_CORES_SELECTION) {
                        CoresSelectionScreen(
                            modifier = Modifier.padding(padding),
                            viewModel =
                                viewModel(
                                    factory =
                                        CoresSelectionViewModel.Factory(
                                            applicationContext,
                                            coresSelection,
                                        ),
                                ),
                        )
                    }
                    composable(MainRoute.SETTINGS_INPUT_DEVICES) {
                        InputDevicesSettingsScreen(
                            modifier = Modifier.padding(padding),
                            navController = navController,
                            viewModel =
                                viewModel(
                                    factory =
                                        InputDevicesSettingsViewModel.Factory(
                                            applicationContext,
                                            inputDeviceManager.get(),
                                        ),
                                ),
                        )
                    }
                    composable(MainRoute.SETTINGS_PORT_ASSIGNMENT) {
                        PortAssignmentScreen(
                            modifier = Modifier.padding(padding),
                            viewModel =
                                viewModel(
                                    factory = PortAssignmentViewModel.Factory(
                                        inputDeviceManager.get(),
                                    ),
                                ),
                        )
                    }
                    composable(MainRoute.SETTINGS_SAVE_SYNC) {
                        SaveSyncSettingsScreen(
                            modifier = Modifier.padding(padding),
                            viewModel =
                                viewModel(
                                    factory =
                                        SaveSyncSettingsViewModel.Factory(
                                            application,
                                            saveSyncManager,
                                        ),
                                ),
                        )
                    }
                    composable(MainRoute.SETTINGS_TRANSFER) {
                        TransferSettingsScreen(
                            modifier = Modifier.padding(padding),
                            navController = navController,
                        )
                    }
                    composable(MainRoute.SETTINGS_TRANSFER_EXPORT) {
                        TransferExportScreen(
                            modifier = Modifier.padding(padding),
                            viewModel = viewModel(
                                factory = TransferViewModel.Factory(
                                    applicationContext,
                                    retrogradeDb,
                                    gameExportManager.get(),
                                    gameImportManager.get(),
                                ),
                            ),
                        )
                    }
                    composable(MainRoute.SETTINGS_TRANSFER_IMPORT) {
                        TransferImportScreen(
                            modifier = Modifier.padding(padding),
                            viewModel = viewModel(
                                factory = TransferViewModel.Factory(
                                    applicationContext,
                                    retrogradeDb,
                                    gameExportManager.get(),
                                    gameImportManager.get(),
                                ),
                            ),
                        )
                    }
                    composable(MainRoute.SETTINGS_ROMSET) {
                        RomsetSettingsScreen(
                            modifier = Modifier.padding(padding),
                            navController = navController,
                        )
                    }
                    composable(MainRoute.SETTINGS_ROMSET_EXPORT) {
                        RomsetExportScreen(
                            modifier = Modifier.padding(padding),
                            viewModel = viewModel(
                                factory = RomsetViewModel.Factory(
                                    applicationContext,
                                    romsetExportManager.get(),
                                    romsetImportManager.get(),
                                ),
                            ),
                        )
                    }
                    composable(MainRoute.SETTINGS_ROMSET_IMPORT) {
                        RomsetImportScreen(
                            modifier = Modifier.padding(padding),
                            viewModel = viewModel(
                                factory = RomsetViewModel.Factory(
                                    applicationContext,
                                    romsetExportManager.get(),
                                    romsetImportManager.get(),
                                ),
                            ),
                        )
                    }
                }
            }

            MainGameContextActions(
                selectedGameState = selectedGameState,
                shortcutSupported = gameInteractor.supportShortcuts(),
                isGameDownloaded = selectedGameState.value?.fileName
                    ?.let { downloadedFileNames.contains(it) } ?: true,
                onGamePlay = { game ->
                    if (!isGamePlaceholder(game)) {
                        gameInteractor.onGamePlay(game)
                    } else {
                        selectedGameForDownload.value = game
                    }
                },
                onGameRestart = { game ->
                    if (!isGamePlaceholder(game)) {
                        gameInteractor.onGameRestart(game)
                    } else {
                        selectedGameForDownload.value = game
                    }
                },
                onFavoriteToggle = { game: Game, isFavorite: Boolean ->
                    gameInteractor.onFavoriteToggle(game, isFavorite)
                },
                onCreateShortcut = { gameInteractor.onCreateShortcut(it) },
                onDeleteRom = { game ->
                    lifecycleScope.launch {
                        romOnDemandManager.deleteRom(game)
                    }
                },
            )

            selectedGameForDownload.value?.let { game ->
                RomDownloadDialog(
                    selectedGame = game,
                    selectedGameState = selectedGameForDownload,
                    romOnDemandManager = romOnDemandManager,
                    onDownloadComplete = { gameInteractor.onGamePlay(it) },
                )
            }

            if (infoDialogDisplayed.value) {
                val message =
                    remember {
                        val systemFolders =
                            SystemID.values()
                                .joinToString(", ") { "<i>${it.dbname}</i>" }

                        getString(R.string.lemuroid_help_content)
                            .replace("\$SYSTEMS", systemFolders)
                    }

                AlertDialog(
                    text = { HtmlText(text = message) },
                    onDismissRequest = { infoDialogDisplayed.value = false },
                    confirmButton = { },
                )
            }

            // ── App-update dialogs ────────────────────────────────────────────
            val updateState = updateViewModel.state.collectAsState().value
            when (val s = updateState) {
                is AppUpdateViewModel.State.UpdateAvailable -> {
                    AlertDialog(
                        onDismissRequest = { updateViewModel.dismissUpdate() },
                        title = {
                            androidx.compose.material3.Text(
                                stringResource(R.string.update_dialog_title, s.info.versionName)
                            )
                        },
                        text = {
                            androidx.compose.material3.Text(
                                stringResource(R.string.update_dialog_message)
                            )
                        },
                        confirmButton = {
                            androidx.compose.material3.TextButton(
                                onClick = { updateViewModel.startUpdate(s.info) }
                            ) {
                                androidx.compose.material3.Text(
                                    stringResource(R.string.update_dialog_yes)
                                )
                            }
                        },
                        dismissButton = {
                            androidx.compose.material3.TextButton(
                                onClick = { updateViewModel.dismissUpdate() }
                            ) {
                                androidx.compose.material3.Text(
                                    stringResource(R.string.update_dialog_no)
                                )
                            }
                        },
                    )
                }
                is AppUpdateViewModel.State.Downloading -> {
                    AlertDialog(
                        onDismissRequest = {},
                        title = {
                            androidx.compose.material3.Text(
                                stringResource(R.string.update_downloading_title)
                            )
                        },
                        text = {
                            androidx.compose.foundation.layout.Column(
                                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)
                            ) {
                                androidx.compose.material3.Text(
                                    stringResource(
                                        R.string.update_downloading_message,
                                        (s.progress * 100).toInt()
                                    )
                                )
                                androidx.compose.material3.LinearProgressIndicator(
                                    progress = { s.progress },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        },
                        confirmButton = {},
                    )
                }
                is AppUpdateViewModel.State.NoUpdate -> {
                    AlertDialog(
                        onDismissRequest = { updateViewModel.resetState() },
                        title = {
                            androidx.compose.material3.Text(
                                stringResource(R.string.update_no_update_title)
                            )
                        },
                        text = {
                            androidx.compose.material3.Text(
                                stringResource(R.string.update_no_update_message)
                            )
                        },
                        confirmButton = {
                            androidx.compose.material3.TextButton(
                                onClick = { updateViewModel.resetState() }
                            ) {
                                androidx.compose.material3.Text(stringResource(R.string.ok))
                            }
                        },
                    )
                }
                is AppUpdateViewModel.State.Error -> {
                    AlertDialog(
                        onDismissRequest = { updateViewModel.resetState() },
                        title = {
                            androidx.compose.material3.Text(
                                stringResource(R.string.update_error_title)
                            )
                        },
                        text = {
                            androidx.compose.material3.Text(s.message)
                        },
                        confirmButton = {
                            androidx.compose.material3.TextButton(
                                onClick = { updateViewModel.resetState() }
                            ) {
                                androidx.compose.material3.Text(stringResource(R.string.ok))
                            }
                        },
                    )
                }
                else -> {}
            }
        }
    }

    override fun activity(): Activity = this

    override fun isBusy(): Boolean = mainViewModel.state.value.operationInProgress ?: false

    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?,
    ) {
        super.onActivityResult(requestCode, resultCode, data)

        when (requestCode) {
            BaseGameActivity.REQUEST_PLAY_GAME -> {
                lifecycleScope.launch {
                    gameLaunchTaskHandler.handleGameFinish(
                        true,
                        this@MainActivity,
                        resultCode,
                        data,
                    )
                }
            }
        }
    }

    @dagger.Module
    abstract class Module {
        @dagger.Module
        companion object {
            @Provides
            @PerActivity
            @JvmStatic
            fun settingsInteractor(
                activity: MainActivity,
                directoriesManager: DirectoriesManager,
            ) = SettingsInteractor(activity, directoriesManager)

            @Provides
            @PerActivity
            @JvmStatic
            fun gameInteractor(
                activity: MainActivity,
                retrogradeDb: RetrogradeDatabase,
                shortcutsGenerator: ShortcutsGenerator,
                gameLauncher: GameLauncher,
            ): GameInteractor {
                val hasNoTouchscreen = !activity.packageManager
                    .hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
                return GameInteractor(activity, retrogradeDb, hasNoTouchscreen, shortcutsGenerator, gameLauncher)
            }
        }
    }
}
