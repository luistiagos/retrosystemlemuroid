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
import com.swordfish.touchinput.radial.layouts.shared.SecondaryButtonSelect
import com.swordfish.touchinput.radial.layouts.shared.SecondaryButtonStart
import com.swordfish.touchinput.radial.settings.TouchControllerSettingsManager
import com.swordfish.touchinput.radial.ui.LemuroidButtonForeground
import gg.padkit.PadKitScope
import gg.padkit.ids.Id
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf

/**
 * MSX / MSX2 controller layout (fMSX core, "Joystick + Emulated Keyboard" device 0x101).
 *
 * Verified in libfmsx_libretro_android.so (retro_input_descriptor / retro_run):
 *   D-pad                -> Stick Up/Down/Left/Right
 *   RetroPad A (east)    -> Fire A
 *   RetroPad B (south)   -> Fire B
 *   RetroPad Y (west)    -> Space (starts/jumps/fires in many MSX titles)
 *   RetroPad X (north)   -> F3
 *   RetroPad START       -> F1 (Start/Pause in many MSX games)
 *   RetroPad SELECT      -> F2
 *   RetroPad L1          -> F4
 *   RetroPad R1          -> F5
 *   RetroPad THUMBL (L3) -> Enter (Return)
 */
@Composable
fun PadKitScope.MSXLeft(
    modifier: Modifier = Modifier,
    settings: TouchControllerSettingsManager.Settings,
) {
    BaseLayoutLeft(
        settings = settings,
        modifier = modifier,
        primaryDial = { LemuroidControlCross(id = Id.DiscreteDirection(ComposeTouchLayouts.MOTION_SOURCE_DPAD)) },
        secondaryDials = {
            LemuroidControlButton(
                modifier = Modifier.radialPosition(120f),
                id = Id.Key(KeyEvent.KEYCODE_BUTTON_L1),
                label = "F4",
            )
            SecondaryButtonSelect(position = 2)
            LemuroidControlButton(
                modifier =
                    Modifier.radialPosition(
                        -120f - 2f * settings.rotation * TouchControllerSettingsManager.MAX_ROTATION,
                    ),
                id = Id.Key(KeyEvent.KEYCODE_BUTTON_THUMBL),
                label = "ENT",
            )
        },
    )
}

@Composable
fun PadKitScope.MSXRight(
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
                        Id.Key(KeyEvent.KEYCODE_BUTTON_Y),
                        Id.Key(KeyEvent.KEYCODE_BUTTON_X),
                    ),
                idsForegrounds =
                    persistentMapOf<Id.Key, @Composable (State<Boolean>) -> Unit>(
                        Id.Key(KeyEvent.KEYCODE_BUTTON_A) to { LemuroidButtonForeground(pressed = it, label = "A") },
                        Id.Key(KeyEvent.KEYCODE_BUTTON_B) to { LemuroidButtonForeground(pressed = it, label = "B") },
                        Id.Key(KeyEvent.KEYCODE_BUTTON_Y) to { LemuroidButtonForeground(pressed = it, label = "SPC") },
                        Id.Key(KeyEvent.KEYCODE_BUTTON_X) to { LemuroidButtonForeground(pressed = it, label = "F3") },
                    ),
            )
        },
        secondaryDials = {
            LemuroidControlButton(
                modifier = Modifier.radialPosition(60f),
                id = Id.Key(KeyEvent.KEYCODE_BUTTON_R1),
                label = "F5",
            )
            SecondaryButtonStart(position = 2)
            SecondaryButtonMenu(settings)
        },
    )
}
