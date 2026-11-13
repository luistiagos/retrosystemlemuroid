package com.swordfish.lemuroid.app.shared.game

import com.swordfish.lemuroid.app.shared.game.viewmodel.GameViewModelRetroGameView.Companion.effectiveScreenAspectRatio
import com.swordfish.lemuroid.app.shared.game.viewmodel.GameViewModelRetroGameView.Companion.isSingleTopScreen
import com.swordfish.lemuroid.app.shared.settings.ScreenAspectRatio
import com.swordfish.lemuroid.lib.core.CoreVariable
import com.swordfish.lemuroid.lib.library.CoreID
import com.swordfish.lemuroid.lib.library.GameSystem
import com.swordfish.lemuroid.lib.library.SystemCoreConfig
import com.swordfish.lemuroid.lib.library.SystemID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tela única do 3DS (T4 + T5, T6 e T3 de
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
    fun threeDsIgnoresTheGlobalAspectRatio() {
        // T6: "Esticar" ou 16:9 de Ajustes deformavam cada tela de Superior — inferior e
        // Esquerda — direita. A tela unica em paisagem estica por outro caminho (setStretchToFill).
        assertTrue(config3ds.ignoresScreenAspectRatio)
        for (choice in ScreenAspectRatio.entries) {
            assertEquals(ScreenAspectRatio.AUTO, effectiveScreenAspectRatio(config3ds, choice))
        }
    }

    @Test
    fun otherSystemsKeepTheGlobalAspectRatio() {
        val nes = GameSystem.findById(SystemID.NES.dbname).systemCoreConfigs.first()

        assertFalse(nes.ignoresScreenAspectRatio)
        for (choice in ScreenAspectRatio.entries) {
            assertEquals(choice, effectiveScreenAspectRatio(nes, choice))
        }
    }

    @Test
    fun systemsWithoutTheFieldNeverMatch() {
        val config = SystemCoreConfig(CoreID.CITRA, hashMapOf())

        assertFalse(config.isSingleScreenLayout(listOf(CoreVariable("citra_layout_option", "single_screen"))))
    }

    @Test
    fun onlyTheTopScreenGoesUnderTheControls() {
        // T3: trocada para a de baixo, a tela visivel e a de toque. Sob os controles, cada botao
        // apertado seria tambem um toque nela (o padkit nao consome o toque).
        assertTrue(isSingleTopScreen(singleScreenLayout = true, screenSwapped = false))
        assertFalse(isSingleTopScreen(singleScreenLayout = true, screenSwapped = true))
        // Superior — inferior (ou Esquerda — direita), com ou sem troca: colunas, como sempre.
        assertFalse(isSingleTopScreen(singleScreenLayout = false, screenSwapped = false))
        assertFalse(isSingleTopScreen(singleScreenLayout = false, screenSwapped = true))
    }

    @Test
    fun threeDsSwapsScreensThroughTheCoreVariable() {
        val swap = checkNotNull(config3ds.screenSwap)

        // Valores que o Azahar 2126.2 declara para "Prominent 3DS Screen" (core_settings.cpp).
        // Outro valor o core ignora, e o app mostraria colunas sobre a tela de cima.
        assertEquals("citra_swap_screen", swap.key)
        assertEquals(CoreVariable("citra_swap_screen", "Top"), swap.variable(swapped = false))
        assertEquals(CoreVariable("citra_swap_screen", "Bottom"), swap.variable(swapped = true))
        // Nem exposta no menu nem nos defaults: a volta do menu regrava as options e desfaria a troca.
        assertTrue(config3ds.exposedSettings.none { it.key == swap.key })
        assertTrue(config3ds.defaultSettings.none { it.key == swap.key })
    }

    @Test
    fun otherSystemsDoNotSwapScreens() {
        val nes = GameSystem.findById(SystemID.NES.dbname).systemCoreConfigs.first()

        // Sem o campo, o THUMBL do pad continua indo ao core como L3.
        assertNull(nes.screenSwap)
    }
}
