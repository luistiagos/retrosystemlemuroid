package com.swordfish.lemuroid.app.shared.settings

import com.swordfish.libretrodroid.GLRetroView

/**
 * Proporção com que a imagem do jogo é desenhada.
 *
 * Segue o desenho adotado pelo mercado (DuckStation, Dolphin, PCSX2, RetroArch): em vez de
 * "modos" de encaixe, o usuário escolhe uma **proporção alvo** e o renderer encaixa um
 * retângulo dessa proporção dentro do viewport. "Original" e "Preencher" são casos
 * particulares disso, não modos separados.
 *
 * Como o encaixe é sempre "contain", a imagem nunca ultrapassa a área de jogo — não existe
 * transbordo para recortar.
 *
 * Escolher uma proporção mais larga que a do jogo **distorce**; para ganhar largura sem
 * distorcer o caminho é o core renderizar mais cena (ver `widescreen-cores-3d.md`).
 */
enum class ScreenAspectRatio(private val key: String, val targetAspectRatio: Float) {
    /** Proporção que o próprio core reporta. Padrão e comportamento histórico. */
    AUTO("auto", GLRetroView.TARGET_ASPECT_AUTO),

    RATIO_4_3("4:3", 4f / 3f),
    RATIO_16_9("16:9", 16f / 9f),
    RATIO_19_9("19:9", 19f / 9f),
    RATIO_20_9("20:9", 20f / 9f),
    RATIO_21_9("21:9", 21f / 9f),

    /** Proporção da própria área de jogo: preenche tudo, distorcendo. */
    FILL("fill", GLRetroView.TARGET_ASPECT_FILL),
    ;

    companion object {
        fun parse(key: String): ScreenAspectRatio {
            return entries.firstOrNull { it.key == key } ?: AUTO
        }
    }
}
