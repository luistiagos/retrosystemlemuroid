package com.swordfish.lemuroid.lib.library

import com.swordfish.lemuroid.common.graphics.ColorUtils
import com.swordfish.lemuroid.lib.R

fun GameSystem.metaSystemID() = MetaSystemID.fromSystemID(id)

/** Meta systems represents a collection of systems which appear the same to the user.
 *  FBNeo and MAME2003Plus are shown as separate consoles. */
enum class MetaSystemID(val titleResId: Int, val imageResId: Int, val systemIDs: List<SystemID>) {
    NES(
        R.string.game_system_title_nes,
        R.drawable.game_system_nes,
        listOf(SystemID.NES),
    ),
    SNES(
        R.string.game_system_title_snes,
        R.drawable.game_system_snes,
        listOf(SystemID.SNES),
    ),
    GENESIS(
        R.string.game_system_title_genesis,
        R.drawable.game_system_genesis,
        listOf(SystemID.GENESIS, SystemID.SEGACD),
    ),
    GB(
        R.string.game_system_title_gb,
        R.drawable.game_system_gb,
        listOf(SystemID.GB),
    ),
    GBC(
        R.string.game_system_title_gbc,
        R.drawable.game_system_gbc,
        listOf(SystemID.GBC),
    ),
    GBA(
        R.string.game_system_title_gba,
        R.drawable.game_system_gba,
        listOf(SystemID.GBA),
    ),
    N64(
        R.string.game_system_title_n64,
        R.drawable.game_system_n64,
        listOf(SystemID.N64),
    ),
    SMS(
        R.string.game_system_title_sms,
        R.drawable.game_system_sms,
        listOf(SystemID.SMS),
    ),
    PSP(
        R.string.game_system_title_psp,
        R.drawable.game_system_psp,
        listOf(SystemID.PSP),
    ),
    NDS(
        R.string.game_system_title_nds,
        R.drawable.game_system_ds,
        listOf(SystemID.NDS),
    ),
    GG(
        R.string.game_system_title_gg,
        R.drawable.game_system_gg,
        listOf(SystemID.GG),
    ),
    ATARI2600(
        R.string.game_system_title_atari2600,
        R.drawable.game_system_atari2600,
        listOf(SystemID.ATARI2600),
    ),
    PSX(
        R.string.game_system_title_psx,
        R.drawable.game_system_psx,
        listOf(SystemID.PSX),
    ),
    FBNEO(
        R.string.game_system_title_arcade_fbneo,
        R.drawable.game_system_fbneo,
        listOf(SystemID.FBNEO),
    ),
    MAME2003PLUS(
        R.string.game_system_title_arcade_mame2003_plus,
        R.drawable.game_system_arcade,
        listOf(SystemID.MAME2003PLUS),
    ),
    ATARI7800(
        R.string.game_system_title_atari7800,
        R.drawable.game_system_atari7800,
        listOf(SystemID.ATARI7800),
    ),
    ATARI5200(
        R.string.game_system_title_atari5200,
        R.drawable.game_system_atari5200,
        listOf(SystemID.ATARI5200),
    ),
    LYNX(
        R.string.game_system_title_lynx,
        R.drawable.game_system_lynx,
        listOf(SystemID.LYNX),
    ),
    PC_ENGINE(
        R.string.game_system_title_pce,
        R.drawable.game_system_pce,
        listOf(SystemID.PC_ENGINE),
    ),
    NGP(
        R.string.game_system_title_ngp,
        R.drawable.game_system_ngp,
        listOf(SystemID.NGP),
    ),
    NGC(
        R.string.game_system_title_ngc,
        R.drawable.game_system_ngpc,
        listOf(SystemID.NGC),
    ),
    WS(
        R.string.game_system_title_ws,
        R.drawable.game_system_ws,
        listOf(SystemID.WS, SystemID.WSC),
    ),
    DOS(
        R.string.game_system_title_dos,
        R.drawable.game_system_dos,
        listOf(SystemID.DOS),
    ),
    NINTENDO_3DS(
        R.string.game_system_title_3ds,
        R.drawable.game_system_3ds,
        listOf(SystemID.NINTENDO_3DS),
    ),
    MSX(
        R.string.game_system_title_msx,
        R.drawable.game_system_msx,
        listOf(SystemID.MSX),
    ),
    MSX2(
        R.string.game_system_title_msx2,
        R.drawable.game_system_msx2,
        listOf(SystemID.MSX2),
    ),
    NEOGEO(
        R.string.game_system_title_neogeo,
        R.drawable.game_system_neogeo,
        listOf(SystemID.NEOGEO),
    ),
    CPS1(
        R.string.game_system_title_cps1,
        R.drawable.game_system_cps1,
        listOf(SystemID.CPS1),
    ),
    CPS2(
        R.string.game_system_title_cps2,
        R.drawable.game_system_cps2,
        listOf(SystemID.CPS2),
    ),
    CPS3(
        R.string.game_system_title_cps3,
        R.drawable.game_system_cps3,
        listOf(SystemID.CPS3),
    ),
    DATAEAST(
        R.string.game_system_title_dataeast,
        R.drawable.game_system_dataeast,
        listOf(SystemID.DATAEAST),
    ),
    GALAXIAN(
        R.string.game_system_title_galaxian,
        R.drawable.game_system_galaxian,
        listOf(SystemID.GALAXIAN),
    ),
    TOAPLAN(
        R.string.game_system_title_toaplan,
        R.drawable.game_system_teoplan,
        listOf(SystemID.TOAPLAN),
    ),
    TAITO(
        R.string.game_system_title_taito,
        R.drawable.game_system_taito,
        listOf(SystemID.TAITO),
    ),
    PSIKYO(
        R.string.game_system_title_psikyo,
        R.drawable.game_system_psikyo,
        listOf(SystemID.PSIKYO),
    ),
    PGM(
        R.string.game_system_title_pgm,
        R.drawable.game_system_pgm,
        listOf(SystemID.PGM),
    ),
    KANEKO(
        R.string.game_system_title_kaneko,
        R.drawable.game_system_kaneko,
        listOf(SystemID.KANEKO),
    ),
    CAVE(
        R.string.game_system_title_cave,
        R.drawable.game_system_cave,
        listOf(SystemID.CAVE),
    ),
    TECHNOS(
        R.string.game_system_title_technos,
        R.drawable.game_system_technos,
        listOf(SystemID.TECHNOS),
    ),
    SETA(
        R.string.game_system_title_seta,
        R.drawable.game_system_seta,
        listOf(SystemID.SETA),
    ),
    ;

    fun color(): Int {
        return ColorUtils.color(ordinal.toFloat() / values().size)
    }

    companion object {
        fun fromSystemID(systemID: SystemID): MetaSystemID {
            return when (systemID) {
                SystemID.FBNEO -> FBNEO
                SystemID.MAME2003PLUS -> MAME2003PLUS
                SystemID.ATARI2600 -> ATARI2600
                SystemID.GB -> GB
                SystemID.GBC -> GBC
                SystemID.GBA -> GBA
                SystemID.GENESIS -> GENESIS
                SystemID.SEGACD -> GENESIS
                SystemID.GG -> GG
                SystemID.N64 -> N64
                SystemID.NDS -> NDS
                SystemID.NES -> NES
                SystemID.PSP -> PSP
                SystemID.PSX -> PSX
                SystemID.SMS -> SMS
                SystemID.SNES -> SNES
                SystemID.PC_ENGINE -> PC_ENGINE
                SystemID.LYNX -> LYNX
                SystemID.ATARI7800 -> ATARI7800
                SystemID.ATARI5200 -> ATARI5200
                SystemID.DOS -> DOS
                SystemID.NGP -> NGP
                SystemID.NGC -> NGC
                SystemID.WS -> WS
                SystemID.WSC -> WS
                SystemID.NINTENDO_3DS -> NINTENDO_3DS
                SystemID.MSX -> MSX
                SystemID.MSX2 -> MSX2
                SystemID.NEOGEO -> NEOGEO
                SystemID.CPS1 -> CPS1
                SystemID.CPS2 -> CPS2
                SystemID.CPS3 -> CPS3
                SystemID.DATAEAST -> DATAEAST
                SystemID.GALAXIAN -> GALAXIAN
                SystemID.TOAPLAN -> TOAPLAN
                SystemID.TAITO -> TAITO
                SystemID.PSIKYO -> PSIKYO
                SystemID.PGM -> PGM
                SystemID.KANEKO -> KANEKO
                SystemID.CAVE -> CAVE
                SystemID.TECHNOS -> TECHNOS
                SystemID.SETA -> SETA
            }
        }
    }
}
