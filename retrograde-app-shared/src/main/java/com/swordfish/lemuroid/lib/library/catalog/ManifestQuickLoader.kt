package com.swordfish.lemuroid.lib.library.catalog

import android.content.Context
import android.os.Build
import androidx.core.net.toUri
import com.swordfish.lemuroid.lib.library.GameSystem
import com.swordfish.lemuroid.lib.library.db.RetrogradeDatabase
import com.swordfish.lemuroid.lib.library.db.entity.Game
import com.swordfish.lemuroid.lib.storage.DirectoriesManager
import androidx.room.withTransaction
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
 *   2. Inserts a Game row via INSERT OR IGNORE so previously enriched rows
 *      (from a LibretroDB scan) are never overwritten.
 *
 * No placeholder files are created on disk. The on-demand download check in
 * MainActivity uses `File.length() == 0L`, which returns 0 for non-existent
 * files — so the download dialog triggers correctly without physical placeholders.
 *
 * The full load runs only once per app version (tracked in SharedPreferences).
 * Subsequent launches with the same versionCode set catalogReady immediately.
 *
 * The LibretroDB scan (LibraryIndexWork) is reserved for ROMs the user adds
 * manually via folder picker, SD/USB mount, or settings rescan.
 */
class ManifestQuickLoader(
    private val context: Context,
    private val directoriesManager: DirectoriesManager,
    private val catalogCoverProvider: CatalogCoverProvider,
    private val database: RetrogradeDatabase,
) {

    data class LoadResult(val inserted: Int)

    companion object {
        // Emits true once load() completes (or is skipped) for this process.
        // HomeViewModel observes this to suppress the spinner after catalog is ready.
        private val _catalogReady = MutableStateFlow(false)
        val catalogReady: StateFlow<Boolean> = _catalogReady

        private const val PREFS_NAME = "manifest_loader_prefs"
        private const val KEY_LOADED_APP_VERSION = "loaded_app_version"
        private const val KEY_LOADED_MANIFEST_SCHEMA = "loaded_manifest_schema"

        // Bump whenever catalog_manifest.txt gains/loses columns or changes semantics
        // so a one-time reload runs on the next launch (regardless of app version).
        //   v1 — 4 fields: path | title | coverUrl | popularityIndex
        //   v2 — 5 fields: + isRepresentative (catalog grouping flag)
        //   v3 — megacd (scd) system added to catalog
        private const val MANIFEST_SCHEMA_VERSION = 3

        // catalog_manifest.txt uses abbreviated folder names that differ from
        // Lemuroid's SystemID.dbname. Map them so DB rows carry the correct systemId.
        private val MANIFEST_ALIAS = mapOf(
            "a26" to "atari2600",
            "a78" to "atari7800",
            "mame2003Plus" to "mame2003plus",
            "megadrive" to "md",
            "megacd" to "scd",
        )

        // Sentinel prefix written by the build-time PrebuiltDbGenerator (buildSrc).
        // ManifestQuickLoader rewrites these into real file:// URIs on first launch.
        private const val PREBUILT_URI_PREFIX = "file:///lemuroid_prebuilt"
    }

    /**
     * Runs the manifest-first catalog load. Idempotent — safe to call on every startup.
     *
     * Skips all I/O if the catalog was already loaded for the current app version,
     * making subsequent launches instant.
     */
    suspend fun load(): LoadResult = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val appVersion = currentAppVersion()
        val loadedSchema = prefs.getInt(KEY_LOADED_MANIFEST_SCHEMA, -1)

        // Rewrite sentinel URIs (no-op unless the prebuilt asset was used). Wrapped in try/catch
        // because any failure here (DAO missing, schema mismatch, etc.) must NOT crash the boot
        // sequence — the catalog can still be rebuilt by the load path below.
        try {
            val romsDir = directoriesManager.getInternalRomsDirectory()
            val realPrefix = romsDir.toUri().toString().trimEnd('/')
            val rewritten = database.gameDao().rewritePrebuiltUris(PREBUILT_URI_PREFIX, realPrefix)
            if (rewritten > 0) {
                Timber.i("ManifestQuickLoader: rewrote $rewritten prebuilt URIs to $realPrefix")
            }
        } catch (t: Throwable) {
            Timber.e(t, "ManifestQuickLoader: prebuilt URI rewrite failed (continuing)")
        }

        // Skip only when both the app version and the manifest schema match what's already
        // loaded. Bumping MANIFEST_SCHEMA_VERSION forces a single reload across all users
        // so new manifest fields (e.g. isRepresentative in v2) flow into the DB.
        if (prefs.getInt(KEY_LOADED_APP_VERSION, -1) == appVersion &&
            loadedSchema == MANIFEST_SCHEMA_VERSION
        ) {
            _catalogReady.value = true
            return@withContext LoadResult(0)
        }

        val manifest = catalogCoverProvider.getAllEntries()
        if (manifest.isEmpty()) {
            Timber.w("ManifestQuickLoader: empty manifest — nothing to load")
            return@withContext LoadResult(0)
        }

        // Fast path: if the DB is already fully populated (prebuilt asset path, or a prior
        // successful load), skip the INSERT OR IGNORE + per-row UPDATE pass entirely. Wrapped
        // in try/catch so a DAO/schema failure doesn't block the full-load fallback below.
        val existingCount = try {
            database.gameDao().countAll()
        } catch (t: Throwable) {
            Timber.e(t, "ManifestQuickLoader: countAll failed; assuming empty")
            0
        }
        val expectedSize = manifest.size
        if (existingCount >= expectedSize - expectedSize / 50) {
            prefs.edit()
                .putInt(KEY_LOADED_APP_VERSION, appVersion)
                .putInt(KEY_LOADED_MANIFEST_SCHEMA, MANIFEST_SCHEMA_VERSION)
                .apply()
            Timber.i(
                "ManifestQuickLoader: fast-skip (DB has $existingCount/${expectedSize} games)",
            )
            _catalogReady.value = true
            return@withContext LoadResult(0)
        }

        val now = System.currentTimeMillis()
        val games = mutableListOf<Game>()
        val romsDir = directoriesManager.getInternalRomsDirectory()

        for ((key, entry) in manifest) {
            val slash = key.indexOf('/')
            if (slash < 0) continue
            val rawSystemId = key.substring(0, slash)
            val systemId = MANIFEST_ALIAS[rawSystemId] ?: rawSystemId
            val fileName = key.substring(slash + 1)

            if (GameSystem.findByIdOrNull(systemId) == null) continue

            val fileUri = File(File(romsDir, systemId), fileName).toUri().toString()
            val title = entry.title?.takeIf { it.isNotBlank() } ?: fileName.substringBeforeLast(".")

            games += Game(
                fileName = fileName,
                fileUri = fileUri,
                title = title,
                systemId = systemId,
                developer = null,
                coverFrontUrl = entry.coverUrl,
                lastIndexedAt = now,
                popularityIndex = entry.popularityIndex,
                isRepresentative = entry.isRepresentative,
            )
        }

        val ids = database.gameDao().insertIfNotExists(games)
        val inserted = ids.count { it != -1L }

        // For rows that already existed (INSERT OR IGNORE skipped them), refresh the
        // manifest-derived fields (popularityIndex, isRepresentative) so changes to the
        // catalog between app versions are picked up.
        val existingGames = games.zip(ids)
            .filter { (_, id) -> id == -1L }
            .map { (game, _) -> game }
        if (existingGames.isNotEmpty()) {
            database.withTransaction {
                for (game in existingGames) {
                    database.gameDao().updateManifestFields(
                        fileUri = game.fileUri,
                        popularityIndex = game.popularityIndex,
                        isRepresentative = game.isRepresentative,
                    )
                }
            }
        }

        prefs.edit()
            .putInt(KEY_LOADED_APP_VERSION, appVersion)
            .putInt(KEY_LOADED_MANIFEST_SCHEMA, MANIFEST_SCHEMA_VERSION)
            .apply()
        Timber.i("ManifestQuickLoader: inserted=$inserted total=${games.size}")
        _catalogReady.value = true
        LoadResult(inserted)
    }

    @Suppress("DEPRECATION")
    private fun currentAppVersion(): Int = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode.toInt()
        } else {
            context.packageManager.getPackageInfo(context.packageName, 0).versionCode
        }
    } catch (_: Exception) {
        -2 // never matches the stored default of -1, so forces a re-run on error
    }
}
