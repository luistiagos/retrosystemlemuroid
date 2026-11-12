package com.swordfish.lemuroid.app.shared.game

import com.swordfish.lemuroid.lib.core.CoreVariable
import com.swordfish.lemuroid.lib.library.CoreID
import com.swordfish.lemuroid.lib.library.GameSystem
import com.swordfish.lemuroid.lib.library.SystemCoreConfig
import com.swordfish.lemuroid.lib.library.SystemID
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tela única do 3DS (T4 + T5 de
 * docs/bugs/open/2026-11-07-3ds-sem-tela-unica-controles-em-coluna-e-imagem-deformada.md).
 */
class GameSystemSingleScreenTest {
    private val config3ds = GameSystem.findById(SystemID.NINTENDO_3DS.dbname).systemCoreConfigs.single()

    @Test
    fun singleScreenValueIsOneTheMenuCanWrite() {
        val target = checkNotNull(config3ds.singleScreenLayout)

        val exposed = config3ds.exposedSettings.single { it.key == target.key }

        // Se a chave exposta mudar e este campo não, a tela única deixa de pôr os controles
        // por cima do jogo sem nenhum erro visível.
        assertTrue(exposed.values.any { it.key == target.value })
    }

    @Test
    fun defaultLayoutIsNotSingleScreen() {
        // O padrão continua Superior — inferior (decisão do dono): sem pref gravada, só os defaults.
        assertFalse(config3ds.isSingleScreenLayout(config3ds.defaultSettings))
    }

    @Test
    fun followsTheLayoutVariable() {
        val single = config3ds.defaultSettings + CoreVariable("citra_layout_option", "single_screen")
        val topBottom = config3ds.defaultSettings + CoreVariable("citra_layout_option", "default")
        val sideBySide = config3ds.defaultSettings + CoreVariable("citra_layout_option", "side_by_side")

        assertTrue(config3ds.isSingleScreenLayout(single))
        assertFalse(config3ds.isSingleScreenLayout(topBottom))
        assertFalse(config3ds.isSingleScreenLayout(sideBySide))
    }

    @Test
    fun systemsWithoutTheFieldNeverMatch() {
        val config = SystemCoreConfig(CoreID.CITRA, hashMapOf())

        assertFalse(config.isSingleScreenLayout(listOf(CoreVariable("citra_layout_option", "single_screen"))))
    }
}
