package com.swordfish.lemuroid.app.shared.game

import com.swordfish.lemuroid.lib.library.ControllerConfigs
import com.swordfish.lemuroid.lib.library.CoreID
import com.swordfish.lemuroid.lib.library.GameSystem
import com.swordfish.lemuroid.lib.library.SystemID
import com.swordfish.touchinput.radial.settings.TouchControllerID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Regressão de controles do MSX / MSX2 (fMSX): garante que a Porta 0 receba
 * `setControllerType` com "Joystick + Emulated Keyboard" e use o layout touch MSX.
 * Ver docs/bugs/aberto/2026-11-19-msx-sem-resposta-controles-travado-demo/.
 */
class MsxControllerConfigTest {
    @Test
    fun msxAndMsx2RequestJoystickPlusEmulatedKeyboardOnPort0() {
        for (systemId in listOf(SystemID.MSX, SystemID.MSX2)) {
            val system = GameSystem.findById(systemId.dbname)
            val fmsxConfig = system.systemCoreConfigs.single { it.coreID == CoreID.FMSX }
            val port0Default = fmsxConfig.controllerConfigs[0]!!.first()

            assertEquals(ControllerConfigs.MSX, port0Default)
            assertEquals("Joystick + Emulated Keyboard", port0Default.libretroDescriptor)
            assertEquals(TouchControllerID.MSX, port0Default.touchControllerID)
            assertNotNull(port0Default.getTouchControllerConfig())
        }
    }
}
