package com.swordfish.lemuroid.lib.library.catalog

import android.content.Context
import java.io.IOException

/**
 * Reads the embedded `assets/catalog_manifest.txt` and exposes fast lookups
 * for cover URLs, titles, and popularity indexes by `"systemId/fileName"` key.
 *
 * Manifest line format (pipe-delimited, 4 fields):
 * ```
 * system/filename.ext|title|https://cover-url.png|popularityIndex
 * ```
 * - **field 1** – `system/filename` path key
 * - **field 2** – optional display title (empty → derived from filename)
 * - **field 3** – cover image URL (IGDB or libretro-thumbnails)
 * - **field 4** – popularity index (positive integer; higher = more popular; 0 = no data)
 */
class CatalogCoverProvider(private val context: Context) {

    data class ManifestEntry(val title: String?, val coverUrl: String?, val popularityIndex: Int = 0)

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
                        val popularityIndex = parts.getOrNull(3)?.toIntOrNull() ?: 0
                        map[path] = ManifestEntry(title = title, coverUrl = coverUrl, popularityIndex = popularityIndex)
                    }
            }
        } catch (e: IOException) {
            // Asset not present or unreadable — silently return empty map.
        }
        return map
    }
}
