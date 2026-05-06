package com.swordfish.lemuroid.lib.library.catalog

import androidx.core.net.toUri
import com.swordfish.lemuroid.lib.library.GameSystem
import com.swordfish.lemuroid.lib.library.db.RetrogradeDatabase
import com.swordfish.lemuroid.lib.library.db.entity.Game
import com.swordfish.lemuroid.lib.storage.DirectoriesManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

/**
 * Fast catalog loader. Builds the user-visible catalog directly from
 * `assets/catalog_manifest.txt` plus a recursive walk of the ROMs directory,
 * without touching LibretroDB.
 *
 * Match rule: a local file at `{romsDir}/{systemDbname}/{fileName}` matches
 * the manifest entry whose key is `"{systemDbname}/{fileName}"`. The parent
 * folder name *is* the systemId — downloads from the catalog already organize
 * files this way.
 *
 * The full LibretroDB scan (multi-strategy CRC/serial/filename matching) is
 * reserved for ROMs the user adds manually (folder picker, SD/USB mount,
 * settings rescan). For catalog downloads the manifest is authoritative, so
 * this loader is the only step required to make games appear in the UI.
 */
class ManifestQuickLoader(
    private val directoriesManager: DirectoriesManager,
    private val catalogCoverProvider: CatalogCoverProvider,
    private val database: RetrogradeDatabase,
) {

    /**
     * Walks the ROM directory, matches files against the manifest, and inserts
     * Game rows that don't already exist in the DB. Idempotent.
     *
     * @return number of new games inserted (0 if nothing changed).
     */
    suspend fun load(): Int = withContext(Dispatchers.IO) {
        val romsDir = directoriesManager.getInternalRomsDirectory()
        if (!romsDir.exists() || !romsDir.isDirectory) {
            Timber.d("ManifestQuickLoader: roms dir missing — $romsDir")
            return@withContext 0
        }

        val manifest = catalogCoverProvider.getAllEntries()
        if (manifest.isEmpty()) {
            Timber.w("ManifestQuickLoader: empty manifest")
            return@withContext 0
        }

        val supportedExtensions = GameSystem.getSupportedExtensions().toSet()
        val now = System.currentTimeMillis()

        val games = romsDir.walkTopDown()
            .filter { it.isFile && it.extension.lowercase() in supportedExtensions }
            .mapNotNull { file -> buildGame(file, manifest, now) }
            .toList()

        if (games.isEmpty()) {
            Timber.d("ManifestQuickLoader: no manifest matches for files under $romsDir")
            return@withContext 0
        }

        // INSERT OR IGNORE on fileUri (unique index): preserves rows already
        // enriched by a previous LibretroDB scan, only fills gaps.
        val ids = database.gameDao().insertIfNotExists(games)
        val inserted = ids.count { it != -1L }
        Timber.i("ManifestQuickLoader: inserted $inserted/${games.size} games")
        inserted
    }

    private fun buildGame(file: File, manifest: Map<String, String>, now: Long): Game? {
        val parent = file.parentFile ?: return null
        val systemId = parent.name
        if (GameSystem.findByIdOrNull(systemId) == null) return null

        val key = "$systemId/${file.name}"
        val coverUrl = manifest[key] ?: return null

        return Game(
            fileName = file.name,
            fileUri = file.toUri().toString(),
            title = file.nameWithoutExtension,
            systemId = systemId,
            developer = null,
            coverFrontUrl = coverUrl,
            lastIndexedAt = now,
        )
    }
}
