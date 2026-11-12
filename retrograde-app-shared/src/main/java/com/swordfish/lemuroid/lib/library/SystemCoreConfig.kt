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
) : Serializable {
    /** Whether [variables] (defaults + the user's choices) put the core in [singleScreenLayout]. */
    fun isSingleScreenLayout(variables: Iterable<CoreVariable>): Boolean {
        val target = singleScreenLayout ?: return false
        return variables.any { it.key == target.key && it.value == target.value }
    }
}
