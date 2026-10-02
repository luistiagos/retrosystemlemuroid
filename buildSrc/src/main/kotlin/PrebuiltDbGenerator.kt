/*
 * PrebuiltDbGenerator.kt
 *
 * Builds the `retrograde-prebuilt.db` SQLite asset embedded in the APK, mirroring the
 * schema that Room generates at runtime and pre-populating
 * the `games` and `fts_games` tables from `catalog_manifest.txt`.
 *
 * On first launch, the Android app uses `Room.databaseBuilder(...).createFromAsset(...)` to
 * copy this file into place instead of running 29k+ INSERTs through Room — that's what
 * eliminates the "preparando ambiente" wait on fresh installs.
 *
 * The generated DB carries:
 *   • `room_master_table` with the exact identity_hash from the Room schema JSON
 *     (Room refuses to open a DB whose hash doesn't match the one its annotation processor
 *     produced for the same @Database class.)
 *   • all entity tables and indices from the Room schema JSON (Game, DataFile, DownloadedRom, SaveQueueItem)
 *   • the FTS4 virtual table + triggers that GameSearchDao defines manually
 *   • `user_version` from the Room schema JSON so Room treats the DB as already migrated
 *
 * Sentinel fileUri: rows are inserted with `fileUri = "file:///lemuroid_prebuilt/<systemId>/<fileName>"`,
 * the path percent-encoded exactly like Android's `File.toUri()` (see [encodeUriPath]).
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

    /** Characters `android.net.Uri.encode` leaves as-is besides letters and digits. */
    private const val URI_UNRESERVED = "_-!.~'()*"

    private val HEX_DIGITS = "0123456789ABCDEF".toCharArray()

    /**
     * Known answers taken from `File.toUri()` on a device (API 25 AVD, 2026-10-02), relative to
     * the ROMs dir — one per special character in the catalog. [validate] fails the build if
     * [encodeUriPath] stops reproducing them.
     */
    private val URI_PATH_KNOWN_ANSWERS = mapOf(
        "nes/Final Fantasy 3 (JP) (3DS Virtual Console).nes" to
            "nes/Final%20Fantasy%203%20(JP)%20(3DS%20Virtual%20Console).nes",
        "atari800/Montana Test #7 (19xx)(-)[k-file].zip" to
            "atari800/Montana%20Test%20%237%20(19xx)(-)%5Bk-file%5D.zip",
        "psx/100% Star (Europe).chd" to "psx/100%25%20Star%20(Europe).chd",
        "pce/pcengine/Yo, Bro (USA).pce" to "pce/pcengine/Yo%2C%20Bro%20(USA).pce",
        "snes/Joe & Mac I.zip" to "snes/Joe%20%26%20Mac%20I.zip",
        "atari800/ik+.zip" to "atari800/ik%2B.zip",
        "dc/Hundred Swords (Japan) (@barai).chd" to "dc/Hundred%20Swords%20(Japan)%20(%40barai).chd",
        "gba/WarioWare, Inc. - Mega Microgame$! (US).gba" to
            "gba/WarioWare%2C%20Inc.%20-%20Mega%20Microgame%24!%20(US).gba",
        "atari800/The Way = Jesus.zip" to "atari800/The%20Way%20%3D%20Jesus.zip",
        "psp/Steins;Gate (Japan).iso" to "psp/Steins%3BGate%20(Japan).iso",
        "psx/Galaxian^3 (Europe).chd" to "psx/Galaxian%5E3%20(Europe).chd",
        "atari800/Gem'y.zip" to "atari800/Gem'y.zip",
        "atari2600/Indy 500 ~ Race (USA).a26" to "atari2600/Indy%20500%20~%20Race%20(USA).a26",
        "lowresnx/Bézier Curve (remix).nx" to "lowresnx/Be%CC%81zier%20Curve%20(remix).nx",
    )

    /**
     * Same output as `android.net.Uri.encode(path, "/")`, which is what `File.toUri()` (via
     * `Uri.fromFile`) puts in the path — the form ManifestQuickLoader builds at runtime. The
     * sentinel rewrite only swaps the prefix, so a suffix in any other form never matches the
     * loader's URI: `games.fileUri` is the unique key, and the first full manifest pass would
     * insert a second row and delete this one (~53k rows on a fresh install).
     *
     * Not `URLEncoder` (space → `+`, encodes `'()*!~`) nor `java.net.URI` (leaves `,&+$@=;` and
     * non-ASCII alone): only a copy of the Android algorithm matches byte for byte. Runs of
     * disallowed chars are encoded together so surrogate pairs come out as one UTF-8 sequence.
     */
    private fun encodeUriPath(path: String): String {
        val out = StringBuilder(path.length)
        var i = 0
        while (i < path.length) {
            if (isUriPathAllowed(path[i])) {
                out.append(path[i++])
                continue
            }
            var end = i + 1
            while (end < path.length && !isUriPathAllowed(path[end])) end++
            for (byte in path.substring(i, end).toByteArray(Charsets.UTF_8)) {
                val v = byte.toInt() and 0xff
                out.append('%').append(HEX_DIGITS[v shr 4]).append(HEX_DIGITS[v and 0xf])
            }
            i = end
        }
        return out.toString()
    }

    private fun isUriPathAllowed(c: Char): Boolean =
        c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c in URI_UNRESERVED || c == '/'

    /** Inverse of [encodeUriPath]; only used by [validate]. */
    private fun decodeUriPath(encoded: String): String {
        val bytes = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < encoded.length) {
            if (encoded[i] == '%') {
                bytes.write(encoded.substring(i + 1, i + 3).toInt(16))
                i += 3
            } else {
                bytes.write(encoded[i++].code)
            }
        }
        return bytes.toString(Charsets.UTF_8.name())
    }

    /** `NAME("dbname"),` entries of the `SystemID` enum. */
    private val SYSTEM_ID_ENTRY = Regex("""^\s*[A-Z][A-Z0-9_]*\("([^"]+)"\)""", RegexOption.MULTILINE)

    /**
     * dbnames declared in `SystemID.kt`, read from source because buildSrc cannot depend on
     * the Android module. Every SystemID is registered in GameSystem, so this is the set that
     * `GameSystem.findByIdOrNull` accepts at runtime.
     */
    private fun loadSystemDbNames(systemIdFile: File): Set<String> {
        require(systemIdFile.exists()) { "SystemID.kt not found at $systemIdFile" }
        val names = SYSTEM_ID_ENTRY.findAll(systemIdFile.readText()).map { it.groupValues[1] }.toSet()
        require(names.isNotEmpty()) { "no SystemID entries parsed from $systemIdFile" }
        return names
    }

    /**
     * ManifestQuickLoader skips any row whose post-alias systemId is not a GameSystem, and its
     * stale-catalog cleanup then deletes the prebuilt copy — the whole system silently vanishes
     * on every install (amiga500/ and gameandwatch/ without alias: 1637 rows). Fail the build.
     */
    private fun requireKnownSystems(games: List<GameRow>, systemDbNames: Set<String>) {
        val unknown = games.groupingBy { it.systemId }.eachCount().filterKeys { it !in systemDbNames }
        require(unknown.isEmpty()) {
            "catalog_manifest.txt has systems that are not a SystemID dbname after $MANIFEST_ALIAS_ASSET: " +
                unknown.entries.joinToString { "${it.key} (${it.value} rows)" } +
                ". Add the manifest folder → dbname mapping to $MANIFEST_ALIAS_ASSET."
        }
    }

    fun generate(
        schemaJsonFile: File,
        manifestFile: File,
        systemIdFile: File,
        outputDbFile: File,
    ) {
        require(schemaJsonFile.exists()) {
            "Room schema JSON not found at $schemaJsonFile — build the app once so kapt generates it."
        }
        require(manifestFile.exists()) { "catalog_manifest.txt not found at $manifestFile" }
        val systemDbNames = loadSystemDbNames(systemIdFile)

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
            requireKnownSystems(games, systemDbNames)
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
                    fileUri = "$PREBUILT_URI_PREFIX/${encodeUriPath(canonicalPath)}",
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
        // Merge the freshly-built index into a single segment so the shipped DB starts optimal
        // for MATCH queries. Runtime keeps it that way via GameSearchDao.optimize after the
        // heavy manifest writes (URI rewrite / field refresh) that would otherwise fragment it.
        conn.createStatement().use {
            it.execute("INSERT INTO fts_games(fts_games) VALUES('optimize')")
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

        // fileUri must be in the exact form ManifestQuickLoader builds with File.toUri(),
        // otherwise the first manifest pass deletes and re-inserts the row (see encodeUriPath).
        for ((decoded, expected) in URI_PATH_KNOWN_ANSWERS) {
            val actual = encodeUriPath(decoded)
            require(actual == expected) {
                "encodeUriPath diverged from Android's File.toUri(): \"$decoded\" -> \"$actual\", " +
                    "expected \"$expected\""
            }
        }
        val encodedPathForm = Regex("""(?:[A-Za-z0-9_\-!.~'()*/]|%[0-9A-F]{2})*""")
        conn.createStatement()
            .executeQuery("SELECT systemId, fileName, fileUri FROM games")
            .use { rs ->
                while (rs.next()) {
                    val canonicalPath = "${rs.getString(1)}/${rs.getString(2)}"
                    val fileUri = rs.getString(3)
                    val encoded = fileUri.removePrefix("$PREBUILT_URI_PREFIX/")
                    require(
                        encoded != fileUri &&
                            encodedPathForm.matches(encoded) &&
                            decodeUriPath(encoded) == canonicalPath,
                    ) { "fileUri not in File.toUri() form for \"$canonicalPath\": $fileUri" }
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
