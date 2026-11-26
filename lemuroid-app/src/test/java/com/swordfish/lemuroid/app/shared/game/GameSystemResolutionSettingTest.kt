package com.swordfish.lemuroid.app.shared.game

import com.swordfish.lemuroid.lib.core.CoreVariable
import com.swordfish.lemuroid.lib.library.CoreID
import com.swordfish.lemuroid.lib.library.GameSystem
import com.swordfish.lemuroid.lib.library.SystemCoreConfig
import com.swordfish.lemuroid.lib.library.SystemID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Resolução interna nos sistemas 3D (TASK-0017 de
 * docs/features/aberto/2026-11-25-resolucao-interna-sistemas-3d/).
 */
class GameSystemResolutionSettingTest {
    private fun config(
        system: SystemID,
        core: CoreID,
    ): SystemCoreConfig = GameSystem.findById(system.dbname).systemCoreConfigs.first { it.coreID == core }

    private fun assertExposes(
        system: SystemID,
        core: CoreID,
        key: String,
    ) {
        val exposed = config(system, core).exposedSettings.map { it.key }
        assertTrue("$key não exposta em ${system.dbname}/$core: $exposed", key in exposed)
    }

    @Test
    fun psxExpoeResolucao() = assertExposes(SystemID.PSX, CoreID.PCSX_REARMED, "pcsx_rearmed_neon_enhancement_enable")

    @Test
    fun gamecubeExpoeResolucao() = assertExposes(SystemID.GAMECUBE, CoreID.DOLPHIN, "dolphin_efb_scale")

    @Test
    fun ndsExpoeResolucao() {
        assertExposes(SystemID.NDS, CoreID.DESMUME, "desmume_internal_resolution")
        // O DeSmuME é o core padrão do DS: é nele que a opção tem de estar.
        assertEquals(CoreID.DESMUME, GameSystem.findById(SystemID.NDS.dbname).systemCoreConfigs.first().coreID)
    }

    @Test
    fun saturnExpoeResolucao() = assertExposes(SystemID.SATURN, CoreID.YABASANSHIRO, "yabasanshiro_resolution_mode")

    @Test
    fun operaExpoeResolucao() = assertExposes(SystemID.THREE_DO, CoreID.OPERA, "opera_high_resolution")

    private fun native(
        key: String,
        value: String,
    ) = CoreVariable(key, value)

    @Test
    fun padraoNativoNosCinco() {
        val expected =
            listOf(
                Triple(SystemID.PSX, CoreID.PCSX_REARMED, native("pcsx_rearmed_neon_enhancement_enable", "disabled")),
                Triple(SystemID.GAMECUBE, CoreID.DOLPHIN, native("dolphin_efb_scale", "1")),
                Triple(SystemID.NDS, CoreID.DESMUME, native("desmume_internal_resolution", "256x192")),
                Triple(SystemID.SATURN, CoreID.YABASANSHIRO, native("yabasanshiro_resolution_mode", "original")),
                Triple(SystemID.THREE_DO, CoreID.OPERA, native("opera_high_resolution", "disabled")),
            )
        for ((system, core, variable) in expected) {
            val defaults = config(system, core).defaultSettings.filter { it.key == variable.key }
            assertEquals("default de ${variable.key} em ${system.dbname}", listOf(variable), defaults)
        }
    }
}
