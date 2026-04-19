package com.swordfish.lemuroid.lib.library

import android.app.ActivityManager
import android.content.Context

/**
 * Two-tier device capability filter for [SystemID].
 *
 * | RAM          | Tier          | Hidden systems                                    |
 * |------------- |-------------- |---------------------------------------------------|
 * | ≤ 1 GB       | ULTRA_WEAK   | PSP, 3DS, NDS, N64, PSX, DOS, Sega CD            |
 * | > 1 GB ≤ 2 GB| WEAK         | PSP, 3DS                                          |
 * | > 2 GB       | NORMAL       | (none)                                            |
 *
 * 2D arcade boards and 8/16-bit consoles are lightweight enough for any device.
 */
object HeavySystemFilter {

    enum class DeviceTier { ULTRA_WEAK, WEAK, NORMAL }

    // ── Very demanding — excluded on ≤ 2 GB ─────────────────────────────────
    private val VERY_HEAVY_SYSTEMS: Set<SystemID> = setOf(
        SystemID.PSP,          // PPSSPP – very demanding
        SystemID.NINTENDO_3DS, // Citra – very demanding
    )

    // ── Moderate / moderate-heavy — additionally excluded on ≤ 1 GB ─────────
    private val MODERATE_SYSTEMS: Set<SystemID> = setOf(
        SystemID.NDS,          // melonDS / DeSmuME – moderate-heavy
        SystemID.N64,          // Mupen64Plus – moderate-heavy
        SystemID.PSX,          // PCSX-ReARMed – moderate
        SystemID.DOS,          // DOSBox Pure – moderate
        SystemID.SEGACD,       // Genesis Plus GX CD – moderate
    )

    /** All systems that may be excluded on some device tier. */
    val HEAVY_SYSTEMS: Set<SystemID> = VERY_HEAVY_SYSTEMS + MODERATE_SYSTEMS

    /** Returns the set of [SystemID]s to exclude for the given [tier]. */
    fun excludedSystems(tier: DeviceTier): Set<SystemID> = when (tier) {
        DeviceTier.ULTRA_WEAK -> VERY_HEAVY_SYSTEMS + MODERATE_SYSTEMS
        DeviceTier.WEAK       -> VERY_HEAVY_SYSTEMS
        DeviceTier.NORMAL     -> emptySet()
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
            totalGb > 2.0                        -> DeviceTier.NORMAL
            am.isLowRamDevice || totalGb <= 1.0  -> DeviceTier.ULTRA_WEAK
            else                                 -> DeviceTier.WEAK
        }
    }

    // ── Convenience aliases used by existing callers ─────────────────────────

    /** True when ANY filtering should apply (device is not NORMAL). */
    fun isWeakDevice(context: Context): Boolean = deviceTier(context) != DeviceTier.NORMAL

    /** DB names to exclude for the current device (empty on NORMAL devices). */
    val HEAVY_SYSTEM_DBNAMES: Set<String> = HEAVY_SYSTEMS.map { it.dbname }.toSet()
}
