package com.swordfish.lemuroid.lib.library.catalog

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import androidx.core.net.toUri
import com.swordfish.lemuroid.lib.library.GameSystem
import com.swordfish.lemuroid.lib.library.db.RetrogradeDatabase
import com.swordfish.lemuroid.lib.library.db.entity.Game
import com.swordfish.lemuroid.lib.storage.DirectoriesManager
import androidx.room.withTransaction
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import timber.log.Timber

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
        private const val KEY_FTS_OPTIMIZED = "fts_optimized_version"

        // Bump to force a one-time FTS index defragmentation (GameSearchDao.optimize) across
        // all installs. Independent of MANIFEST_SCHEMA_VERSION / app version. Needed because
        // the bulk UPDATEs below (sentinel-URI rewrite, manifest-field refresh) fire the FTS
        // delete/insert triggers on every touched row and fragment the index into many
        // segments, making search (MATCH) progressively slower. v1 — first cleanup pass for
        // installs already fragmented by the first-boot URI rewrite.
        private const val FTS_MAINTENANCE_VERSION = 1

        // Bump whenever catalog_manifest.txt gains/loses columns or changes semantics
        // so a one-time reload runs on the next launch (regardless of app version).
        //   v1 — 4 fields: path | title | coverUrl | popularityIndex
        //   v2 — 5 fields: + isRepresentative (catalog grouping flag)
        //   v3 — megacd (scd) system added to catalog
        //   v4 — sg1000/colecovision/virtualboy/c64/zxspectrum/amstradcpc/vectrex/intellivision/pokemini/supervision added
        //   v5 — title cleanup for new systems (filename-derived instead of bad IGDB fuzzy match)
        //   v6 — isRepresentative corrected for sg1000/pokemini/msx2 (all had only 1 rep due to empty titles in input)
        //          also fixed malformed fused line (Zukkoke/007 James Bond Alt); fast-skip bug fix in loader
        //   v7 — fixed malformed 3do/Dreamcast boundary line (Zhadnost/Broadband Passport)
        //   v8 — fixed vircon32 entries: double path (vircon32/vircon32/) → vircon32/ + added pipe metadata
        //   v9 — re-run v8 cleanup for devices that ran v8 before cleanup code existed
        //   v10  sega32x (456 games) added to catalog_manifest.txt
        //   v11  fds (Famicom Disk System, 268 games) added; FDS system registered in GameSystem
        //   v12  gc (Nintendo GameCube, 1086 games) added; GAMECUBE system + Dolphin core registered
        //   v13  saturn (Sega Saturn) added; SATURN system + YabaSanshiro core registered
        //   v14  jaguar (Atari Jaguar) added; JAGUAR system + Virtual Jaguar core registered
        //   v15  odyssey2 (Magnavox Odyssey2) added; ODYSSEY2 system + O2EM core registered
        //   v16  neocd (SNK Neo Geo CD) added; NEOCD system + NeoCD core registered
        //   v17  amiga (Commodore Amiga) added; AMIGA system + PUAE core registered
        //   v18  amiga1200/amigacd32/amigacdtv added (PUAE model variants of the Amiga)
        //   v19  pcfx (NEC PC-FX) added; PCFX system + Beetle PC-FX core registered
        //   v20  gw (Nintendo Game & Watch) added
        //   v21  atarist (Atari ST) added; ATARI_ST system + Hatari core registered
        //   v22  dc (Sega Dreamcast, 1425 games) re-added — see bug fix
        //          documentacao/bugs/done/2026-07-07-dreamcast-crash-boot-ashmem-libandroid.md
        //   v23  dc broken titles removed (GTA2, RE3, Soul Reaver, Worms Armageddon — hang at
        //          boot in the Flycast core; see bugs/open/2026-07-08-dreamcast-subset-jogos-
        //          travam-9fps.md). Manifest lines dropped + one-time DB delete below.
        private const val MANIFEST_SCHEMA_VERSION = 23

        // Titles removed in v23 — deleted from existing installs' DBs (search/FTS shows any
        // row in `games`, so hiding requires actual deletion, not isRepresentative=0).
        private val DC_BROKEN_TITLES = listOf(
            "Grand Theft Auto 2",
            "Resident Evil 3: Nemesis",
            "Legacy of Kain: Soul Reaver",
            "Worms Armageddon",
        )

        // catalog_manifest.txt uses abbreviated folder names that differ from
        // Lemuroid's SystemID.dbname. The mapping (manifest folder → dbname) lives in
        // assets/manifest_alias.json — the single source of truth shared with the
        // build-time PrebuiltDbGenerator (buildSrc), which reads the same file from disk.
        // Kept as a file rather than a Kotlin constant because buildSrc cannot depend
        // on Android modules; duplicating it as a constant caused a drift bug.
        private const val MANIFEST_ALIAS_ASSET = "manifest_alias.json"

        @Volatile private var cachedManifestAlias: Map<String, String>? = null

        /**
         * Loads the manifest folder → dbname alias map from [MANIFEST_ALIAS_ASSET].
         * Cached after first read. Also consumed by `RomSystemMapper` to translate
         * dbname → manifest folder when looking up systems in mnemonico_map.json.
         */
        fun loadManifestAlias(context: Context): Map<String, String> {
            cachedManifestAlias?.let { return it }
            return synchronized(this) {
                cachedManifestAlias ?: run {
                    val parsed = try {
                        val json = context.assets.open(MANIFEST_ALIAS_ASSET)
                            .bufferedReader().use { it.readText() }
                        val obj = org.json.JSONObject(json)
                        buildMap {
                            val keys = obj.keys()
                            while (keys.hasNext()) {
                                val k = keys.next()
                                put(k, obj.getString(k))
                            }
                        }
                    } catch (e: Exception) {
                        Timber.e(e, "Failed to load $MANIFEST_ALIAS_ASSET")
                        emptyMap()
                    }
                    parsed.also { cachedManifestAlias = it }
                }
            }
        }

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

        // v8 one-time cleanup: vircon32 manifest had double path (vircon32/vircon32/file.zip)
        // which caused fileName = "vircon32/file.zip" in DB. Delete those stale entries so
        // the correct ones (fileName = "file.zip") can be inserted below. Also migrate any
        // already-downloaded files from the wrong nested directory to the correct flat path.
        if (loadedSchema < 9) {
            try {
                val romsDir = directoriesManager.getInternalRomsDirectory()
                val wrongEntries = database.gameDao().selectBySystemWithNestedPath("vircon32")
                for (game in wrongEntries) {
                    val oldFile = Uri.parse(game.fileUri).path?.let { File(it) } ?: continue
                    if (oldFile.exists() && oldFile.length() > 0L) {
                        val newFile = File(File(romsDir, "vircon32"), File(game.fileName).name)
                        runCatching { oldFile.renameTo(newFile) }
                    }
                }
                val deleted = database.gameDao().deleteBySystemWithNestedPath("vircon32")
                Timber.i("ManifestQuickLoader: v8 cleanup removed $deleted stale vircon32 entries")
            } catch (t: Throwable) {
                Timber.e(t, "ManifestQuickLoader: v8 cleanup failed (continuing)")
            }
        }

        // v23 one-time cleanup: remove Dreamcast titles confirmed broken in the Flycast core
        // (hang at boot). The manifest no longer carries them (fresh installs are clean); this
        // deletes the rows already present in existing installs so they vanish from the
        // catalog AND from search (FTS rows drop via the games_bd trigger).
        if (loadedSchema < 23) {
            try {
                val deleted = database.gameDao().deleteBySystemAndTitles("dc", DC_BROKEN_TITLES)
                Timber.i("ManifestQuickLoader: v23 cleanup removed $deleted broken dc games")
            } catch (t: Throwable) {
                Timber.e(t, "ManifestQuickLoader: v23 cleanup failed (continuing)")
            }
        }

        // Skip only when both the app version and the manifest schema match what's already
        // loaded. Bumping MANIFEST_SCHEMA_VERSION forces a single reload across all users
        // so new manifest fields (e.g. isRepresentative in v2) flow into the DB.
        if (prefs.getInt(KEY_LOADED_APP_VERSION, -1) == appVersion &&
            loadedSchema == MANIFEST_SCHEMA_VERSION
        ) {
            // Common every-boot path. Signal readiness first so Home isn't gated on the
            // (one-time) FTS defrag below, which cleans up installs fragmented by a past
            // first-boot URI rewrite from before this maintenance existed.
            _catalogReady.value = true
            maybeOptimizeFtsIndex(prefs)
            return@withContext LoadResult(0)
        }

        val manifest = catalogCoverProvider.getAllEntries()
        if (manifest.isEmpty()) {
            Timber.w("ManifestQuickLoader: empty manifest — nothing to load")
            return@withContext LoadResult(0)
        }

        // Fast path: if the DB is already fully populated (prebuilt asset path, or a prior
        // successful load) AND the manifest schema is already up-to-date, skip the
        // INSERT OR IGNORE + per-row UPDATE pass entirely. We must NOT fast-skip when the
        // schema version changed, because that means manifest fields (title, isRepresentative,
        // popularityIndex) may have been updated and existing rows need to be refreshed.
        val existingCount = try {
            database.gameDao().countAll()
        } catch (t: Throwable) {
            Timber.e(t, "ManifestQuickLoader: countAll failed; assuming empty")
            0
        }
        val expectedSize = manifest.size
        if (existingCount >= expectedSize - expectedSize / 50 && loadedSchema == MANIFEST_SCHEMA_VERSION) {
            prefs.edit()
                .putInt(KEY_LOADED_APP_VERSION, appVersion)
                .putInt(KEY_LOADED_MANIFEST_SCHEMA, MANIFEST_SCHEMA_VERSION)
                .apply()
            Timber.i(
                "ManifestQuickLoader: fast-skip (DB has $existingCount/${expectedSize} games)",
            )
            _catalogReady.value = true
            maybeOptimizeFtsIndex(prefs)
            return@withContext LoadResult(0)
        }

        val now = System.currentTimeMillis()
        val games = mutableListOf<Game>()
        val romsDir = directoriesManager.getInternalRomsDirectory()
        val manifestAlias = loadManifestAlias(context)

        for ((key, entry) in manifest) {
            val slash = key.indexOf('/')
            if (slash < 0) continue
            val rawSystemId = key.substring(0, slash)
            val systemId = manifestAlias[rawSystemId] ?: rawSystemId
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
                    database.gameDao().updateManifestFieldsWithTitle(
                        fileUri = game.fileUri,
                        title = game.title,
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
        // This path just ran the sentinel-URI rewrite and/or the manifest-field refresh, both
        // of which fragment the FTS index — defrag unconditionally to keep search fast. After
        // _catalogReady so Home isn't gated on it.
        optimizeFtsIndex(prefs)
        LoadResult(inserted)
    }

    /**
     * Runs the FTS defrag only if it hasn't been done for the current FTS_MAINTENANCE_VERSION.
     * Used on the fast-return / fast-skip paths so already-fragmented installs get cleaned up
     * exactly once without paying the (small) optimize cost on every boot.
     */
    private fun maybeOptimizeFtsIndex(prefs: SharedPreferences) {
        if (prefs.getInt(KEY_FTS_OPTIMIZED, -1) == FTS_MAINTENANCE_VERSION) return
        optimizeFtsIndex(prefs)
    }

    /** Merges the FTS4 index segments into one (see GameSearchDao.optimize) and records it. */
    private fun optimizeFtsIndex(prefs: SharedPreferences) {
        try {
            database.gameSearchDao().optimize(database.openHelper.writableDatabase)
            prefs.edit().putInt(KEY_FTS_OPTIMIZED, FTS_MAINTENANCE_VERSION).apply()
            Timber.i("ManifestQuickLoader: FTS index optimized (defragmented)")
        } catch (t: Throwable) {
            Timber.e(t, "ManifestQuickLoader: FTS optimize failed (continuing)")
        }
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
