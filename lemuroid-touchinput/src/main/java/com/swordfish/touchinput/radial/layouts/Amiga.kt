package com.swordfish.touchinput.radial.layouts

import android.view.KeyEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import com.swordfish.touchinput.controller.R
import com.swordfish.touchinput.radial.controls.LemuroidControlButton
import com.swordfish.touchinput.radial.controls.LemuroidControlCross
import com.swordfish.touchinput.radial.controls.LemuroidControlFaceButtons
import com.swordfish.touchinput.radial.layouts.shared.ComposeTouchLayouts
import com.swordfish.touchinput.radial.layouts.shared.SecondaryAnalogLeft
import com.swordfish.touchinput.radial.layouts.shared.SecondaryAnalogRight
import com.swordfish.touchinput.radial.layouts.shared.SecondaryButtonL1
import com.swordfish.touchinput.radial.layouts.shared.SecondaryButtonMenu
import com.swordfish.touchinput.radial.layouts.shared.SecondaryButtonR1
import com.swordfish.touchinput.radial.layouts.shared.SecondaryButtonStart
import com.swordfish.touchinput.radial.settings.TouchControllerSettingsManager
import com.swordfish.touchinput.radial.ui.LemuroidButtonForeground
import gg.padkit.PadKitScope
import gg.padkit.ids.Id
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf

/**
 * Commodore Amiga controller layout (PUAE core).
 *
 * Amiga uses a joystick (1-2 fire buttons), a mouse, and the keyboard. PUAE maps
 * RetroPad → Amiga (verified in libretro-core.c). The port device is forced to PUAE's
 * "Automatic" pad in ControllerConfigs.AMIGA (libretroDescriptor) — PUAE itself defaults
 * ports to NONE and would otherwise process nothing but the analog→mouse path:
 *   RetroPad B  (KEYCODE_BUTTON_B)  → Fire / Red    (CD32 Red)
 *   RetroPad A  (KEYCODE_BUTTON_A)  → 2nd fire / Blue (CD32 Blue)
 *   RetroPad Y  (KEYCODE_BUTTON_Y)  → Green          (CD32)
 *   RetroPad X  (KEYCODE_BUTTON_X)  → Yellow         (CD32)
 *   RetroPad L/R                    → CD32 shoulders
 *   Left/Right Analog               → Mouse
 *   RetroPad SELECT                 → toggle the on-screen virtual keyboard (PUAE VKBD)
 */
@Composable
fun PadKitScope.AmigaLeft(
    modifier: Modifier = Modifier,
    settings: TouchControllerSettingsManager.Settings,
) {
    BaseLayoutLeft(
        settings = settings,
        modifier = modifier,
        primaryDial = { LemuroidControlCross(id = Id.DiscreteDirection(ComposeTouchLayouts.MOTION_SOURCE_DPAD)) },
        secondaryDials = {
            SecondaryButtonL1()
            SecondaryAnalogLeft()
            // Virtual keyboard toggle → RetroPad Select (PUAE VKBD default)
            LemuroidControlButton(
                modifier =
                    Modifier.radialPosition(
                        -120f - 2f * settings.rotation * TouchControllerSettingsManager.MAX_ROTATION,
                    ),
                id = Id.Key(KeyEvent.KEYCODE_BUTTON_SELECT),
                icon = R.drawable.button_keyboard,
            )
        },
    )
}

@Composable
fun PadKitScope.AmigaRight(
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
                        Id.Key(KeyEvent.KEYCODE_BUTTON_Y) to { LemuroidButtonForeground(pressed = it, label = "Y") },
                        Id.Key(KeyEvent.KEYCODE_BUTTON_X) to { LemuroidButtonForeground(pressed = it, label = "X") },
                    ),
            )
        },
        secondaryDials = {
            SecondaryButtonR1()
            SecondaryButtonStart(position = 2)
            SecondaryAnalogRight()
            SecondaryButtonMenu(settings)
        },
    )
}
