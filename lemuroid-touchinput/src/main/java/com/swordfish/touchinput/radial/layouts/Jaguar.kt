package com.swordfish.touchinput.radial.layouts

import android.view.KeyEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import com.swordfish.touchinput.radial.controls.LemuroidControlCross
import com.swordfish.touchinput.radial.controls.LemuroidControlFaceButtons
import com.swordfish.touchinput.radial.layouts.shared.ComposeTouchLayouts
import com.swordfish.touchinput.radial.layouts.shared.SecondaryButtonMenu
import com.swordfish.touchinput.radial.layouts.shared.SecondaryButtonMenuPlaceholder
import com.swordfish.touchinput.radial.layouts.shared.SecondaryButtonSelect
import com.swordfish.touchinput.radial.layouts.shared.SecondaryButtonStart
import com.swordfish.touchinput.radial.settings.TouchControllerSettingsManager
import com.swordfish.touchinput.radial.ui.LemuroidButtonForeground
import gg.padkit.PadKitScope
import gg.padkit.ids.Id
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf

/**
 * Atari Jaguar controller layout (Virtual Jaguar core).
 *
 * The Jaguar pad has three primary face buttons (A B C), an Option and a Pause button,
 * plus a numeric keypad (rarely used; reachable via a physical controller). Virtual Jaguar
 * maps RetroPad → Jaguar (verified in libretro.c retro_input_descriptor):
 *   RetroPad A      (KEYCODE_BUTTON_A)      → Jaguar A
 *   RetroPad B      (KEYCODE_BUTTON_B)      → Jaguar B
 *   RetroPad Y      (KEYCODE_BUTTON_Y)      → Jaguar C
 *   RetroPad START  (KEYCODE_BUTTON_START)  → Jaguar Option
 *   RetroPad SELECT (KEYCODE_BUTTON_SELECT) → Jaguar Pause
 */
@Composable
fun PadKitScope.JaguarLeft(
    modifier: Modifier = Modifier,
    settings: TouchControllerSettingsManager.Settings,
) {
    BaseLayoutLeft(
        settings = settings,
        modifier = modifier,
        primaryDial = { LemuroidControlCross(id = Id.DiscreteDirection(ComposeTouchLayouts.MOTION_SOURCE_DPAD)) },
        secondaryDials = {
            // Jaguar Pause → RetroPad Select
            SecondaryButtonSelect(position = 0)
            SecondaryButtonMenuPlaceholder(settings)
        },
    )
}

@Composable
fun PadKitScope.JaguarRight(
    modifier: Modifier = Modifier,
    settings: TouchControllerSettingsManager.Settings,
) {
    BaseLayoutRight(
        settings = settings,
        modifier = modifier,
        primaryDial = {
            LemuroidControlFaceButtons(
                rotationInDegrees = -30f,
                ids =
                    persistentListOf(
                        Id.Key(KeyEvent.KEYCODE_BUTTON_A), // Jaguar A
                        Id.Key(KeyEvent.KEYCODE_BUTTON_B), // Jaguar B
                        Id.Key(KeyEvent.KEYCODE_BUTTON_Y), // Jaguar C
                    ),
                idsForegrounds =
                    persistentMapOf<Id.Key, @Composable (State<Boolean>) -> Unit>(
                        Id.Key(KeyEvent.KEYCODE_BUTTON_A) to { LemuroidButtonForeground(pressed = it, label = "A") },
                        Id.Key(KeyEvent.KEYCODE_BUTTON_B) to { LemuroidButtonForeground(pressed = it, label = "B") },
                        Id.Key(KeyEvent.KEYCODE_BUTTON_Y) to { LemuroidButtonForeground(pressed = it, label = "C") },
                    ),
            )
        },
        secondaryDials = {
            // Jaguar Option → RetroPad Start
            SecondaryButtonStart(position = 0)
            SecondaryButtonMenu(settings)
        },
    )
}
