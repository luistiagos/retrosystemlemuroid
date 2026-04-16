package com.swordfish.lemuroid.app.mobile.feature.settings.romset

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavController
import com.swordfish.lemuroid.R
import com.swordfish.lemuroid.app.mobile.feature.main.MainRoute
import com.swordfish.lemuroid.app.mobile.feature.main.navigateToRoute
import com.swordfish.lemuroid.app.utils.android.settings.LemuroidCardSettingsGroup
import com.swordfish.lemuroid.app.utils.android.settings.LemuroidSettingsMenuLink
import com.swordfish.lemuroid.app.utils.android.settings.LemuroidSettingsPage

@Composable
fun RomsetSettingsScreen(
    modifier: Modifier = Modifier,
    navController: NavController,
) {
    LemuroidSettingsPage(modifier = modifier.fillMaxSize()) {
        LemuroidCardSettingsGroup(
            title = { Text(text = stringResource(R.string.romset_title)) },
        ) {
            LemuroidSettingsMenuLink(
                title = { Text(text = stringResource(R.string.romset_export_title)) },
                subtitle = { Text(text = stringResource(R.string.romset_export_description)) },
                onClick = { navController.navigateToRoute(MainRoute.SETTINGS_ROMSET_EXPORT) },
            )
            LemuroidSettingsMenuLink(
                title = { Text(text = stringResource(R.string.romset_import_title)) },
                subtitle = { Text(text = stringResource(R.string.romset_import_description)) },
                onClick = { navController.navigateToRoute(MainRoute.SETTINGS_ROMSET_IMPORT) },
            )
        }
    }
}
