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
import com.swordfish.touchinput.radial.layouts.shared.SecondaryButtonSelect
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
 * NEC PC-FX controller layout (Beetle PC-FX core).
 *
 * The PC-FX pad has six face buttons (I–VI) plus Run and Select. Beetle PC-FX maps
 * RetroPad → PC-FX (verified in libretro.cpp) and registers the pad as "PCFX Joypad":
 *   RetroPad A  (KEYCODE_BUTTON_A)  → I
 *   RetroPad B  (KEYCODE_BUTTON_B)  → II
 *   RetroPad X  (KEYCODE_BUTTON_X)  → III
 *   RetroPad Y  (KEYCODE_BUTTON_Y)  → IV
 *   RetroPad L  (KEYCODE_BUTTON_L1) → V
 *   RetroPad R  (KEYCODE_BUTTON_R1) → VI
 *   RetroPad START                  → Run
 *   RetroPad SELECT                 → Select
 *
 * I/II/III/IV sit in the hex cluster; V and VI are radial buttons to their right.
 */
@Composable
fun PadKitScope.PCFXLeft(
    modifier: Modifier = Modifier,
    settings: TouchControllerSettingsManager.Settings,
) {
    BaseLayoutLeft(
        settings = settings,
        modifier = modifier,
        primaryDial = { LemuroidControlCross(id = Id.DiscreteDirection(ComposeTouchLayouts.MOTION_SOURCE_DPAD)) },
        secondaryDials = {
            SecondaryButtonSelect(position = 0)
            SecondaryButtonMenuPlaceholder(settings)
        },
    )
}

@Composable
fun PadKitScope.PCFXRight(
    modifier: Modifier = Modifier,
    settings: TouchControllerSettingsManager.Settings,
) {
    val centralAnchors = rememberPCFXCentralAnchors(settings.rotation)

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
                        Id.Key(KeyEvent.KEYCODE_BUTTON_Y) to { LemuroidCentralButton(pressedState = it, label = "IV") },
                        Id.Key(KeyEvent.KEYCODE_BUTTON_X) to { LemuroidCentralButton(pressedState = it, label = "III") },
                        Id.Key(KeyEvent.KEYCODE_BUTTON_B) to { LemuroidCentralButton(pressedState = it, label = "II") },
                        Id.Key(KeyEvent.KEYCODE_BUTTON_A) to { LemuroidCentralButton(pressedState = it, label = "I") },
                    ),
            )
        },
        secondaryDials = {
            // V → RetroPad L
            LemuroidControlButton(
                modifier = Modifier.radialPosition(60f),
                id = Id.Key(KeyEvent.KEYCODE_BUTTON_L1),
                label = "V",
            )
            // VI → RetroPad R
            LemuroidControlButton(
                modifier = Modifier.radialPosition(90f),
                id = Id.Key(KeyEvent.KEYCODE_BUTTON_R1),
                label = "VI",
            )
            // Run → RetroPad Start
            SecondaryButtonStart(position = 2)
            SecondaryButtonMenu(settings)
        },
    )
}

@Composable
private fun rememberPCFXCentralAnchors(rotation: Float): PersistentList<Anchor<Id.Key>> {
    return remember(rotation) {
        buildCentral6ButtonsAnchors(
            rotation,
            KeyEvent.KEYCODE_BUTTON_Y, // → label "IV"
            KeyEvent.KEYCODE_BUTTON_X, // → label "III"
            KeyEvent.KEYCODE_BUTTON_B, // → label "II"
            KeyEvent.KEYCODE_BUTTON_A, // → label "I"
        )
    }
}
