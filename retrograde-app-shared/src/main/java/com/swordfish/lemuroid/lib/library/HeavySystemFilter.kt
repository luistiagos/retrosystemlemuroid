package com.swordfish.lemuroid.lib.library

import android.app.ActivityManager
import android.content.Context

/**
 * Two-tier device capability filter for [SystemID].
 *
 * | RAM          | Tier          | Hidden systems                                    |
 * |------------- |-------------- |---------------------------------------------------|
 * | ≤ 1 GB       | ULTRA_WEAK   | PSP, 3DS, GameCube, NDS, N64, DOS, Sega CD, ...   |
 * | > 1 GB ≤ 2 GB| WEAK         | PSP, 3DS, GameCube                                |
 * | > 2 GB       | NORMAL       | (none)                                            |
 *
 * 2D arcade boards and 8/16-bit consoles are lightweight enough for any device.
 *
 * PSX is deliberately absent from every tier: PCSX-ReARMed runs acceptably even on
 * 1 GB set-top boxes, and hiding it made the whole PlayStation catalogue vanish on
 * cheap TV boxes — which report `isLowRamDevice = true` and so land in ULTRA_WEAK
 * even when they have 2 GB. Do not add it back without a measured reason.
 */
object HeavySystemFilter {

    enum class DeviceTier { ULTRA_WEAK, WEAK, NORMAL }

    // ── Very demanding — excluded on ≤ 2 GB ─────────────────────────────────
    private val VERY_HEAVY_SYSTEMS: Set<SystemID> = setOf(
        // PPSSPP – very demanding
        SystemID.PSP,
        // Citra – very demanding
        SystemID.NINTENDO_3DS,
        // Dolphin – very demanding
        SystemID.GAMECUBE,
        // MAME 0.289 (Arcade) – very demanding
        SystemID.MAME289,
    )

    // ── Moderate / moderate-heavy — additionally excluded on ≤ 1 GB ─────────
    private val MODERATE_SYSTEMS: Set<SystemID> = setOf(
        // melonDS / DeSmuME – moderate-heavy
        SystemID.NDS,
        // Mupen64Plus – moderate-heavy
        SystemID.N64,
        // DOSBox Pure – moderate
        SystemID.DOS,
        // Genesis Plus GX CD – moderate
        SystemID.SEGACD,
        // Flycast – moderate-heavy
        SystemID.DREAMCAST,
        // Flycast – moderate-heavy
        SystemID.NAOMI,
        // Flycast – moderate-heavy
        SystemID.ATOMISWAVE,
        // Opera – moderate-heavy
        SystemID.THREE_DO,
        // YabaSanshiro – moderate-heavy
        SystemID.SATURN,
        // PUAE – moderate
        SystemID.AMIGA,
        // PUAE (AGA) – moderate
        SystemID.AMIGA_1200,
        // PUAE (CD32) – moderate
        SystemID.AMIGA_CD32,
        // PUAE (CDTV) – moderate
        SystemID.AMIGA_CDTV,
        // Beetle PC-FX – moderate
        SystemID.PCFX,
        // Hatari – moderate (68000-based computer)
        SystemID.ATARI_ST,
    )

    /** All systems that may be excluded on some device tier. */
    val HEAVY_SYSTEMS: Set<SystemID> = VERY_HEAVY_SYSTEMS + MODERATE_SYSTEMS

    /** Returns the set of [SystemID]s to exclude for the given [tier]. */
    fun excludedSystems(tier: DeviceTier): Set<SystemID> = when (tier) {
        DeviceTier.ULTRA_WEAK -> VERY_HEAVY_SYSTEMS + MODERATE_SYSTEMS
        DeviceTier.WEAK -> VERY_HEAVY_SYSTEMS
        DeviceTier.NORMAL -> emptySet()
    }

    /** DB names to exclude for a given [tier], for SQL queries. */
    fun excludedDbNames(tier: DeviceTier): Set<String> =
        excludedSystems(tier).map { it.dbname }.toSet()

    /** Catalog folder prefixes to exclude for a given [tier]. */
    fun excludedCatalogPrefixes(tier: DeviceTier): Set<String> =
        excludedSystems(tier).map { "${it.dbname}/" }.toSet()

    /** Classifies the current device into a [DeviceTier]. */
    fun deviceTier(context: Context): DeviceTier {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)
        val totalGb = memInfo.totalMem.toDouble() / (1024.0 * 1024.0 * 1024.0)

        // Physical RAM takes priority: > 2 GB is always NORMAL regardless of the
        // isLowRamDevice flag, which some manufacturers set even on 4 GB+ devices.
        return when {
            totalGb > 2.0 -> DeviceTier.NORMAL
            am.isLowRamDevice || totalGb <= 1.0 -> DeviceTier.ULTRA_WEAK
            else -> DeviceTier.WEAK
        }
    }

    // ── Convenience aliases used by existing callers ─────────────────────────

    /** True when ANY filtering should apply (device is not NORMAL). */
    fun isWeakDevice(context: Context): Boolean = deviceTier(context) != DeviceTier.NORMAL

    /** DB names to exclude for the current device (empty on NORMAL devices). */
    val HEAVY_SYSTEM_DBNAMES: Set<String> = HEAVY_SYSTEMS.map { it.dbname }.toSet()
}
