package com.swordfish.lemuroid.lib.library.catalog

import androidx.core.net.toUri
import com.swordfish.lemuroid.lib.library.GameSystem
import com.swordfish.lemuroid.lib.library.db.RetrogradeDatabase
import com.swordfish.lemuroid.lib.library.db.entity.Game
import com.swordfish.lemuroid.lib.storage.DirectoriesManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

/**
 * Populates the game catalog directly from `assets/catalog_manifest.txt` without
 * scanning the ROM directory or touching LibretroDB.
 *
 * For every manifest entry whose systemId is a recognised Lemuroid system:
 *   1. Derives the expected ROM file path: `{romsDir}/{systemId}/{fileName}`
 *   2. Creates a 0-byte placeholder at that path if the file doesn't exist yet
 *      (the placeholder is required so that GameLoader can detect "needs on-demand
 *      download" instead of "ROM not found").
 *   3. Inserts a Game row via INSERT OR IGNORE so previously enriched rows
 *      (from a LibretroDB scan) are never overwritten.
 *
 * This replaces the StreamingRomsManager placeholder-creation step so the
 * "Baixando Catálogo" card no longer appears on first run.
 *
 * The LibretroDB scan (LibraryIndexWork) is reserved for ROMs the user adds
 * manually via folder picker, SD/USB mount, or settings rescan.
 */
class ManifestQuickLoader(
    private val directoriesManager: DirectoriesManager,
    private val catalogCoverProvider: CatalogCoverProvider,
    private val database: RetrogradeDatabase,
) {

    data class LoadResult(val inserted: Int, val placeholdersCreated: Int)

    companion object {
        // Emits true once load() completes for the first time in this process.
        // HomeViewModel observes this so it can show a spinner while the catalog
        // is being inserted on fresh install (the 500ms startup delay window).
        private val _catalogReady = MutableStateFlow(false)
        val catalogReady: StateFlow<Boolean> = _catalogReady

        // catalog_manifest.txt uses abbreviated folder names that differ from
        // Lemuroid's SystemID.dbname. Map them so files land in the correct
        // directory and DB rows carry the correct systemId.
        private val MANIFEST_ALIAS = mapOf(
            "a26" to "atari2600",
            "a78" to "atari7800",
            "mame2003Plus" to "mame2003plus",
            "megadrive" to "md",
            "megacd" to "scd",
        )
    }

    /**
     * Runs the manifest-first catalog load. Idempotent — safe to call on every startup.
     *
     * @return [LoadResult] with counts of DB rows inserted and 0-byte files created.
     */
    suspend fun load(): LoadResult = withContext(Dispatchers.IO) {
        val manifest = catalogCoverProvider.getAllEntries()
        if (manifest.isEmpty()) {
            Timber.w("ManifestQuickLoader: empty manifest — nothing to load")
            return@withContext LoadResult(0, 0)
        }

        val romsDir = directoriesManager.getInternalRomsDirectory()
        val now = System.currentTimeMillis()

        var placeholdersCreated = 0
        val games = mutableListOf<Game>()

        for ((key, coverUrl) in manifest) {
            val slash = key.indexOf('/')
            if (slash < 0) continue
            val rawSystemId = key.substring(0, slash)
            val systemId = MANIFEST_ALIAS[rawSystemId] ?: rawSystemId
            val fileName = key.substring(slash + 1)

            if (GameSystem.findByIdOrNull(systemId) == null) continue

            val file = File(File(romsDir, systemId), fileName)

            // Create a 0-byte placeholder so GameLoader can distinguish
            // "catalog game — download on demand" from "ROM not found".
            if (!file.exists()) {
                try {
                    file.parentFile?.mkdirs()
                    file.createNewFile()
                    placeholdersCreated++
                } catch (e: Exception) {
                    Timber.w("ManifestQuickLoader: could not create placeholder for $key — ${e.message}")
                    continue
                }
            }

            games += Game(
                fileName = fileName,
                fileUri = file.toUri().toString(),
                title = fileName.substringBeforeLast("."),
                systemId = systemId,
                developer = null,
                coverFrontUrl = coverUrl,
                lastIndexedAt = now,
            )
        }

        val ids = database.gameDao().insertIfNotExists(games)
        val inserted = ids.count { it != -1L }

        Timber.i("ManifestQuickLoader: inserted=$inserted placeholders=$placeholdersCreated total=${games.size}")
        _catalogReady.value = true
        LoadResult(inserted, placeholdersCreated)
    }
}
