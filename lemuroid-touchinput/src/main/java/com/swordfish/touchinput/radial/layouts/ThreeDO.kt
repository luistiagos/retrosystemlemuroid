package com.swordfish.touchinput.radial.layouts

import android.view.KeyEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import com.swordfish.touchinput.controller.R
import com.swordfish.touchinput.radial.controls.LemuroidControlCross
import com.swordfish.touchinput.radial.controls.LemuroidControlFaceButtons
import com.swordfish.touchinput.radial.layouts.shared.ComposeTouchLayouts
import com.swordfish.touchinput.radial.layouts.shared.SecondaryButtonL1
import com.swordfish.touchinput.radial.layouts.shared.SecondaryButtonMenu
import com.swordfish.touchinput.radial.layouts.shared.SecondaryButtonMenuPlaceholder
import com.swordfish.touchinput.radial.layouts.shared.SecondaryButtonR1
import com.swordfish.touchinput.radial.layouts.shared.SecondaryButtonSelect
import com.swordfish.touchinput.radial.layouts.shared.SecondaryButtonStart
import com.swordfish.touchinput.radial.settings.TouchControllerSettingsManager
import com.swordfish.touchinput.radial.ui.LemuroidButtonForeground
import gg.padkit.PadKitScope
import gg.padkit.ids.Id
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf

/**
 * 3DO Interactive Multiplayer controller layout (Opera core).
 *
 * Opera Libretro button mapping:
 *   RetroPad B (south / KEYCODE_BUTTON_B) → 3DO A
 *   RetroPad A (east  / KEYCODE_BUTTON_A) → 3DO B
 *   RetroPad Y (west  / KEYCODE_BUTTON_Y) → 3DO C
 *   RetroPad L (left bumper)              → 3DO L
 *   RetroPad R (right bumper)             → 3DO R
 *   RetroPad Start                        → 3DO Play/Pause (P)
 *   RetroPad Select                       → 3DO X (stop)
 */
@Composable
fun PadKitScope.ThreeDOLeft(
    modifier: Modifier = Modifier,
    settings: TouchControllerSettingsManager.Settings,
) {
    BaseLayoutLeft(
        settings = settings,
        modifier = modifier,
        primaryDial = { LemuroidControlCross(id = Id.DiscreteDirection(ComposeTouchLayouts.MOTION_SOURCE_DPAD)) },
        secondaryDials = {
            SecondaryButtonL1()
            SecondaryButtonSelect(position = 2)
            SecondaryButtonMenuPlaceholder(settings)
        },
    )
}

@Composable
fun PadKitScope.ThreeDORight(
    modifier: Modifier = Modifier,
    settings: TouchControllerSettingsManager.Settings,
) {
    BaseLayoutRight(
        settings = settings,
        modifier = modifier,
        // 3DO has 3 face buttons: A (south), B (east), C (west) — no north button
        primaryDial = {
            LemuroidControlFaceButtons(
                ids =
                    persistentListOf(
                        Id.Key(KeyEvent.KEYCODE_BUTTON_B), // south → 3DO A
                        Id.Key(KeyEvent.KEYCODE_BUTTON_A), // east  → 3DO B
                        Id.Key(KeyEvent.KEYCODE_BUTTON_Y), // west  → 3DO C
                    ),
                idsForegrounds =
                    persistentMapOf<Id.Key, @Composable (State<Boolean>) -> Unit>(
                        Id.Key(KeyEvent.KEYCODE_BUTTON_B) to {
                            LemuroidButtonForeground(
                                pressed = it,
                                icon = R.drawable.tdo_button_a,
                            )
                        },
                        Id.Key(KeyEvent.KEYCODE_BUTTON_A) to {
                            LemuroidButtonForeground(
                                pressed = it,
                                icon = R.drawable.tdo_button_b,
                            )
                        },
                        Id.Key(KeyEvent.KEYCODE_BUTTON_Y) to {
                            LemuroidButtonForeground(
                                pressed = it,
                                icon = R.drawable.tdo_button_c,
                            )
                        },
                    ),
            )
        },
        secondaryDials = {
            SecondaryButtonR1()
            SecondaryButtonStart(position = 2)
            SecondaryButtonMenu(settings)
        },
    )
}
