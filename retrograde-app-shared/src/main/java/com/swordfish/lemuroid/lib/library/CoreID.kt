package com.swordfish.lemuroid.lib.library

import android.content.SharedPreferences
import com.swordfish.lemuroid.lib.core.CoreUpdater
import com.swordfish.lemuroid.lib.core.assetsmanager.NoAssetsManager
import com.swordfish.lemuroid.lib.core.assetsmanager.PPSSPPAssetsManager
import com.swordfish.lemuroid.lib.storage.DirectoriesManager

enum class CoreID(
    val coreName: String,
    val coreDisplayName: String,
    val libretroFileName: String,
) {
    STELLA(
        "stella",
        "Stella",
        "libstella_libretro_android.so",
    ),
    FCEUMM(
        "fceumm",
        "FCEUmm",
        "libfceumm_libretro_android.so",
    ),
    SNES9X(
        "snes9x",
        "Snes9x",
        "libsnes9x_libretro_android.so",
    ),
    GENESIS_PLUS_GX(
        "genesis_plus_gx",
        "Genesis Plus GX",
        "libgenesis_plus_gx_libretro_android.so",
    ),
    GAMBATTE(
        "gambatte",
        "Gambatte",
        "libgambatte_libretro_android.so",
    ),
    MGBA(
        "mgba",
        "mGBA",
        "libmgba_libretro_android.so",
    ),
    MUPEN64_PLUS_NEXT(
        "mupen64plus_next_gles3",
        "Mupen64Plus",
        "libmupen64plus_next_gles3_libretro_android.so",
    ),
    PCSX_REARMED(
        "pcsx_rearmed",
        "PCSXReARMed",
        "libpcsx_rearmed_libretro_android.so",
    ),
    PPSSPP(
        "ppsspp",
        "PPSSPP",
        "libppsspp_libretro_android.so",
    ),
    FBNEO(
        "fbneo",
        "FBNeo",
        "libfbneo_libretro_android.so",
    ),
    MAME2003PLUS(
        "mame2003_plus",
        "MAME2003 Plus",
        "libmame2003_plus_libretro_android.so",
    ),
    DESMUME(
        "desmume",
        "DeSmuME (Deprecated)",
        "libdesmume_libretro_android.so",
    ),
    MELONDS(
        "melonds",
        "MelonDS",
        "libmelonds_libretro_android.so",
    ),
    HANDY(
        "handy",
        "Handy",
        "libhandy_libretro_android.so",
    ),
    MEDNAFEN_PCE_FAST(
        "mednafen_pce_fast",
        "PCEFast",
        "libmednafen_pce_fast_libretro_android.so",
    ),
    PROSYSTEM(
        "prosystem",
        "ProSystem",
        "libprosystem_libretro_android.so",
    ),
    A5200(
        "a5200",
        "a5200",
        "liba5200_libretro_android.so",
    ),
    MEDNAFEN_NGP(
        "mednafen_ngp",
        "Mednafen NGP",
        "libmednafen_ngp_libretro_android.so",
    ),
    MEDNAFEN_WSWAN(
        "mednafen_wswan",
        "Beetle Cygne",
        "libmednafen_wswan_libretro_android.so",
    ),
    CITRA(
        "citra",
        "Citra",
        "libcitra_libretro_android.so",
    ),
    DOSBOX_PURE(
        "dosbox_pure",
        "DosBox Pure",
        "libdosbox_pure_libretro_android.so",
    ),
    FMSX(
        "fmsx",
        "fMSX",
        "libfmsx_libretro_android.so",
    ),
    MEDNAFEN_VB(
        "mednafen_vb",
        "Beetle VB",
        "libmednafen_vb_libretro_android.so",
    ),
    VICE_X64SC(
        "vice_x64sc",
        "VICE x64sc",
        "libvice_x64sc_libretro_android.so",
    ),
    FUSE(
        "fuse",
        "Fuse",
        "libfuse_libretro_android.so",
    ),
    CAP32(
        "cap32",
        "Caprice32",
        "libcap32_libretro_android.so",
    ),
    VECX(
        "vecx",
        "vecx",
        "libvecx_libretro_android.so",
    ),
    FREEINTV(
        "freeintv",
        "FreeIntv",
        "libfreeintv_libretro_android.so",
    ),
    POKEMINI(
        "pokemini",
        "PokeMini",
        "libpokemini_libretro_android.so",
    ),
    POTATOR(
        "potator",
        "Potator",
        "libpotator_libretro_android.so",
    ),
    GEARCOLECO(
        "gearcoleco",
        "GearColeco",
        "libgearcoleco_libretro_android.so",
    ),
    FLYCAST(
        "flycast",
        "Flycast",
        "libflycast_libretro_android.so",
    ),
    OPERA(
        "opera",
        "Opera",
        "opera_libretro_android.so",
    ),
    FAKE_08(
        "fake08",
        "Fake-08",
        "libfake08_libretro_android.so",
    ),
    VIRCON32(
        "vircon32",
        "Vircon32",
        "libvircon32_libretro_android.so",
    ),
    PICODRIVE(
        "picodrive",
        "PicoDrive",
        "picodrive_libretro_android.so",
    ),
    ATARI800(
        "atari800",
        "Atari800",
        // Buildbot Android nightlies ship this core WITHOUT the "lib" prefix
        // (same as Opera/PicoDrive). Copy the file name literally.
        "atari800_libretro_android.so",
    ),
    SAMEDUCK(
        "sameduck",
        "SameDuck",
        // Buildbot Android nightlies ship this core WITHOUT the "lib" prefix
        // (same as Opera/PicoDrive/Atari800). Copy the file name literally.
        "sameduck_libretro_android.so",
    ),
    FREECHAF(
        "freechaf",
        "FreeChaF",
        // Buildbot Android nightlies ship this core WITHOUT the "lib" prefix
        // (same as Opera/PicoDrive/Atari800). Copy the file name literally.
        "freechaf_libretro_android.so",
    ),
    UZEM(
        "uzem",
        "Uzem",
        // Buildbot Android nightlies ship this core WITHOUT the "lib" prefix
        // (same as Opera/PicoDrive/Atari800). Copy the file name literally.
        "uzem_libretro_android.so",
    ),
    LOWRESNX(
        "lowresnx",
        "LowRes NX",
        // Buildbot Android nightlies ship this core WITHOUT the "lib" prefix
        // (same as Opera/PicoDrive/Atari800). Copy the file name literally.
        "lowresnx_libretro_android.so",
    ),
    ARDUOUS(
        "arduous",
        "Arduous",
        // Buildbot Android nightlies ship this core WITHOUT the "lib" prefix
        // (same as Opera/PicoDrive/Atari800). Copy the file name literally.
        "arduous_libretro_android.so",
    ),
    DOLPHIN(
        "dolphin",
        "Dolphin",
        // Buildbot Android nightlies ship this core WITHOUT the "lib" prefix
        // (same as Opera/PicoDrive/Atari800). Copy the file name literally.
        "dolphin_libretro_android.so",
    ),
    YABASANSHIRO(
        "yabasanshiro",
        "YabaSanshiro",
        // Buildbot Android nightlies ship this core WITHOUT the "lib" prefix
        // (same as Opera/PicoDrive/Atari800). Copy the file name literally.
        "yabasanshiro_libretro_android.so",
    ),
    VIRTUALJAGUAR(
        "virtualjaguar",
        "Virtual Jaguar",
        // Buildbot Android nightlies ship this core WITHOUT the "lib" prefix
        // (same as Opera/PicoDrive/Atari800). Copy the file name literally.
        "virtualjaguar_libretro_android.so",
    ),
    O2EM(
        "o2em",
        "O2EM",
        // Buildbot Android nightlies ship this core WITHOUT the "lib" prefix
        // (same as Opera/PicoDrive/Atari800). Copy the file name literally.
        "o2em_libretro_android.so",
    ),
    NEOCD(
        "neocd",
        "NeoCD",
        // Buildbot Android nightlies ship this core WITHOUT the "lib" prefix
        // (same as Opera/PicoDrive/Atari800). Copy the file name literally.
        "neocd_libretro_android.so",
    ),
    ;

    companion object {
        fun getAssetManager(coreID: CoreID): AssetsManager {
            return when (coreID) {
                PPSSPP -> PPSSPPAssetsManager()
                else -> NoAssetsManager()
            }
        }
    }

    interface AssetsManager {
        suspend fun retrieveAssetsIfNeeded(
            coreUpdaterApi: CoreUpdater.CoreManagerApi,
            directoriesManager: DirectoriesManager,
            sharedPreferences: SharedPreferences,
        )

        suspend fun clearAssets(directoriesManager: DirectoriesManager)
    }
}

fun findByName(query: String): CoreID? = CoreID.values().firstOrNull { it.coreName == query }
