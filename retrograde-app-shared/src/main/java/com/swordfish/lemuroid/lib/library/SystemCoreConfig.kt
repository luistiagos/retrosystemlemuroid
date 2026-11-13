package com.swordfish.lemuroid.lib.library

import com.swordfish.lemuroid.lib.controller.ControllerConfig
import com.swordfish.lemuroid.lib.core.CoreVariable
import java.io.Serializable

data class SystemCoreConfig(
    val coreID: CoreID,
    val controllerConfigs: HashMap<Int, ArrayList<ControllerConfig>>,
    val exposedSettings: List<ExposedSetting> = listOf(),
    val exposedAdvancedSettings: List<ExposedSetting> = listOf(),
    val defaultSettings: List<CoreVariable> = listOf(),
    val statesSupported: Boolean = true,
    val rumbleSupported: Boolean = false,
    val requiredBIOSFiles: List<String> = listOf(),
    val regionalBIOSFiles: Map<String, String> = mapOf(),
    val statesVersion: Int = 0,
    val supportsLibretroVFS: Boolean = false,
    val skipDuplicateFrames: Boolean = true,
    val supportedOnlyArchitectures: Set<String>? = null,
    val supportsMicrophone: Boolean = false,
    /**
     * Core variable that turns a two-screen system into one picture (3DS: top screen only). While
     * it is set, the mobile landscape layout puts the touch controls over the game and stretches
     * the picture to fill, whatever [ControllerConfig.allowTouchOverlay] and the global aspect
     * ratio say. The key and value must be the ones the menu writes (an [ExposedSetting.Value]).
     */
    val singleScreenLayout: CoreVariable? = null,
    /**
     * The picture is made of screens with a fixed shape (3DS top-bottom / side by side), so any
     * target aspect ratio other than the core's own deforms each screen. When set, the global
     * aspect ratio preference is ignored and the core's ratio is used; [singleScreenLayout] still
     * stretches in mobile landscape.
     */
    val ignoresScreenAspectRatio: Boolean = false,
    /**
     * Core variable that swaps which screen is shown first (3DS: the bottom one in place of the
     * top one). The touch pad's swap button flips it through the app, never as a raw key to the
     * core, so the app knows which screen is visible: with the bottom (touch) screen in
     * [singleScreenLayout] the controls go back to the side columns and the picture keeps its
     * shape. Not persisted and not in the menu: every game starts with [ScreenSwap.normalValue].
     */
    val screenSwap: ScreenSwap? = null,
) : Serializable {
    /** Whether [variables] (defaults + the user's choices) put the core in [singleScreenLayout]. */
    fun isSingleScreenLayout(variables: Iterable<CoreVariable>): Boolean {
        val target = singleScreenLayout ?: return false
        return variables.any { it.key == target.key && it.value == target.value }
    }
}

/**
 * See [SystemCoreConfig.screenSwap]. [normalValue] and [swappedValue] must be values the core
 * declares for [key], or the core ignores them.
 */
data class ScreenSwap(
    val key: String,
    val normalValue: String,
    val swappedValue: String,
) : Serializable {
    fun variable(swapped: Boolean): CoreVariable = CoreVariable(key, if (swapped) swappedValue else normalValue)
}
