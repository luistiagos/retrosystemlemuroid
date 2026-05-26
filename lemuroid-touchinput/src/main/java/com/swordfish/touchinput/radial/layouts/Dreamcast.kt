package com.swordfish.touchinput.radial.layouts

import android.view.KeyEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import com.swordfish.touchinput.controller.R
import com.swordfish.touchinput.radial.controls.LemuroidControlCross
import com.swordfish.touchinput.radial.controls.LemuroidControlFaceButtons
import com.swordfish.touchinput.radial.layouts.shared.ComposeTouchLayouts
import com.swordfish.touchinput.radial.layouts.shared.SecondaryAnalogLeft
import com.swordfish.touchinput.radial.layouts.shared.SecondaryButtonL2
import com.swordfish.touchinput.radial.layouts.shared.SecondaryButtonMenu
import com.swordfish.touchinput.radial.layouts.shared.SecondaryButtonMenuPlaceholder
import com.swordfish.touchinput.radial.layouts.shared.SecondaryButtonR2
import com.swordfish.touchinput.radial.layouts.shared.SecondaryButtonSelect
import com.swordfish.touchinput.radial.layouts.shared.SecondaryButtonStart
import com.swordfish.touchinput.radial.settings.TouchControllerSettingsManager
import com.swordfish.touchinput.radial.ui.LemuroidButtonForeground
import gg.padkit.PadKitScope
import gg.padkit.ids.Id
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf

/**
 * Dreamcast controller layout (Flycast core).
 *
 * Flycast Libretro button mapping:
 *   RetroPad B (south / KEYCODE_BUTTON_B) → DC A
 *   RetroPad A (east  / KEYCODE_BUTTON_A) → DC B
 *   RetroPad Y (west  / KEYCODE_BUTTON_Y) → DC X
 *   RetroPad X (north / KEYCODE_BUTTON_X) → DC Y
 *   RetroPad L2                           → DC L trigger
 *   RetroPad R2                           → DC R trigger
 *   Left Analog                           → DC Analog thumb stick
 */
@Composable
fun PadKitScope.DreamcastLeft(
    modifier: Modifier = Modifier,
    settings: TouchControllerSettingsManager.Settings,
) {
    BaseLayoutLeft(
        settings = settings,
        modifier = modifier,
        // D-pad as primary dial
        primaryDial = { LemuroidControlCross(id = Id.DiscreteDirection(ComposeTouchLayouts.MOTION_SOURCE_DPAD)) },
        secondaryDials = {
            // L trigger (maps to L2 in Libretro → Flycast DC L trigger)
            SecondaryButtonL2()
            // Start here instead of Select — DC has no Select button
            SecondaryButtonSelect(position = 2)
            SecondaryButtonMenuPlaceholder(settings)
            // Analog stick (DC thumb stick)
            SecondaryAnalogLeft()
        },
    )
}

@Composable
fun PadKitScope.DreamcastRight(
    modifier: Modifier = Modifier,
    settings: TouchControllerSettingsManager.Settings,
) {
    BaseLayoutRight(
        settings = settings,
        modifier = modifier,
        // A/B/X/Y face buttons as primary dial
        // Order: south=A, east=B, west=X, north=Y  (Flycast mapping)
        primaryDial = {
            LemuroidControlFaceButtons(
                ids =
                    persistentListOf(
                        Id.Key(KeyEvent.KEYCODE_BUTTON_B), // south → DC A
                        Id.Key(KeyEvent.KEYCODE_BUTTON_A), // east  → DC B
                        Id.Key(KeyEvent.KEYCODE_BUTTON_Y), // west  → DC X
                        Id.Key(KeyEvent.KEYCODE_BUTTON_X), // north → DC Y
                    ),
                idsForegrounds =
                    persistentMapOf<Id.Key, @Composable (State<Boolean>) -> Unit>(
                        Id.Key(KeyEvent.KEYCODE_BUTTON_B) to {
                            LemuroidButtonForeground(
                                pressed = it,
                                icon = R.drawable.dc_button_a,
                            )
                        },
                        Id.Key(KeyEvent.KEYCODE_BUTTON_A) to {
                            LemuroidButtonForeground(
                                pressed = it,
                                icon = R.drawable.dc_button_b,
                            )
                        },
                        Id.Key(KeyEvent.KEYCODE_BUTTON_Y) to {
                            LemuroidButtonForeground(
                                pressed = it,
                                icon = R.drawable.dc_button_x,
                            )
                        },
                        Id.Key(KeyEvent.KEYCODE_BUTTON_X) to {
                            LemuroidButtonForeground(
                                pressed = it,
                                icon = R.drawable.dc_button_y,
                            )
                        },
                    ),
            )
        },
        secondaryDials = {
            // R trigger (maps to R2 in Libretro → Flycast DC R trigger)
            SecondaryButtonR2()
            SecondaryButtonStart(position = 2)
            SecondaryButtonMenu(settings)
        },
    )
}
