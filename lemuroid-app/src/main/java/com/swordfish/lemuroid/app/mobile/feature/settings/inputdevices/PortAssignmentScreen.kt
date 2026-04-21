package com.swordfish.lemuroid.app.mobile.feature.settings.inputdevices

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.swordfish.lemuroid.R
import com.swordfish.lemuroid.app.utils.android.settings.LemuroidCardSettingsGroup
import com.swordfish.lemuroid.app.utils.android.settings.LemuroidSettingsMenuLink
import com.swordfish.lemuroid.app.utils.android.settings.LemuroidSettingsPage

@Composable
fun PortAssignmentScreen(
    modifier: Modifier = Modifier,
    viewModel: PortAssignmentViewModel,
) {
    val state = viewModel.uiState.collectAsState(PortAssignmentViewModel.State()).value

    LemuroidSettingsPage(modifier = modifier.fillMaxSize()) {
        if (state.orderedDevices.isEmpty()) {
            LemuroidCardSettingsGroup {
                Text(
                    text = stringResource(R.string.settings_port_assignment_no_devices),
                    modifier = Modifier.padding(16.dp),
                )
            }
        } else {
            LemuroidCardSettingsGroup(
                title = { Text(text = stringResource(R.string.settings_port_assignment_group_title)) },
            ) {
                state.orderedDevices.forEachIndexed { index, device ->
                    PortEntryRow(
                        portIndex = index,
                        deviceName = device.name,
                        canMoveUp = index > 0,
                        canMoveDown = index < state.orderedDevices.size - 1,
                        onMoveUp = { viewModel.moveUp(index) },
                        onMoveDown = { viewModel.moveDown(index) },
                    )
                }
            }
        }

        LemuroidCardSettingsGroup(
            title = { Text(text = stringResource(R.string.settings_gamepad_category_general)) },
        ) {
            LemuroidSettingsMenuLink(
                title = { Text(text = stringResource(R.string.settings_port_assignment_reset)) },
                subtitle = { Text(text = stringResource(R.string.settings_port_assignment_reset_subtitle)) },
                onClick = { viewModel.resetOrder() },
            )
        }
    }
}

@Composable
private fun PortEntryRow(
    portIndex: Int,
    deviceName: String,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
) {
    LemuroidSettingsMenuLink(
        title = { Text(text = stringResource(R.string.settings_port_assignment_player, portIndex + 1)) },
        subtitle = { Text(text = deviceName) },
        action = {
            Row(
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onMoveUp, enabled = canMoveUp) {
                    Icon(Icons.Filled.KeyboardArrowUp, contentDescription = null)
                }
                IconButton(onClick = onMoveDown, enabled = canMoveDown) {
                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null)
                }
            }
        },
        onClick = {},
    )
}
