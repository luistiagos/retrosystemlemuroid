/*
 * PrebuiltDbGenerator.kt
 *
 * Builds the `retrograde-prebuilt.db` SQLite asset embedded in the APK, mirroring the
 * schema that Room generates at runtime (version 23 — see schemas/23.json) and pre-populating
 * the `games` and `fts_games` tables from `catalog_manifest.txt`.
 *
 * On first launch, the Android app uses `Room.databaseBuilder(...).createFromAsset(...)` to
 * copy this file into place instead of running 29k+ INSERTs through Room — that's what
 * eliminates the "preparando ambiente" wait on fresh installs.
 *
 * The generated DB carries:
 *   • `room_master_table` with the exact identity_hash from schemas/23.json
 *     (Room refuses to open a DB whose hash doesn't match the one its annotation processor
 *     produced for the same @Database class.)
 *   • all entity tables and indices from schemas/23.json (Game, DataFile, DownloadedRom, SaveQueueItem)
 *   • the FTS4 virtual table + triggers that GameSearchDao defines manually
 *   • `user_version = 23` so Room treats the DB as already migrated
 *
 * Sentinel fileUri: rows are inserted with `fileUri = "file:///lemuroid_prebuilt/<systemId>/<fileName>"`.
 * The app rewrites these to real `file://<romsDir>/...` URIs in a single SQL UPDATE on first boot
 * (see ManifestQuickLoader.rewritePrebuiltUris).
 *
 * Validation: this generator self-validates the produced DB before returning. If any check
 * fails the Gradle task fails with a clear message — that's the build-time guard against
 * shipping a DB that Room would later reject and crash on.
 */

package com.swordfish.lemuroid.builder

import org.json.JSONObject
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

object PrebuiltDbGenerator {

    /**
     * Asset file holding the manifest folder → dbname alias map. Single source of truth,
     * shared with ManifestQuickLoader (which reads the same file from Android assets at
     * runtime). buildSrc cannot depend on Android modules, so both sides read the file
     * rather than duplicating a Kotlin constant.
     */
    private const val MANIFEST_ALIAS_ASSET = "manifest_alias.json"

    /** Loads the manifest folder → dbname alias map from the JSON next to the manifest. */
    private fun loadManifestAlias(manifestFile: File): Map<String, String> {
        val aliasFile = File(manifestFile.parentFile, MANIFEST_ALIAS_ASSET)
        require(aliasFile.exists()) {
            "$MANIFEST_ALIAS_ASSET not found next to the manifest at ${aliasFile.absolutePath}"
        }
        val obj = JSONObject(aliasFile.readText())
        return buildMap {
            for (key in obj.keys()) {
                put(key, obj.getString(key))
            }
        }
    }

    /**
     * URI prefix used for placeholder ROM paths in the prebuilt DB. ManifestQuickLoader rewrites
     * all rows that start with this prefix to point at the actual romsDir on first launch.
     */
    private const val PREBUILT_URI_PREFIX = "file:///lemuroid_prebuilt"

    fun generate(
        schemaJsonFile: File,
        manifestFile: File,
        outputDbFile: File,
    ) {
        require(schemaJsonFile.exists()) {
            "schemas/23.json not found at $schemaJsonFile — build the app once so kapt generates it."
        }
        require(manifestFile.exists()) { "catalog_manifest.txt not found at $manifestFile" }

        val schemaJson = JSONObject(schemaJsonFile.readText()).getJSONObject("database")
        val expectedVersion = schemaJson.getInt("version")
        val identityHash = schemaJson.getString("identityHash")

        outputDbFile.parentFile.mkdirs()
        if (outputDbFile.exists()) outputDbFile.delete()

        Class.forName("org.sqlite.JDBC")
        val jdbcUrl = "jdbc:sqlite:${outputDbFile.absolutePath}"
        DriverManager.getConnection(jdbcUrl).use { conn ->
            // PRAGMAs MUST be applied before any transaction is open. SQLite refuses to
            // change `synchronous` or `journal_mode` mid-transaction.
            applyBulkLoadPragmas(conn)

            // Room stores schema version in PRAGMA user_version (matches what RoomOpenHelper checks).
            conn.createStatement().use { it.execute("PRAGMA user_version = $expectedVersion") }

            conn.autoCommit = false

            createEntityTables(conn, schemaJson)
            createFtsTableAndUpdateDeleteTriggers(conn)
            createRoomMasterTable(conn, identityHash)

            val manifestAlias = loadManifestAlias(manifestFile)
            val games = parseManifest(manifestFile, manifestAlias)
            insertGamesBulk(conn, games)

            populateFtsBulk(conn)
            createInsertTrigger(conn)

            conn.commit()

            validate(conn, expectedVersion, identityHash, games.size)
        }

        println(
            "[PrebuiltDbGenerator] OK — ${outputDbFile.absolutePath} " +
                "(${outputDbFile.length() / 1024} KB)",
        )
    }

    private fun applyBulkLoadPragmas(conn: Connection) {
        conn.createStatement().use { stmt ->
            // synchronous = OFF + journal_mode = MEMORY make the bulk insert dramatically
            // faster. Safe here because we're building the file from scratch — if the build
            // crashes the file is regenerated next run.
            stmt.execute("PRAGMA synchronous = OFF")
            stmt.execute("PRAGMA journal_mode = MEMORY")
            stmt.execute("PRAGMA temp_store = MEMORY")
        }
    }

    private fun createEntityTables(conn: Connection, schema: JSONObject) {
        val entities = schema.getJSONArray("entities")
        for (i in 0 until entities.length()) {
            val entity = entities.getJSONObject(i)
            val tableName = entity.getString("tableName")
            val createSql = entity.getString("createSql").replace("\${TABLE_NAME}", tableName)
            conn.createStatement().use { it.execute(createSql) }

            if (entity.has("indices")) {
                val indices = entity.getJSONArray("indices")
                for (j in 0 until indices.length()) {
                    val index = indices.getJSONObject(j)
                    val indexSql = index.getString("createSql")
                        .replace("\${TABLE_NAME}", tableName)
                    conn.createStatement().use { it.execute(indexSql) }
                }
            }
        }
    }

    /**
     * FTS4 virtual table + UPDATE/DELETE triggers from GameSearchDao.MIGRATION.
     * The INSERT trigger (games_ai) is deliberately created LATER, after the bulk insert,
     * so each row doesn't trigger FTS tokenization 29k times.
     */
    private fun createFtsTableAndUpdateDeleteTriggers(conn: Connection) {
        conn.createStatement().use {
            it.execute(
                """
                CREATE VIRTUAL TABLE fts_games USING FTS4(
                    tokenize=unicode61 "remove_diacritics=1",
                    content="games",
                    title)
                """.trimIndent(),
            )
        }
        conn.createStatement().use {
            it.execute(
                """
                CREATE TRIGGER games_bu BEFORE UPDATE ON games BEGIN
                    DELETE FROM fts_games WHERE docid=old.id;
                END
                """.trimIndent(),
            )
        }
        conn.createStatement().use {
            it.execute(
                """
                CREATE TRIGGER games_bd BEFORE DELETE ON games BEGIN
                    DELETE FROM fts_games WHERE docid=old.id;
                END
                """.trimIndent(),
            )
        }
        conn.createStatement().use {
            it.execute(
                """
                CREATE TRIGGER games_au AFTER UPDATE ON games BEGIN
                    INSERT INTO fts_games(docid, title) VALUES(new.id, new.title);
                END
                """.trimIndent(),
            )
        }
    }

    private fun createInsertTrigger(conn: Connection) {
        conn.createStatement().use {
            it.execute(
                """
                CREATE TRIGGER games_ai AFTER INSERT ON games BEGIN
                    INSERT INTO fts_games(docid, title) VALUES(new.id, new.title);
                END
                """.trimIndent(),
            )
        }
    }

    private fun createRoomMasterTable(conn: Connection, identityHash: String) {
        // Room's RoomMasterTable uses id = 42. The exact DDL is mandated by androidx.room.
        conn.createStatement().use {
            it.execute(
                "CREATE TABLE room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)",
            )
        }
        conn.prepareStatement(
            "INSERT OR REPLACE INTO room_master_table (id, identity_hash) VALUES (42, ?)",
        ).use {
            it.setString(1, identityHash)
            it.executeUpdate()
        }
    }

    private data class GameRow(
        val fileName: String,
        val fileUri: String,
        val title: String,
        val systemId: String,
        val coverFrontUrl: String?,
        val lastIndexedAt: Long,
        val popularityIndex: Int,
        val isRepresentative: Boolean,
    )

    private fun parseManifest(file: File, manifestAlias: Map<String, String>): List<GameRow> {
        val games = mutableListOf<GameRow>()
        val now = System.currentTimeMillis()
        // Track collisions on (systemId/fileName) so we don't violate the UNIQUE index on
        // fileUri. The catalog occasionally has the same path appearing under different
        // raw system names that alias to the same canonical id (very rare, but defensive).
        val seenPaths = HashSet<String>()

        file.useLines { lines ->
            for (raw in lines) {
                val line = raw.trim()
                if (line.isEmpty()) continue

                val parts = line.split('|')
                val path = parts.getOrNull(0)?.takeIf { it.isNotBlank() } ?: continue
                val slash = path.indexOf('/')
                if (slash < 0) continue

                val rawSystemId = path.substring(0, slash)
                val systemId = manifestAlias[rawSystemId] ?: rawSystemId
                val fileName = path.substring(slash + 1)
                val canonicalPath = "$systemId/$fileName"
                if (!seenPaths.add(canonicalPath)) continue

                val title = parts.getOrNull(1)?.takeIf { it.isNotBlank() }
                    ?: fileName.substringBeforeLast('.')
                val coverFrontUrl = parts.getOrNull(2)?.takeIf { it.isNotBlank() }
                val popularityIndex = parts.getOrNull(3)?.toIntOrNull() ?: 0
                val isRepresentative = parts.getOrNull(4)?.trim()?.let { it != "0" } ?: true

                games += GameRow(
                    fileName = fileName,
                    fileUri = "$PREBUILT_URI_PREFIX/$canonicalPath",
                    title = title,
                    systemId = systemId,
                    coverFrontUrl = coverFrontUrl,
                    lastIndexedAt = now,
                    popularityIndex = popularityIndex,
                    isRepresentative = isRepresentative,
                )
            }
        }
        return games
    }

    private fun insertGamesBulk(conn: Connection, games: List<GameRow>) {
        val sql = """
            INSERT INTO games (
                fileName, fileUri, title, systemId, developer, coverFrontUrl,
                lastIndexedAt, lastPlayedAt, isFavorite, popularityIndex, isRepresentative
            ) VALUES (?, ?, ?, ?, NULL, ?, ?, NULL, 0, ?, ?)
        """.trimIndent()
        conn.prepareStatement(sql).use { stmt ->
            for (game in games) {
                stmt.setString(1, game.fileName)
                stmt.setString(2, game.fileUri)
                stmt.setString(3, game.title)
                stmt.setString(4, game.systemId)
                if (game.coverFrontUrl != null) {
                    stmt.setString(5, game.coverFrontUrl)
                } else {
                    stmt.setNull(5, java.sql.Types.VARCHAR)
                }
                stmt.setLong(6, game.lastIndexedAt)
                stmt.setInt(7, game.popularityIndex)
                stmt.setInt(8, if (game.isRepresentative) 1 else 0)
                stmt.addBatch()
            }
            stmt.executeBatch()
        }
    }

    private fun populateFtsBulk(conn: Connection) {
        conn.createStatement().use {
            it.execute("INSERT INTO fts_games(docid, title) SELECT id, title FROM games")
        }
    }

    /**
     * Build-time validation. Failing any of these assertions means the DB would not be
     * accepted by Room at runtime — so we fail the Gradle build loudly instead of shipping
     * an APK that crashes on first launch.
     */
    private fun validate(
        conn: Connection,
        expectedVersion: Int,
        expectedHash: String,
        expectedGameCount: Int,
    ) {
        // PRAGMA user_version
        conn.createStatement().executeQuery("PRAGMA user_version").use { rs ->
            require(rs.next())
            val v = rs.getInt(1)
            require(v == expectedVersion) {
                "user_version mismatch: got $v, expected $expectedVersion"
            }
        }

        // identity_hash in room_master_table
        conn.createStatement()
            .executeQuery("SELECT identity_hash FROM room_master_table WHERE id = 42")
            .use { rs ->
                require(rs.next()) { "room_master_table row id=42 not found" }
                val hash = rs.getString(1)
                require(hash == expectedHash) {
                    "identity_hash mismatch: got $hash, expected $expectedHash"
                }
            }

        // games row count
        conn.createStatement().executeQuery("SELECT COUNT(*) FROM games").use { rs ->
            require(rs.next())
            val count = rs.getInt(1)
            require(count == expectedGameCount) {
                "games row count mismatch: got $count, expected $expectedGameCount"
            }
        }

        // fts_games row count matches games
        conn.createStatement().executeQuery("SELECT COUNT(*) FROM fts_games").use { rs ->
            require(rs.next())
            val ftsCount = rs.getInt(1)
            require(ftsCount == expectedGameCount) {
                "fts_games row count mismatch: got $ftsCount, expected $expectedGameCount"
            }
        }

        // Required tables exist
        val expectedTables = setOf(
            "games",
            "datafiles",
            "downloaded_roms",
            "save_queue",
            "fts_games",
            "room_master_table",
        )
        val actualTables = mutableSetOf<String>()
        conn.createStatement()
            .executeQuery("SELECT name FROM sqlite_master WHERE type IN ('table')")
            .use { rs ->
                while (rs.next()) actualTables.add(rs.getString(1))
            }
        val missing = expectedTables - actualTables
        require(missing.isEmpty()) { "Missing tables: $missing" }

        // Required triggers exist
        val expectedTriggers = setOf("games_ai", "games_au", "games_bu", "games_bd")
        val actualTriggers = mutableSetOf<String>()
        conn.createStatement()
            .executeQuery("SELECT name FROM sqlite_master WHERE type = 'trigger'")
            .use { rs ->
                while (rs.next()) actualTriggers.add(rs.getString(1))
            }
        val missingTriggers = expectedTriggers - actualTriggers
        require(missingTriggers.isEmpty()) { "Missing FTS triggers: $missingTriggers" }

        println(
            "[PrebuiltDbGenerator] validate: games=$expectedGameCount fts=$expectedGameCount " +
                "hash=$expectedHash version=$expectedVersion tables=${actualTables.size} " +
                "triggers=${actualTriggers.size}",
        )
    }
}
