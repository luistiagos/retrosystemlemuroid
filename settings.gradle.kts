@file:Suppress("ktlint")

pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
    }
}

include(
    ":retrograde-util",
    ":retrograde-app-shared",
    ":lemuroid-touchinput",
    ":lemuroid-app",
    ":lemuroid-metadata-libretro-db",
    ":lemuroid-app-ext-free",
    ":lemuroid-app-ext-play",
    ":bundled-cores",
    ":baselineprofile"
)

project(":bundled-cores").projectDir = File("lemuroid-cores/bundled-cores")

fun usePlayDynamicFeatures(): Boolean {
    val task = gradle.startParameter.taskRequests.toString()
    return task.contains("Play") && task.contains("Dynamic")
}

if (usePlayDynamicFeatures()) {
    include(
        ":lemuroid_core_a5200",
        ":lemuroid_core_desmume",
        ":lemuroid_core_dosbox_pure",
        ":lemuroid_core_fbneo",
        ":lemuroid_core_fceumm",
        ":lemuroid_core_gambatte",
        ":lemuroid_core_genesis_plus_gx",
        ":lemuroid_core_handy",
        ":lemuroid_core_mame2003_plus",
        ":lemuroid_core_mednafen_ngp",
        ":lemuroid_core_mednafen_pce_fast",
        ":lemuroid_core_mednafen_wswan",
        ":lemuroid_core_melonds",
        ":lemuroid_core_mgba",
        ":lemuroid_core_mupen64plus_next_gles3",
        ":lemuroid_core_pcsx_rearmed",
        ":lemuroid_core_ppsspp",
        ":lemuroid_core_prosystem",
        ":lemuroid_core_snes9x",
        ":lemuroid_core_stella",
        ":lemuroid_core_citra",
        ":lemuroid_core_fmsx",
        ":lemuroid_core_mednafen_vb",
        ":lemuroid_core_vice_x64sc",
        ":lemuroid_core_fuse",
        ":lemuroid_core_cap32",
        ":lemuroid_core_vecx",
        ":lemuroid_core_freeintv",
        ":lemuroid_core_pokemini",
        ":lemuroid_core_potator",
        ":lemuroid_core_gearcoleco",
        ":lemuroid_core_flycast",
        ":lemuroid_core_opera",
        ":lemuroid_core_fake08",
        ":lemuroid_core_vircon32",
        ":lemuroid_core_picodrive",
        ":lemuroid_core_atari800",
        ":lemuroid_core_sameduck",
        ":lemuroid_core_freechaf",
        ":lemuroid_core_uzem",
        ":lemuroid_core_lowresnx",
        ":lemuroid_core_arduous",
        ":lemuroid_core_dolphin",
        ":lemuroid_core_yabasanshiro",
        ":lemuroid_core_virtualjaguar",
        ":lemuroid_core_o2em",
        ":lemuroid_core_neocd",
        ":lemuroid_core_puae",
        ":lemuroid_core_mednafen_pcfx",
        ":lemuroid_core_gw",
        ":lemuroid_core_hatari"
    )

    project(":lemuroid_core_opera").projectDir = File("lemuroid-cores/lemuroid_core_opera")
    project(":lemuroid_core_fake08").projectDir = File("lemuroid-cores/lemuroid_core_fake08")
    project(":lemuroid_core_vircon32").projectDir = File("lemuroid-cores/lemuroid_core_vircon32")
    project(":lemuroid_core_picodrive").projectDir = File("lemuroid-cores/lemuroid_core_picodrive")
    project(":lemuroid_core_atari800").projectDir = File("lemuroid-cores/lemuroid_core_atari800")
    project(":lemuroid_core_sameduck").projectDir = File("lemuroid-cores/lemuroid_core_sameduck")
    project(":lemuroid_core_freechaf").projectDir = File("lemuroid-cores/lemuroid_core_freechaf")
    project(":lemuroid_core_uzem").projectDir = File("lemuroid-cores/lemuroid_core_uzem")
    project(":lemuroid_core_lowresnx").projectDir = File("lemuroid-cores/lemuroid_core_lowresnx")
    project(":lemuroid_core_arduous").projectDir = File("lemuroid-cores/lemuroid_core_arduous")
    project(":lemuroid_core_dolphin").projectDir = File("lemuroid-cores/lemuroid_core_dolphin")
    project(":lemuroid_core_yabasanshiro").projectDir = File("lemuroid-cores/lemuroid_core_yabasanshiro")
    project(":lemuroid_core_virtualjaguar").projectDir = File("lemuroid-cores/lemuroid_core_virtualjaguar")
    project(":lemuroid_core_o2em").projectDir = File("lemuroid-cores/lemuroid_core_o2em")
    project(":lemuroid_core_neocd").projectDir = File("lemuroid-cores/lemuroid_core_neocd")
    project(":lemuroid_core_puae").projectDir = File("lemuroid-cores/lemuroid_core_puae")
    project(":lemuroid_core_mednafen_pcfx").projectDir = File("lemuroid-cores/lemuroid_core_mednafen_pcfx")
    project(":lemuroid_core_gw").projectDir = File("lemuroid-cores/lemuroid_core_gw")
    project(":lemuroid_core_hatari").projectDir = File("lemuroid-cores/lemuroid_core_hatari")
}
