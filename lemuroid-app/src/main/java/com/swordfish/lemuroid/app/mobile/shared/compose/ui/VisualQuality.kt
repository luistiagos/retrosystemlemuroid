package com.swordfish.lemuroid.app.mobile.shared.compose.ui

import android.content.Context
import android.provider.Settings
import androidx.compose.runtime.staticCompositionLocalOf
import com.swordfish.lemuroid.app.shared.covers.CoverUtils

/**
 * How much eye candy the redesign may spend. Every new effect (ambient backdrop, motion, focus
 * scale) reads this and has a [LITE] variant.
 *
 * - [FULL]: cover art behind the ambient gradient, animations.
 * - [LITE]: weak device (same criterion that already shrinks the cover cache, see
 *   [CoverUtils.isLowRamDevice]) or animations turned off in the system settings: flat gradients
 *   in the system colour, no motion.
 */
enum class VisualQuality {
    FULL,
    LITE,
    ;

    val isLite: Boolean get() = this == LITE

    companion object {
        @Volatile
        private var cached: VisualQuality? = null

        /**
         * Computed once per process. Reads only `ActivityManager` and `Settings`: safe in the
         * `:game` process, which must not initialise Coil.
         */
        fun of(context: Context): VisualQuality {
            cached?.let { return it }
            val appContext = context.applicationContext
            val result = if (CoverUtils.isLowRamDevice(appContext) || animationsDisabled(appContext)) LITE else FULL
            cached = result
            return result
        }

        private fun animationsDisabled(context: Context): Boolean {
            val scale =
                runCatching {
                    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
                }.getOrDefault(1f)
            return scale == 0f
        }
    }
}

val LocalVisualQuality = staticCompositionLocalOf { VisualQuality.FULL }
