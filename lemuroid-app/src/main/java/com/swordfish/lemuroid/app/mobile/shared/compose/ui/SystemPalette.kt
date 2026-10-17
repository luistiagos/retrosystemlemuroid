package com.swordfish.lemuroid.app.mobile.shared.compose.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import com.swordfish.lemuroid.lib.library.GameSystem
import com.swordfish.lemuroid.lib.library.MetaSystemID
import com.swordfish.lemuroid.lib.library.metaSystemID

/**
 * Curated colour per system (redesign, direction A). Every tone keeps a contrast of at least
 * 4.5:1 against white text, so titles and chips can sit directly on it.
 *
 * The `when` is exhaustive on purpose: a new [MetaSystemID] without a colour here fails the build,
 * instead of silently falling back to [MetaSystemID.color] (a hue picked by ordinal, which is
 * not the brand colour and changes whenever the enum is reordered).
 */
object SystemPalette {
    fun color(metaSystem: MetaSystemID): Color =
        when (metaSystem) {
            MetaSystemID.NES -> Color(0xFFB3261E)
            MetaSystemID.SNES -> Color(0xFF6A4FD0)
            MetaSystemID.GENESIS -> Color(0xFF2452A8)
            MetaSystemID.SEGACD -> Color(0xFF1F4E9A)
            MetaSystemID.GB -> Color(0xFF4F6B2A)
            MetaSystemID.GBC -> Color(0xFF7E35A3)
            MetaSystemID.GBA -> Color(0xFF3D48B8)
            MetaSystemID.N64 -> Color(0xFF17753F)
            MetaSystemID.SMS -> Color(0xFFA31F2B)
            MetaSystemID.PSP -> Color(0xFF3B3F48)
            MetaSystemID.NDS -> Color(0xFF5C616B)
            MetaSystemID.GG -> Color(0xFF34407A)
            MetaSystemID.ATARI2600 -> Color(0xFF8A4B1C)
            MetaSystemID.PSX -> Color(0xFF4F5665)
            MetaSystemID.FBNEO -> Color(0xFF8C2A6B)
            MetaSystemID.MAME2003PLUS -> Color(0xFF6B2F8C)
            MetaSystemID.ATARI7800 -> Color(0xFF7A3B1F)
            MetaSystemID.ATARI5200 -> Color(0xFF5A4632)
            MetaSystemID.LYNX -> Color(0xFF9A5A12)
            MetaSystemID.PC_ENGINE -> Color(0xFFA8323E)
            MetaSystemID.NGP -> Color(0xFF1F5F8B)
            MetaSystemID.NGC -> Color(0xFF2D6FA0)
            MetaSystemID.WS -> Color(0xFF3F4F5F)
            MetaSystemID.WSC -> Color(0xFF2E6E8E)
            MetaSystemID.DOS -> Color(0xFF2E3A46)
            MetaSystemID.NINTENDO_3DS -> Color(0xFFA01E2E)
            MetaSystemID.MSX -> Color(0xFF3A4A8C)
            MetaSystemID.MSX2 -> Color(0xFF2F5E8C)
            MetaSystemID.NEOGEO -> Color(0xFF8E1B1B)
            MetaSystemID.CPS1 -> Color(0xFF2A5C9E)
            MetaSystemID.CPS2 -> Color(0xFF3B3B9E)
            MetaSystemID.CPS3 -> Color(0xFF5E2C8E)
            MetaSystemID.DATAEAST -> Color(0xFF2E6B4F)
            MetaSystemID.GALAXIAN -> Color(0xFF22447A)
            MetaSystemID.TOAPLAN -> Color(0xFF8A3A1A)
            MetaSystemID.TAITO -> Color(0xFF1E5AA8)
            MetaSystemID.PSIKYO -> Color(0xFF6E2E6E)
            MetaSystemID.PGM -> Color(0xFF7A5A10)
            MetaSystemID.KANEKO -> Color(0xFF3E6E2E)
            MetaSystemID.CAVE -> Color(0xFF7A2E2E)
            MetaSystemID.TECHNOS -> Color(0xFF2E4E7A)
            MetaSystemID.SETA -> Color(0xFF5A3E8A)
            MetaSystemID.SG_1000 -> Color(0xFF2F4F9F)
            MetaSystemID.SC_3000 -> Color(0xFF3A3A6A)
            MetaSystemID.COLECOVISION -> Color(0xFF364D7D)
            MetaSystemID.PC_ENGINE_CD -> Color(0xFF8E2E50)
            MetaSystemID.VIRTUAL_BOY -> Color(0xFF9E1B1B)
            MetaSystemID.COMMODORE_64 -> Color(0xFF40318D)
            MetaSystemID.ZX_SPECTRUM -> Color(0xFF0B7381)
            MetaSystemID.AMSTRAD_CPC -> Color(0xFF1E6E5E)
            MetaSystemID.VECTREX -> Color(0xFF3E4450)
            MetaSystemID.INTELLIVISION -> Color(0xFF6E5A2E)
            MetaSystemID.POKEMON_MINI -> Color(0xFF3E7A5A)
            MetaSystemID.SUPERVISION -> Color(0xFF4E5A3E)
            MetaSystemID.DREAMCAST -> Color(0xFFB04E0C)
            MetaSystemID.THREE_DO -> Color(0xFF8A5E10)
            MetaSystemID.PICO_8 -> Color(0xFF9E2A5A)
            MetaSystemID.VIRCON32 -> Color(0xFF2E5A7A)
            MetaSystemID.SEGA_32X -> Color(0xFF6E5A14)
            MetaSystemID.FDS -> Color(0xFFA0302A)
            MetaSystemID.ATARI800 -> Color(0xFF5E4632)
            MetaSystemID.AMSTRAD_GX4000 -> Color(0xFF2E6E7A)
            MetaSystemID.MEGADUCK -> Color(0xFF3E6E3E)
            MetaSystemID.CHANNEL_F -> Color(0xFF7A4E2E)
            MetaSystemID.UZEBOX -> Color(0xFF2E7A6E)
            MetaSystemID.LOWRES_NX -> Color(0xFF4E3E8A)
            MetaSystemID.ARDUBOY -> Color(0xFF1E6E8E)
            MetaSystemID.GAMECUBE -> Color(0xFF5A4AAF)
            MetaSystemID.SATURN -> Color(0xFF3A4A6E)
            MetaSystemID.JAGUAR -> Color(0xFFA0281E)
            MetaSystemID.ODYSSEY2 -> Color(0xFF5A2E8E)
            MetaSystemID.NEOCD -> Color(0xFF8E2A2A)
            MetaSystemID.AMIGA -> Color(0xFFA8402A)
            MetaSystemID.AMIGA_1200 -> Color(0xFF2E5E9E)
            MetaSystemID.AMIGA_CD32 -> Color(0xFF3E3E8E)
            MetaSystemID.AMIGA_CDTV -> Color(0xFF3A4A5A)
            MetaSystemID.PCFX -> Color(0xFF6A3A2A)
            MetaSystemID.GAME_WATCH -> Color(0xFF8A5A1A)
            MetaSystemID.ATARI_ST -> Color(0xFF3A5A3A)
            MetaSystemID.MAME289 -> Color(0xFF8B2500)
            MetaSystemID.NAOMI -> Color(0xFFC84B1A)
            MetaSystemID.ATOMISWAVE -> Color(0xFF2A52BE)
        }

    /** Colour for a `Game.systemId` (dbname). Unknown ids get a neutral surface tone. */
    fun colorForSystemId(systemId: String): Color {
        val system = GameSystem.findByIdOrNull(systemId) ?: return LemuroidColors.Surface
        return color(system.metaSystemID())
    }

    /** Dark tone of the system colour, used behind the generated cover and the LITE backdrop. */
    fun darkTone(color: Color): Color = lerp(color, Color.Black, 0.55f)
}
