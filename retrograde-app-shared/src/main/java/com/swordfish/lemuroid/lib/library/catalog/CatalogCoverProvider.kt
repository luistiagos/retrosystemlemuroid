package com.swordfish.lemuroid.lib.library.catalog

import android.content.Context
import java.io.IOException

/**
 * Reads the embedded catalog_manifest.txt from assets and exposes a fast
 * lookup for cover URLs by [systemId/fileName].
 *
 * The manifest lines follow the format:
 *     system/file-name.ext|https://...
 */
class CatalogCoverProvider(private val context: Context) {

    /** Lazy so the disk read is deferred until first actual lookup. */
    private val coverMap: Map<String, String> by lazy { loadFromAssets() }

    /**
     * Returns the cover URL for the given [systemId] and [fileName], or null
     * if the catalog contains no entry for that combination.
     *
     * @param systemId the system dbname, e.g. "snes", "gba", etc.
     * @param fileName the exact ROM file name, e.g. "Super Mario World (USA).sfc"
     */
    fun getCoverUrl(systemId: String, fileName: String): String? {
        return coverMap["$systemId/$fileName"]
    }

    /**
     * Returns the entire manifest map keyed by "systemId/fileName".
     * Used by ManifestQuickLoader to build the local catalog without scanning every ROM
     * through the full LibretroDB metadata pipeline.
     */
    fun getAllEntries(): Map<String, String> = coverMap

    private fun loadFromAssets(): Map<String, String> {
        val map = mutableMapOf<String, String>()
        try {
            context.assets.open("catalog_manifest.txt").bufferedReader().useLines { lines ->
                lines.filter { it.isNotBlank() }
                    .forEach { line ->
                        val path = line.substringBefore('|')
                        val url = line.substringAfter('|', "")
                        if (path.isNotBlank() && url.isNotBlank()) {
                            map[path] = url
                        }
                    }
            }
        } catch (e: IOException) {
            // Asset not present or unreadable — silently return empty map.
        }
        return map
    }
}
