package com.swordfish.touchinput.radial.layouts

import android.view.KeyEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import com.swordfish.touchinput.radial.controls.LemuroidControlButton
import com.swordfish.touchinput.radial.controls.LemuroidControlCross
import com.swordfish.touchinput.radial.controls.LemuroidControlFaceButtons
import com.swordfish.touchinput.radial.layouts.shared.ComposeTouchLayouts
import com.swordfish.touchinput.radial.layouts.shared.SecondaryAnalogLeft
import com.swordfish.touchinput.radial.layouts.shared.SecondaryAnalogRight
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
 * GameCube controller layout (Dolphin core).
 *
 * Dolphin Libretro button mapping (verified in Source/Core/DolphinLibretro/Input.cpp):
 *   RetroPad A  (KEYCODE_BUTTON_A)  → GC A
 *   RetroPad B  (KEYCODE_BUTTON_B)  → GC B
 *   RetroPad X  (KEYCODE_BUTTON_X)  → GC X
 *   RetroPad Y  (KEYCODE_BUTTON_Y)  → GC Y
 *   RetroPad L2 (KEYCODE_BUTTON_L2) → GC L analog trigger
 *   RetroPad R2 (KEYCODE_BUTTON_R2) → GC R analog trigger
 *   RetroPad R  (KEYCODE_BUTTON_R1) → GC Z
 *   RetroPad START                  → GC Start
 *   Left Analog                     → GC main control stick
 *   Right Analog                    → GC C-stick
 *
 * The four face buttons are placed to approximate the real GameCube cluster:
 *   A at the bottom-centre (south), B to the left (west), X to the right (east),
 *   Y at the top (north).
 */
@Composable
fun PadKitScope.GameCubeLeft(
    modifier: Modifier = Modifier,
    settings: TouchControllerSettingsManager.Settings,
) {
    BaseLayoutLeft(
        settings = settings,
        modifier = modifier,
        // D-pad as primary dial
        primaryDial = { LemuroidControlCross(id = Id.DiscreteDirection(ComposeTouchLayouts.MOTION_SOURCE_DPAD)) },
        secondaryDials = {
            // GC L analog trigger → RetroPad L2
            LemuroidControlButton(
                modifier = Modifier.radialPosition(120f),
                id = Id.Key(KeyEvent.KEYCODE_BUTTON_L2),
                label = "L",
            )
            SecondaryButtonMenuPlaceholder(settings)
            // GC main control stick
            SecondaryAnalogLeft()
        },
    )
}

@Composable
fun PadKitScope.GameCubeRight(
    modifier: Modifier = Modifier,
    settings: TouchControllerSettingsManager.Settings,
) {
    BaseLayoutRight(
        settings = settings,
        modifier = modifier,
        // A/B/X/Y face buttons as primary dial.
        // Order in the list is: south, east, west, north.
        primaryDial = {
            LemuroidControlFaceButtons(
                ids =
                    persistentListOf(
                        Id.Key(KeyEvent.KEYCODE_BUTTON_A), // south → GC A
                        Id.Key(KeyEvent.KEYCODE_BUTTON_X), // east  → GC X
                        Id.Key(KeyEvent.KEYCODE_BUTTON_B), // west  → GC B
                        Id.Key(KeyEvent.KEYCODE_BUTTON_Y), // north → GC Y
                    ),
                idsForegrounds =
                    persistentMapOf<Id.Key, @Composable (State<Boolean>) -> Unit>(
                        Id.Key(KeyEvent.KEYCODE_BUTTON_A) to { LemuroidButtonForeground(pressed = it, label = "A") },
                        Id.Key(KeyEvent.KEYCODE_BUTTON_X) to { LemuroidButtonForeground(pressed = it, label = "X") },
                        Id.Key(KeyEvent.KEYCODE_BUTTON_B) to { LemuroidButtonForeground(pressed = it, label = "B") },
                        Id.Key(KeyEvent.KEYCODE_BUTTON_Y) to { LemuroidButtonForeground(pressed = it, label = "Y") },
                    ),
            )
        },
        secondaryDials = {
            // GC Z → RetroPad R
            LemuroidControlButton(
                modifier = Modifier.radialPosition(90f),
                id = Id.Key(KeyEvent.KEYCODE_BUTTON_R1),
                label = "Z",
            )
            // GC R analog trigger → RetroPad R2
            LemuroidControlButton(
                modifier = Modifier.radialPosition(60f),
                id = Id.Key(KeyEvent.KEYCODE_BUTTON_R2),
                label = "R",
            )
            SecondaryButtonStart(position = 2)
            // GC C-stick
            SecondaryAnalogRight()
            SecondaryButtonMenu(settings)
        },
    )
}
