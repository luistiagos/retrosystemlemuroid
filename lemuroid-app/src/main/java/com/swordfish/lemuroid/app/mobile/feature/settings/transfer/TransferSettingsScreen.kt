package com.swordfish.lemuroid.app.mobile.feature.settings.transfer

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
fun TransferSettingsScreen(
    modifier: Modifier = Modifier,
    navController: NavController,
) {
    LemuroidSettingsPage(modifier = modifier.fillMaxSize()) {
        LemuroidCardSettingsGroup(
            title = { Text(text = stringResource(R.string.transfer_title)) },
        ) {
            LemuroidSettingsMenuLink(
                title = { Text(text = stringResource(R.string.transfer_export_title)) },
                subtitle = { Text(text = stringResource(R.string.transfer_export_description)) },
                onClick = { navController.navigateToRoute(MainRoute.SETTINGS_TRANSFER_EXPORT) },
            )
            LemuroidSettingsMenuLink(
                title = { Text(text = stringResource(R.string.transfer_import_title)) },
                subtitle = { Text(text = stringResource(R.string.transfer_import_description)) },
                onClick = { navController.navigateToRoute(MainRoute.SETTINGS_TRANSFER_IMPORT) },
            )
        }
    }
}
