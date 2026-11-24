package com.swordfish.lemuroid.lib.library

import android.app.ActivityManager
import android.content.Context

/**
 * Device memory tier, used ONLY for memory tuning: SQLite PRAGMAs
 * (`LemuroidApplicationModule.retrogradeDb`), cover decode sizes (`CoverUtils`) and
 * shaders (`ShaderChooser`).
 *
 * | RAM                               | Tier       |
 * |-----------------------------------|------------|
 * | ≤ 1 GB, or `isLowRamDevice`       | ULTRA_WEAK |
 * | > 1 GB ≤ 2 GB                     | WEAK       |
 * | > 2 GB                            | NORMAL     |
 *
 * It no longer hides any system. Until 2026-11-24 the tier also removed PSP, 3DS,
 * GameCube, NDS, N64, Dreamcast, Saturn... from the systems list and the Home on weak
 * devices, and the TV hid PSP and 3DS. The owner decided every system is always shown
 * (docs/bugs/aberto/2026-11-24-android-go-2gb-esconde-sistemas-moderados). Do not bring a
 * system filter back here.
 */
object HeavySystemFilter {
    enum class DeviceTier { ULTRA_WEAK, WEAK, NORMAL }

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

    /** True when the device is not NORMAL (lighter shaders, smaller covers). */
    fun isWeakDevice(context: Context): Boolean = deviceTier(context) != DeviceTier.NORMAL
}
