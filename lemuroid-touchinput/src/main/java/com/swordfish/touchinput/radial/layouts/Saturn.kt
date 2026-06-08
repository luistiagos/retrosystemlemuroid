package com.swordfish.touchinput.radial.layouts

import android.view.KeyEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.swordfish.touchinput.radial.controls.LemuroidControlButton
import com.swordfish.touchinput.radial.controls.LemuroidControlCross
import com.swordfish.touchinput.radial.controls.LemuroidControlFaceButtons
import com.swordfish.touchinput.radial.layouts.shared.ComposeTouchLayouts
import com.swordfish.touchinput.radial.layouts.shared.SecondaryButtonMenu
import com.swordfish.touchinput.radial.layouts.shared.SecondaryButtonMenuPlaceholder
import com.swordfish.touchinput.radial.layouts.shared.SecondaryButtonStart
import com.swordfish.touchinput.radial.settings.TouchControllerSettingsManager
import com.swordfish.touchinput.radial.ui.LemuroidCentralButton
import com.swordfish.touchinput.radial.utils.buildCentral6ButtonsAnchors
import gg.padkit.PadKitScope
import gg.padkit.anchors.Anchor
import gg.padkit.ids.Id
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentMapOf

/**
 * Sega Saturn controller layout (YabaSanshiro core).
 *
 * Saturn has a 6-button face cluster (A B C / X Y Z) plus two shoulder triggers (L R)
 * and Start. YabaSanshiro maps RetroPad → Saturn (verified in libretro.c set_descriptors):
 *   RetroPad B  (KEYCODE_BUTTON_B)  → Saturn A
 *   RetroPad A  (KEYCODE_BUTTON_A)  → Saturn B
 *   RetroPad R  (KEYCODE_BUTTON_R1) → Saturn C
 *   RetroPad Y  (KEYCODE_BUTTON_Y)  → Saturn X
 *   RetroPad X  (KEYCODE_BUTTON_X)  → Saturn Y
 *   RetroPad L  (KEYCODE_BUTTON_L1) → Saturn Z
 *   RetroPad L2 (KEYCODE_BUTTON_L2) → Saturn L (shoulder)
 *   RetroPad R2 (KEYCODE_BUTTON_R2) → Saturn R (shoulder)
 *   RetroPad START                  → Saturn Start
 *
 * The four central buttons (A B X Y) sit in the hex cluster; C and Z are radial buttons
 * to their right (same arrangement as the Sega 6-button layout). L and R shoulders are
 * placed on the outer left/right.
 */
@Composable
fun PadKitScope.SaturnLeft(
    modifier: Modifier = Modifier,
    settings: TouchControllerSettingsManager.Settings,
) {
    BaseLayoutLeft(
        settings = settings,
        modifier = modifier,
        primaryDial = { LemuroidControlCross(id = Id.DiscreteDirection(ComposeTouchLayouts.MOTION_SOURCE_DPAD)) },
        secondaryDials = {
            // Saturn L shoulder → RetroPad L2
            LemuroidControlButton(
                modifier = Modifier.radialPosition(120f),
                id = Id.Key(KeyEvent.KEYCODE_BUTTON_L2),
                label = "L",
            )
            SecondaryButtonStart(position = 1)
            SecondaryButtonMenuPlaceholder(settings)
        },
    )
}

@Composable
fun PadKitScope.SaturnRight(
    modifier: Modifier = Modifier,
    settings: TouchControllerSettingsManager.Settings,
) {
    val centralAnchors = rememberSaturnCentralAnchors(settings.rotation)

    BaseLayoutRight(
        settings = settings,
        modifier = modifier,
        primaryDial = {
            LemuroidControlFaceButtons(
                primaryAnchors = centralAnchors,
                background = { },
                applyPadding = false,
                trackPointers = false,
                idsForegrounds =
                    persistentMapOf<Id.Key, @Composable (State<Boolean>) -> Unit>(
                        Id.Key(KeyEvent.KEYCODE_BUTTON_X) to { LemuroidCentralButton(pressedState = it, label = "Y") },
                        Id.Key(KeyEvent.KEYCODE_BUTTON_Y) to { LemuroidCentralButton(pressedState = it, label = "X") },
                        Id.Key(KeyEvent.KEYCODE_BUTTON_A) to { LemuroidCentralButton(pressedState = it, label = "B") },
                        Id.Key(KeyEvent.KEYCODE_BUTTON_B) to { LemuroidCentralButton(pressedState = it, label = "A") },
                    ),
            )
        },
        secondaryDials = {
            // Saturn C → RetroPad R
            LemuroidControlButton(
                modifier = Modifier.radialPosition(60f),
                id = Id.Key(KeyEvent.KEYCODE_BUTTON_R1),
                label = "C",
            )
            // Saturn Z → RetroPad L
            LemuroidControlButton(
                modifier = Modifier.radialPosition(90f),
                id = Id.Key(KeyEvent.KEYCODE_BUTTON_L1),
                label = "Z",
            )
            // Saturn R shoulder → RetroPad R2
            LemuroidControlButton(
                modifier = Modifier.radialPosition(120f),
                id = Id.Key(KeyEvent.KEYCODE_BUTTON_R2),
                label = "R",
            )
            SecondaryButtonMenu(settings)
        },
    )
}

@Composable
private fun rememberSaturnCentralAnchors(rotation: Float): PersistentList<Anchor<Id.Key>> {
    return remember(rotation) {
        buildCentral6ButtonsAnchors(
            rotation,
            KeyEvent.KEYCODE_BUTTON_X, // → label "Y"
            KeyEvent.KEYCODE_BUTTON_Y, // → label "X"
            KeyEvent.KEYCODE_BUTTON_A, // → label "B"
            KeyEvent.KEYCODE_BUTTON_B, // → label "A"
        )
    }
}
