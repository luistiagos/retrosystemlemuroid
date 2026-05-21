package com.swordfish.lemuroid.app.shared.roms

import android.content.Context
import com.swordfish.lemuroid.lib.library.catalog.ManifestQuickLoader
import org.json.JSONObject

/**
 * Maps Lemuroid systemId values to the system names expected by the remote endpoint
 * at emuladores.pythonanywhere.com.
 *
 * Source of truth: [assets/mnemonico_map.json]. The JSON keys are catalog manifest
 * folder names; this object additionally registers the post-alias dbname for each
 * aliased system (via [ManifestQuickLoader.loadManifestAlias]) so callers can pass
 * either form without divergence.
 *
 * Loaded once, lazily, into an in-memory map.
 */
object RomSystemMapper {

    private const val ASSET_NAME = "mnemonico_map.json"

    @Volatile private var cachedMap: Map<String, String>? = null

    /**
     * Returns the endpoint system name for the given Lemuroid [systemId],
     * or null if the system is not present in the map.
     */
    fun toEndpointSystem(context: Context, systemId: String): String? {
        val map = cachedMap ?: synchronized(this) {
            cachedMap ?: load(context.applicationContext).also { cachedMap = it }
        }
        return map[systemId]
    }

    private fun load(ctx: Context): Map<String, String> {
        val json = ctx.assets.open(ASSET_NAME).bufferedReader().use { it.readText() }
        val raw = JSONObject(json)
        val manifestAlias = ManifestQuickLoader.loadManifestAlias(ctx)
        val result = mutableMapOf<String, String>()
        val keys = raw.keys()
        while (keys.hasNext()) {
            val manifestKey = keys.next()
            val endpoint = raw.getString(manifestKey)
            // Register the manifest folder name (raw key from JSON).
            result[manifestKey] = endpoint
            // For aliased systems, also register the post-alias dbname,
            // since DB rows carry the dbname (not the manifest folder name).
            manifestAlias[manifestKey]?.let { dbname ->
                result[dbname] = endpoint
            }
        }
        return result
    }
}
