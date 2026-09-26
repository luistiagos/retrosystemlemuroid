package com.swordfish.lemuroid.lib.library

import android.content.SharedPreferences
import com.swordfish.lemuroid.lib.core.CoreUpdater
import com.swordfish.lemuroid.lib.core.assetsmanager.NoAssetsManager
import com.swordfish.lemuroid.lib.core.assetsmanager.PPSSPPAssetsManager
import com.swordfish.lemuroid.lib.storage.DirectoriesManager

enum class CoreID(
    val coreName: String,
    val coreDisplayName: String,
    // Always lib<coreName>_libretro_android.so, even when the buildbot ships the core without
    // the "lib" prefix: the installer only extracts lib*.so into nativeLibraryDir, so any other
    // name stays inside the release APK and the core gets downloaded again (Android 13+ skips
    // that filter for debuggable APKs, so debug builds hide it). verifyBundledCores enforces it.
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
        "libopera_libretro_android.so",
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
        "libpicodrive_libretro_android.so",
    ),
    ATARI800(
        "atari800",
        "Atari800",
        "libatari800_libretro_android.so",
    ),
    SAMEDUCK(
        "sameduck",
        "SameDuck",
        "libsameduck_libretro_android.so",
    ),
    FREECHAF(
        "freechaf",
        "FreeChaF",
        "libfreechaf_libretro_android.so",
    ),
    UZEM(
        "uzem",
        "Uzem",
        "libuzem_libretro_android.so",
    ),
    LOWRESNX(
        "lowresnx",
        "LowRes NX",
        "liblowresnx_libretro_android.so",
    ),
    ARDUOUS(
        "arduous",
        "Arduous",
        "libarduous_libretro_android.so",
    ),
    DOLPHIN(
        "dolphin",
        "Dolphin",
        "libdolphin_libretro_android.so",
    ),
    YABASANSHIRO(
        "yabasanshiro",
        "YabaSanshiro",
        "libyabasanshiro_libretro_android.so",
    ),
    VIRTUALJAGUAR(
        "virtualjaguar",
        "Virtual Jaguar",
        "libvirtualjaguar_libretro_android.so",
    ),
    O2EM(
        "o2em",
        "O2EM",
        "libo2em_libretro_android.so",
    ),
    NEOCD(
        "neocd",
        "NeoCD",
        "libneocd_libretro_android.so",
    ),
    PUAE(
        "puae",
        "PUAE",
        "libpuae_libretro_android.so",
    ),
    MEDNAFEN_PCFX(
        "mednafen_pcfx",
        "Beetle PC-FX",
        "libmednafen_pcfx_libretro_android.so",
    ),
    GW(
        "gw",
        "Game & Watch",
        "libgw_libretro_android.so",
    ),
    HATARI(
        "hatari",
        "Hatari",
        "libhatari_libretro_android.so",
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
