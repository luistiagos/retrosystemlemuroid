package com.swordfish.touchinput.radial.layouts

import android.view.KeyEvent
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import com.swordfish.touchinput.radial.controls.LemuroidControlCross
import com.swordfish.touchinput.radial.controls.LemuroidControlFaceButtons
import com.swordfish.touchinput.radial.layouts.shared.ComposeTouchLayouts
import com.swordfish.touchinput.radial.layouts.shared.SecondaryButtonMenu
import com.swordfish.touchinput.radial.layouts.shared.SecondaryButtonMenuPlaceholder
import com.swordfish.touchinput.radial.layouts.shared.SecondaryButtonStart
import com.swordfish.touchinput.radial.settings.TouchControllerSettingsManager
import com.swordfish.touchinput.radial.ui.LemuroidButtonForeground
import gg.padkit.PadKitScope
import gg.padkit.ids.Id
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf

/**
 * Amstrad GX4000 controller layout (cap32 core).
 *
 * The GX4000 is a cartridge console built on the Amstrad CPC Plus hardware. Its pad has a
 * d-pad plus two fire buttons (1 and 2) and a separate Pause button. Unlike the Amstrad CPC
 * computer there is NO keyboard, so this layout drops the virtual-keyboard combo button.
 *
 * RetroPad → GX4000 mapping (cap32, identical to the Amstrad CPC joystick):
 *   RetroPad B (KEYCODE_BUTTON_B) -> Amstrad fire 1  (shown as "1")
 *   RetroPad A (KEYCODE_BUTTON_A) -> Amstrad fire 2  (shown as "2")
 *   RetroPad START                -> Pause
 */
@Composable
fun PadKitScope.AmstradGX4000Left(
    modifier: Modifier = Modifier,
    settings: TouchControllerSettingsManager.Settings,
) {
    BaseLayoutLeft(
        settings = settings,
        modifier = modifier,
        primaryDial = { LemuroidControlCross(id = Id.DiscreteDirection(ComposeTouchLayouts.MOTION_SOURCE_DPAD)) },
        secondaryDials = {
            Box(modifier = Modifier.radialPosition(120f))
            SecondaryButtonMenuPlaceholder(settings)
        },
    )
}

@Composable
fun PadKitScope.AmstradGX4000Right(
    modifier: Modifier = Modifier,
    settings: TouchControllerSettingsManager.Settings,
) {
    BaseLayoutRight(
        settings = settings,
        modifier = modifier,
        primaryDial = {
            LemuroidControlFaceButtons(
                ids =
                    persistentListOf(
                        Id.Key(KeyEvent.KEYCODE_BUTTON_A),
                        Id.Key(KeyEvent.KEYCODE_BUTTON_B),
                    ),
                idsForegrounds =
                    persistentMapOf<Id.Key, @Composable (State<Boolean>) -> Unit>(
                        Id.Key(KeyEvent.KEYCODE_BUTTON_A) to { LemuroidButtonForeground(pressed = it, label = "2") },
                        Id.Key(KeyEvent.KEYCODE_BUTTON_B) to { LemuroidButtonForeground(pressed = it, label = "1") },
                    ),
            )
        },
        secondaryDials = {
            SecondaryButtonStart()
            SecondaryButtonMenu(settings)
        },
    )
}
