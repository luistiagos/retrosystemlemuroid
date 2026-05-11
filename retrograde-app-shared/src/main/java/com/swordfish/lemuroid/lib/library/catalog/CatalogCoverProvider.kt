package com.swordfish.lemuroid.lib.library.catalog

import android.content.Context
import java.io.IOException

/**
 * Reads the embedded catalog_manifest.txt from assets and exposes a fast
 * lookup for cover URLs and titles by [systemId/fileName].
 *
 * The manifest lines follow the format:
 *     system/file-name.ext|title|https://...
 */
class CatalogCoverProvider(private val context: Context) {

    data class ManifestEntry(val title: String?, val coverUrl: String?)

    /** Lazy so the disk read is deferred until first actual lookup. */
    private val entryMap: Map<String, ManifestEntry> by lazy { loadFromAssets() }

    /**
     * Returns the cover URL for the given [systemId] and [fileName], or null
     * if the catalog contains no entry for that combination.
     */
    fun getCoverUrl(systemId: String, fileName: String): String? {
        return entryMap["$systemId/$fileName"]?.coverUrl
    }

    /**
     * Returns the entire manifest map keyed by "systemId/fileName".
     * Used by ManifestQuickLoader to build the local catalog without scanning every ROM
     * through the full LibretroDB metadata pipeline.
     */
    fun getAllEntries(): Map<String, ManifestEntry> = entryMap

    private fun loadFromAssets(): Map<String, ManifestEntry> {
        val map = mutableMapOf<String, ManifestEntry>()
        try {
            context.assets.open("catalog_manifest.txt").bufferedReader().useLines { lines ->
                lines.filter { it.isNotBlank() }
                    .forEach { line ->
                        val parts = line.split('|')
                        val path = parts.getOrNull(0)?.takeIf { it.isNotBlank() } ?: return@forEach
                        val title = parts.getOrNull(1)?.takeIf { it.isNotBlank() }
                        val coverUrl = parts.getOrNull(2)?.takeIf { it.isNotBlank() }
                        map[path] = ManifestEntry(title = title, coverUrl = coverUrl)
                    }
            }
        } catch (e: IOException) {
            // Asset not present or unreadable — silently return empty map.
        }
        return map
    }
}
