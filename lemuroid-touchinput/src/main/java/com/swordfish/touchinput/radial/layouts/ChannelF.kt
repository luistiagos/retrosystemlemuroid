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
 * Fairchild Channel F controller layout (FreeChaF core).
 *
 * The Channel F controller is a single plunger/twist grip: it moves in 8 directions and can be
 * pushed down (fire), pulled up, and twisted left/right. FreeChaF maps these to the RetroPad as
 * (verified in the core's retro_input_descriptor in src/libretro.c):
 *
 *   UP/DOWN/LEFT/RIGHT          -> forward / back / left / right
 *   RetroPad B (south)          -> push (fire)        shown as "▼"
 *   RetroPad X (north)          -> pull               shown as "▲"
 *   RetroPad A (east)           -> rotate right        shown as "↻"
 *   RetroPad Y (west)           -> rotate left         shown as "↺"
 *   RetroPad SELECT             -> swap left/right controllers
 *   RetroPad START              -> toggle console/controller input (console panel buttons)
 *
 * The four face buttons keep the standard RetroPad diamond positions, which lines up naturally
 * with the plunger: push/fire at the bottom, pull at the top, rotate-right/left on east/west.
 * FreeChaF does not call SET_CONTROLLER_INFO, so a physical pad works with the default JOYPAD.
 */
@Composable
fun PadKitScope.ChannelFLeft(
    modifier: Modifier = Modifier,
    settings: TouchControllerSettingsManager.Settings,
) {
    BaseLayoutLeft(
        settings = settings,
        modifier = modifier,
        primaryDial = { LemuroidControlCross(id = Id.DiscreteDirection(ComposeTouchLayouts.MOTION_SOURCE_DPAD)) },
        secondaryDials = {
            SecondaryButtonSelect()
            SecondaryButtonMenuPlaceholder(settings)
        },
    )
}

@Composable
fun PadKitScope.ChannelFRight(
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
                        Id.Key(KeyEvent.KEYCODE_BUTTON_A), // east  -> rotate right
                        Id.Key(KeyEvent.KEYCODE_BUTTON_B), // south -> push (fire)
                        Id.Key(KeyEvent.KEYCODE_BUTTON_Y), // west  -> rotate left
                        Id.Key(KeyEvent.KEYCODE_BUTTON_X), // north -> pull
                    ),
                idsForegrounds =
                    persistentMapOf<Id.Key, @Composable (State<Boolean>) -> Unit>(
                        Id.Key(KeyEvent.KEYCODE_BUTTON_A) to { LemuroidButtonForeground(pressed = it, label = "↻") },
                        Id.Key(KeyEvent.KEYCODE_BUTTON_B) to { LemuroidButtonForeground(pressed = it, label = "▼") },
                        Id.Key(KeyEvent.KEYCODE_BUTTON_Y) to { LemuroidButtonForeground(pressed = it, label = "↺") },
                        Id.Key(KeyEvent.KEYCODE_BUTTON_X) to { LemuroidButtonForeground(pressed = it, label = "▲") },
                    ),
            )
        },
        secondaryDials = {
            SecondaryButtonStart()
            SecondaryButtonMenu(settings)
        },
    )
}
