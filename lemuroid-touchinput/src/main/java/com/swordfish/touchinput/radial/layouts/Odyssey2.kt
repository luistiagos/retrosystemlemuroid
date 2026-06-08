package com.swordfish.touchinput.radial.layouts

import android.view.KeyEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import com.swordfish.touchinput.radial.controls.LemuroidControlButton
import com.swordfish.touchinput.radial.controls.LemuroidControlCross
import com.swordfish.touchinput.radial.controls.LemuroidControlFaceButtons
import com.swordfish.touchinput.radial.layouts.shared.ComposeTouchLayouts
import com.swordfish.touchinput.radial.layouts.shared.SecondaryButtonMenu
import com.swordfish.touchinput.radial.layouts.shared.SecondaryButtonMenuPlaceholder
import com.swordfish.touchinput.radial.settings.TouchControllerSettingsManager
import com.swordfish.touchinput.radial.ui.LemuroidButtonForeground
import gg.padkit.PadKitScope
import gg.padkit.ids.Id
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf

/**
 * Magnavox Odyssey² controller layout (O2EM core).
 *
 * The Odyssey² joystick has a single Action (fire) button; the console's alphanumeric
 * keyboard is reached through O2EM's on-screen virtual keyboard. O2EM does not call
 * SET_CONTROLLER_INFO and maps RetroPad → Odyssey² (verified in libretro.c desc[]):
 *   RetroPad B      (KEYCODE_BUTTON_B)      → Action (fire)
 *   RetroPad SELECT (KEYCODE_BUTTON_SELECT) → Show/Hide the virtual keyboard
 *   RetroPad Y      → Move virtual keyboard; X / L..R3 → numeric keys 0-6
 *
 * The touch layout exposes the D-pad, the Action button, and a "KB" toggle that shows the
 * virtual keyboard (then keys are tapped via the screen). The numeric keys remain reachable
 * with a physical controller.
 */
@Composable
fun PadKitScope.Odyssey2Left(
    modifier: Modifier = Modifier,
    settings: TouchControllerSettingsManager.Settings,
) {
    BaseLayoutLeft(
        settings = settings,
        modifier = modifier,
        primaryDial = { LemuroidControlCross(id = Id.DiscreteDirection(ComposeTouchLayouts.MOTION_SOURCE_DPAD)) },
        secondaryDials = {
            // Show/Hide the virtual keyboard → RetroPad Select
            LemuroidControlButton(
                modifier = Modifier.radialPosition(120f),
                id = Id.Key(KeyEvent.KEYCODE_BUTTON_SELECT),
                label = "KB",
            )
            SecondaryButtonMenuPlaceholder(settings)
        },
    )
}

@Composable
fun PadKitScope.Odyssey2Right(
    modifier: Modifier = Modifier,
    settings: TouchControllerSettingsManager.Settings,
) {
    BaseLayoutRight(
        settings = settings,
        modifier = modifier,
        primaryDial = {
            LemuroidControlFaceButtons(
                ids = persistentListOf(Id.Key(KeyEvent.KEYCODE_BUTTON_B)),
                includeComposite = false,
                idsForegrounds =
                    persistentMapOf<Id.Key, @Composable (State<Boolean>) -> Unit>(
                        Id.Key(KeyEvent.KEYCODE_BUTTON_B) to { LemuroidButtonForeground(pressed = it) },
                    ),
            )
        },
        secondaryDials = {
            SecondaryButtonMenu(settings)
        },
    )
}
