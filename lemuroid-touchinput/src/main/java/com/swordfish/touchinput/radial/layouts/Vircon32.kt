package com.swordfish.touchinput.radial.layouts

import android.view.KeyEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
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
 * Vircon32 controller layout (Vircon32 core).
 * Mapeamento RetroPad -> Vircon32 (fonte: source do vircon32-libretro libretro.cpp):
 *   RetroPad B (south / KEYCODE_BUTTON_B) -> Vircon32 B
 *   RetroPad A (east / KEYCODE_BUTTON_A) -> Vircon32 A
 *   RetroPad Y (west / KEYCODE_BUTTON_Y) -> Vircon32 Y
 *   RetroPad X (north / KEYCODE_BUTTON_X) -> Vircon32 X
 *   RetroPad L (left bumper) -> Vircon32 L
 *   RetroPad R (right bumper) -> Vircon32 R
 *   RetroPad Start -> Vircon32 Start
 *   D-Pad -> D-Pad
 */
@Composable
fun PadKitScope.Vircon32Left(
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
fun PadKitScope.Vircon32Right(
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
                        Id.Key(KeyEvent.KEYCODE_BUTTON_A), // east  -> Vircon32 A
                        Id.Key(KeyEvent.KEYCODE_BUTTON_B), // south -> Vircon32 B
                        Id.Key(KeyEvent.KEYCODE_BUTTON_Y), // west  -> Vircon32 Y
                        Id.Key(KeyEvent.KEYCODE_BUTTON_X), // north -> Vircon32 X
                    ),
                idsForegrounds =
                    persistentMapOf<Id.Key, @Composable (State<Boolean>) -> Unit>(
                        Id.Key(KeyEvent.KEYCODE_BUTTON_A) to { LemuroidButtonForeground(pressed = it, label = "A") },
                        Id.Key(KeyEvent.KEYCODE_BUTTON_B) to { LemuroidButtonForeground(pressed = it, label = "B") },
                        Id.Key(KeyEvent.KEYCODE_BUTTON_Y) to { LemuroidButtonForeground(pressed = it, label = "Y") },
                        Id.Key(KeyEvent.KEYCODE_BUTTON_X) to { LemuroidButtonForeground(pressed = it, label = "X") },
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
