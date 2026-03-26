package com.swordfish.lemuroid.app.mobile.feature.settings.general

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import androidx.navigation.NavController
import com.swordfish.lemuroid.R
import com.swordfish.lemuroid.app.mobile.feature.main.MainRoute
import com.swordfish.lemuroid.app.mobile.feature.main.navigateToRoute
import com.swordfish.lemuroid.app.shared.library.LibraryIndexScheduler
import com.swordfish.lemuroid.app.utils.android.settings.LemuroidCardSettingsGroup
import com.swordfish.lemuroid.app.utils.android.settings.LemuroidSettingsList
import com.swordfish.lemuroid.app.utils.android.settings.LemuroidSettingsMenuLink
import com.swordfish.lemuroid.app.utils.android.settings.LemuroidSettingsPage
import com.swordfish.lemuroid.app.utils.android.settings.LemuroidSettingsSlider
import com.swordfish.lemuroid.app.utils.android.settings.LemuroidSettingsSwitch
import com.swordfish.lemuroid.app.utils.android.settings.booleanPreferenceState
import com.swordfish.lemuroid.app.utils.android.settings.indexPreferenceState
import com.swordfish.lemuroid.app.utils.android.settings.intPreferenceState
import com.swordfish.lemuroid.app.utils.android.stringListResource
import com.swordfish.lemuroid.app.utils.settings.rememberSafePreferenceIndexSettingState
import com.swordfish.lemuroid.app.shared.roms.DownloadRomsState
import com.swordfish.lemuroid.lib.preferences.LocaleHelper

@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel,
    navController: NavController,
) {
    val state =
        viewModel.uiState
            .collectAsState(SettingsViewModel.State())
            .value

    val scanInProgress =
        viewModel.directoryScanInProgress
            .collectAsState(false)
            .value

    val indexingInProgress =
        viewModel.indexingInProgress
            .collectAsState(false)
            .value

    val downloadRomsState =
        viewModel.downloadRomsState
            .collectAsState(DownloadRomsState.Idle)
            .value

    LemuroidSettingsPage(modifier = modifier) {
        RomsSettings(
            state = state,
            onChangeFolder = { viewModel.changeLocalStorageFolder() },
            indexingInProgress = indexingInProgress,
            scanInProgress = scanInProgress,
            downloadRomsState = downloadRomsState,
            onDownloadRomsClicked = { viewModel.downloadAndExtractRoms() },
            smartStorageUsingRemovable = state.smartStorageUsingRemovable,
            smartStorageUserOverride = state.smartStorageUserOverride,
            smartStorageVolumes = state.smartStorageVolumes,
        )
        GeneralSettings()
        InputSettings(navController = navController)
        MiscSettings(
            indexingInProgress = indexingInProgress,
            isSaveSyncSupported = state.isSaveSyncSupported,
            navController = navController,
        )
    }
}

@Composable
private fun MiscSettings(
    indexingInProgress: Boolean,
    isSaveSyncSupported: Boolean,
    navController: NavController,
) {
    LemuroidCardSettingsGroup(
        title = { Text(text = stringResource(id = R.string.settings_category_misc)) },
    ) {
        if (isSaveSyncSupported) {
            LemuroidSettingsMenuLink(
                title = { Text(text = stringResource(id = R.string.settings_title_save_sync)) },
                subtitle = {
                    Text(text = stringResource(id = R.string.settings_description_save_sync))
                },
                onClick = { navController.navigateToRoute(MainRoute.SETTINGS_SAVE_SYNC) },
            )
        }
        LemuroidSettingsMenuLink(
            title = { Text(text = stringResource(id = R.string.settings_title_open_cores_selection)) },
            subtitle = {
                Text(text = stringResource(id = R.string.settings_description_open_cores_selection))
            },
            onClick = { navController.navigateToRoute(MainRoute.SETTINGS_CORES_SELECTION) },
        )
        LemuroidSettingsMenuLink(
            title = { Text(text = stringResource(id = R.string.settings_title_display_bios_info)) },
            subtitle = {
                Text(text = stringResource(id = R.string.settings_description_display_bios_info))
            },
            enabled = !indexingInProgress,
            onClick = { navController.navigateToRoute(MainRoute.SETTINGS_BIOS) },
        )
        LemuroidSettingsMenuLink(
            title = { Text(text = stringResource(id = R.string.settings_title_advanced_settings)) },
            subtitle = {
                Text(text = stringResource(id = R.string.settings_description_advanced_settings))
            },
            onClick = { navController.navigateToRoute(MainRoute.SETTINGS_ADVANCED) },
        )
    }
}

@Composable
private fun InputSettings(navController: NavController) {
    LemuroidCardSettingsGroup(
        title = { Text(text = stringResource(id = R.string.settings_category_input)) },
    ) {
        LemuroidSettingsList(
            state =
                indexPreferenceState(
                    R.string.pref_key_haptic_feedback_mode,
                    "press",
                    stringListResource(R.array.pref_key_haptic_feedback_mode_values),
                ),
            title = {
                Text(text = stringResource(id = R.string.settings_title_enable_touch_feedback))
            },
            items = stringListResource(R.array.pref_key_haptic_feedback_mode_display_names),
        )
        LemuroidSettingsMenuLink(
            title = { Text(text = stringResource(id = R.string.settings_title_gamepad_settings)) },
            subtitle = {
                Text(text = stringResource(id = R.string.settings_description_gamepad_settings))
            },
            onClick = { navController.navigateToRoute(MainRoute.SETTINGS_INPUT_DEVICES) },
        )
    }
}

@Composable
private fun GeneralSettings() {
    val context = LocalContext.current
    val hdMode = booleanPreferenceState(R.string.pref_key_hd_mode, false)
    val immersiveMode = booleanPreferenceState(R.string.pref_key_enable_immersive_mode, false)

    val languageValues = LocaleHelper.ALL_VALUES
    val languageState = rememberSafePreferenceIndexSettingState(
        key = LocaleHelper.PREF_KEY,
        values = languageValues,
        defaultValue = LocaleHelper.VALUE_SYSTEM,
        preferences = LocaleHelper.getSharedPreferences(context),
    )
    val languageDisplayNames = listOf(
        stringResource(R.string.language_system),
        stringResource(R.string.language_en),
        stringResource(R.string.language_pt),
    )

    LemuroidCardSettingsGroup(
        title = { Text(text = stringResource(id = R.string.settings_category_general)) },
    ) {
        LemuroidSettingsSwitch(
            state = booleanPreferenceState(R.string.pref_key_autosave, true),
            title = { Text(text = stringResource(id = R.string.settings_title_enable_autosave)) },
            subtitle = { Text(text = stringResource(id = R.string.settings_description_enable_autosave)) },
        )
        LemuroidSettingsSwitch(
            state = immersiveMode,
            title = { Text(text = stringResource(id = R.string.settings_title_immersive_mode)) },
            subtitle = { Text(text = stringResource(id = R.string.settings_description_immersive_mode)) },
        )
        LemuroidSettingsSwitch(
            state = hdMode,
            title = { Text(text = stringResource(id = R.string.settings_title_hd_mode)) },
            subtitle = { Text(text = stringResource(id = R.string.settings_description_hd_mode)) },
        )
        LemuroidSettingsSlider(
            enabled = hdMode.value,
            state =
                intPreferenceState(
                    key = stringResource(id = R.string.pref_key_hd_mode_quality),
                    default = 2,
                ),
            steps = 1,
            valueRange = 0f..2f,
            title = { Text(text = stringResource(R.string.settings_title_hd_quality)) },
            subtitle = { Text(text = stringResource(id = R.string.settings_description_hd_quality)) },
        )
        LemuroidSettingsList(
            enabled = !hdMode.value,
            state =
                indexPreferenceState(
                    R.string.pref_key_shader_filter,
                    "auto",
                    stringListResource(R.array.pref_key_shader_filter_values).toList(),
                ),
            title = { Text(text = stringResource(id = R.string.display_filter)) },
            items = stringListResource(R.array.pref_key_shader_filter_display_names),
        )
        LemuroidSettingsList(
            state = languageState,
            title = { Text(text = stringResource(id = R.string.settings_title_language)) },
            subtitle = { Text(text = stringResource(id = R.string.settings_description_language)) },
            items = languageDisplayNames,
            useSelectedValueAsSubtitle = false,
            onItemSelected = { index, _ ->
                LocaleHelper.setLanguage(context, languageValues[index])
                (context as? Activity)?.recreate()
            },
        )
    }
}

@Composable
private fun RomsSettings(
    state: SettingsViewModel.State,
    onChangeFolder: () -> Unit,
    indexingInProgress: Boolean,
    scanInProgress: Boolean,
    downloadRomsState: DownloadRomsState,
    onDownloadRomsClicked: () -> Unit,
    smartStorageUsingRemovable: Boolean,
    smartStorageUserOverride: Boolean,
    smartStorageVolumes: List<com.swordfish.lemuroid.lib.storage.SmartStoragePicker.VolumeInfo>,
) {
    val context = LocalContext.current

    val currentDirectory = state.currentDirectory
    val emptyDirectory = stringResource(R.string.none)

    val currentDirectoryName =
        remember(state.currentDirectory) {
            runCatching {
                DocumentFile.fromTreeUri(context, Uri.parse(currentDirectory))?.name
            }.getOrNull() ?: emptyDirectory
        }

    LemuroidCardSettingsGroup(title = { Text(text = stringResource(id = R.string.roms)) }) {
        LemuroidSettingsMenuLink(
            title = { Text(text = stringResource(id = R.string.directory)) },
            subtitle = { Text(text = currentDirectoryName) },
            onClick = { onChangeFolder() },
            enabled = !indexingInProgress,
        )
        if (scanInProgress) {
            LemuroidSettingsMenuLink(
                title = { Text(text = stringResource(id = R.string.stop)) },
                onClick = { LibraryIndexScheduler.cancelLibrarySync(context) },
            )
        } else {
            LemuroidSettingsMenuLink(
                title = { Text(text = stringResource(id = R.string.rescan)) },
                onClick = { LibraryIndexScheduler.scheduleLibrarySync(context) },
                enabled = !indexingInProgress,
            )
        }
        val storageProvidersPrefs = context.getSharedPreferences(com.swordfish.lemuroid.lib.storage.StorageProviderRegistry.PREF_NAME, android.content.Context.MODE_PRIVATE)

        LemuroidSettingsSwitch(
            state = booleanPreferenceState("all_files", false, storageProvidersPrefs),
            title = { Text(text = stringResource(id = com.swordfish.lemuroid.lib.R.string.all_files_storage)) },
            subtitle = { Text(text = stringResource(id = com.swordfish.lemuroid.lib.R.string.all_files_storage_desc)) },
            onCheckedChange = { isChecked ->
                if (isChecked && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    if (!Environment.isExternalStorageManager()) {
                        try {
                            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                                data = Uri.parse("package:${context.packageName}")
                            }
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                            context.startActivity(intent)
                        }
                    }
                }
            }
        )

        // Smart storage info — only shown when the user has not selected a custom directory
        // OR when a removable volume was auto-selected (always informative in that case).
        SmartStorageInfoItem(
            usingRemovable = smartStorageUsingRemovable,
            userOverride = smartStorageUserOverride,
            volumes = smartStorageVolumes,
        )

        // Old batch-download settings entry hidden; streaming is now the main provider.
        // LemuroidSettingsMenuLink(title = settings_download_roms_title, ...) removed from UI.
    }
}

@Composable
private fun SmartStorageInfoItem(
    usingRemovable: Boolean,
    userOverride: Boolean,
    volumes: List<com.swordfish.lemuroid.lib.storage.SmartStoragePicker.VolumeInfo>,
) {
    // Only show the card when there is something noteworthy to display:
    // • a removable volume was auto-selected, OR
    // • multiple volumes exist even if the user overrode the selection
    val hasMultipleVolumes = volumes.size > 1
    if (!hasMultipleVolumes && !usingRemovable) return

    val subtitle = when {
        userOverride -> stringResource(R.string.settings_smart_storage_user_override)
        usingRemovable -> {
            val removableVol = volumes.firstOrNull { it.isRemovable }
            if (removableVol != null)
                stringResource(R.string.settings_smart_storage_active_removable, removableVol.freeSpaceMB)
            else
                stringResource(R.string.settings_smart_storage_desc)
        }
        else -> {
            val primaryVol = volumes.firstOrNull { !it.isRemovable }
            if (primaryVol != null)
                stringResource(R.string.settings_smart_storage_active_internal, primaryVol.freeSpaceMB)
            else
                stringResource(R.string.settings_smart_storage_desc)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = stringResource(R.string.settings_smart_storage_title),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
