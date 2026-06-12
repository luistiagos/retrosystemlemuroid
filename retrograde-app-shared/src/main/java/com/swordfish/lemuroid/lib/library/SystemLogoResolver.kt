package com.swordfish.lemuroid.lib.library

import com.swordfish.lemuroid.lib.R

object SystemLogoResolver {
    fun resolve(metaSystem: MetaSystemID, hovered: Boolean): Int {
        if (!hovered) return metaSystem.imageResId

        return when (metaSystem) {
            MetaSystemID.NES -> R.drawable.game_system_nes_hover
            MetaSystemID.SNES -> R.drawable.game_system_snes_hover
            MetaSystemID.GENESIS -> R.drawable.game_system_genesis_hover
            MetaSystemID.SEGACD -> R.drawable.game_system_scd_hover
            MetaSystemID.GB -> R.drawable.game_system_gb_hover
            MetaSystemID.GBC -> R.drawable.game_system_gbc_hover
            MetaSystemID.GBA -> R.drawable.game_system_gba_hover
            MetaSystemID.N64 -> R.drawable.game_system_n64_hover
            MetaSystemID.SMS -> R.drawable.game_system_sms_hover
            MetaSystemID.PSP -> R.drawable.game_system_psp_hover
            MetaSystemID.NDS -> R.drawable.game_system_ds_hover
            MetaSystemID.GG -> R.drawable.game_system_gg_hover
            MetaSystemID.ATARI2600 -> R.drawable.game_system_atari2600_hover
            MetaSystemID.PSX -> R.drawable.game_system_psx_hover
            MetaSystemID.FBNEO -> R.drawable.game_system_fbneo_hover
            MetaSystemID.MAME2003PLUS -> R.drawable.game_system_arcade_hover
            MetaSystemID.ATARI7800 -> R.drawable.game_system_atari7800_hover
            MetaSystemID.ATARI5200 -> R.drawable.game_system_atari5200_hover
            MetaSystemID.LYNX -> R.drawable.game_system_lynx_hover
            MetaSystemID.PC_ENGINE -> R.drawable.game_system_pce_hover
            MetaSystemID.NGP -> R.drawable.game_system_ngp_hover
            MetaSystemID.NGC -> R.drawable.game_system_ngpc_hover
            MetaSystemID.WS -> R.drawable.game_system_ws_hover
            MetaSystemID.WSC -> R.drawable.game_system_wsc_hover
            MetaSystemID.DOS -> R.drawable.game_system_dos_hover
            MetaSystemID.NINTENDO_3DS -> R.drawable.game_system_3ds_hover
            MetaSystemID.MSX -> R.drawable.game_system_msx_hover
            MetaSystemID.MSX2 -> R.drawable.game_system_msx2_hover
            MetaSystemID.NEOGEO -> R.drawable.game_system_neogeo_hover
            MetaSystemID.CPS1 -> R.drawable.game_system_cps1_hover
            MetaSystemID.CPS2 -> R.drawable.game_system_cps2_hover
            MetaSystemID.CPS3 -> R.drawable.game_system_cps3_hover
            MetaSystemID.DATAEAST -> R.drawable.game_system_dataeast_hover
            MetaSystemID.GALAXIAN -> R.drawable.game_system_galaxian_hover
            MetaSystemID.TOAPLAN -> R.drawable.game_system_teoplan_hover
            MetaSystemID.TAITO -> R.drawable.game_system_taito_hover
            MetaSystemID.PSIKYO -> R.drawable.game_system_psikyo_hover
            MetaSystemID.PGM -> R.drawable.game_system_pgm_hover
            MetaSystemID.KANEKO -> R.drawable.game_system_kaneko_hover
            MetaSystemID.CAVE -> R.drawable.game_system_cave_hover
            MetaSystemID.TECHNOS -> R.drawable.game_system_technos_hover
            MetaSystemID.SETA -> R.drawable.game_system_seta_hover
            MetaSystemID.SG_1000 -> R.drawable.game_system_sg1000_hover
            MetaSystemID.SC_3000 -> R.drawable.game_system_sc3000_hover
            MetaSystemID.COLECOVISION -> R.drawable.game_system_coleco_hover
            MetaSystemID.PC_ENGINE_CD -> R.drawable.game_system_pcecd_hover
            MetaSystemID.VIRTUAL_BOY -> R.drawable.game_system_vb_hover
            MetaSystemID.COMMODORE_64 -> R.drawable.game_system_c64_hover
            MetaSystemID.ZX_SPECTRUM -> R.drawable.game_system_zxspectrum_hover
            MetaSystemID.AMSTRAD_CPC -> R.drawable.game_system_amstradcpc_hover
            MetaSystemID.VECTREX -> R.drawable.game_system_vectrex_hover
            MetaSystemID.INTELLIVISION -> R.drawable.game_system_intellivision_hover
            MetaSystemID.POKEMON_MINI -> R.drawable.game_system_pokemini_hover
            MetaSystemID.SUPERVISION -> R.drawable.game_system_supervision_hover
            MetaSystemID.DREAMCAST -> R.drawable.game_system_dc_hover
            MetaSystemID.AMIGA_1200 -> R.drawable.game_system_amiga1200_hover
            MetaSystemID.AMIGA_CD32 -> R.drawable.game_system_amigacd32_hover
            MetaSystemID.AMIGA_CDTV -> R.drawable.game_system_amigacdtv_hover
            MetaSystemID.THREE_DO -> R.drawable.game_system_3do_hover
            MetaSystemID.PICO_8 -> R.drawable.game_system_pico8_hover
            MetaSystemID.VIRCON32 -> R.drawable.game_system_vircon32_hover
            MetaSystemID.SEGA_32X -> R.drawable.game_system_sega32x_hover
            MetaSystemID.FDS -> R.drawable.game_system_fds_hover
            MetaSystemID.ATARI800 -> R.drawable.game_system_atari800_hover
            MetaSystemID.AMSTRAD_GX4000 -> R.drawable.game_system_gx4000_hover
            MetaSystemID.MEGADUCK -> R.drawable.game_system_megaduck_hover
            MetaSystemID.CHANNEL_F -> R.drawable.game_system_channelf_hover
            MetaSystemID.UZEBOX -> R.drawable.game_system_uzebox_hover
            MetaSystemID.LOWRES_NX -> R.drawable.game_system_lowresnx_hover
            MetaSystemID.ARDUBOY -> R.drawable.game_system_arduboy_hover
            else -> metaSystem.imageResId
        }
    }
}
