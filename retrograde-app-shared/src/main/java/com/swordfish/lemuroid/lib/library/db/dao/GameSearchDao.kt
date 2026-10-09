package com.swordfish.lemuroid.lib.library.db.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.RawQuery
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteQuery
import com.swordfish.lemuroid.lib.library.db.entity.DownloadedRom
import com.swordfish.lemuroid.lib.library.db.entity.Game

class GameSearchDao(private val internalDao: Internal) {
    object CALLBACK : RoomDatabase.Callback() {
        override fun onCreate(db: SupportSQLiteDatabase) {
            super.onCreate(db)
            // Skip if the FTS table is already present — this happens when Room boots
            // from the prebuilt asset (`createFromAsset(...)` in LemuroidApplicationModule),
            // which ships with `fts_games` + triggers + content already in place.
            // Without this guard the CREATE VIRTUAL TABLE below throws "table already exists"
            // and the app crashes on first launch.
            val cursor = db.query("SELECT 1 FROM sqlite_master WHERE name = 'fts_games' LIMIT 1")
            val ftsAlreadyExists = cursor.use { it.moveToFirst() }
            if (!ftsAlreadyExists) {
                MIGRATION.migrate(db)
            }
        }
    }

    object MIGRATION : Migration(7, 8) {
        override fun migrate(database: SupportSQLiteDatabase) {
            // All CREATE statements are idempotent (IF NOT EXISTS) so this migration is safe to
            // re-run in the unlikely event of a partial install / interrupted onCreate.
            database.execSQL(
                """
                CREATE VIRTUAL TABLE IF NOT EXISTS fts_games USING FTS4(
                  tokenize=unicode61 "remove_diacritics=1",
                  content="games",
                  title);
                """,
            )
            database.execSQL(
                """
                CREATE TRIGGER IF NOT EXISTS games_bu BEFORE UPDATE ON games BEGIN
                  DELETE FROM fts_games WHERE docid=old.id;
                END;
                """,
            )
            database.execSQL(
                """
                CREATE TRIGGER IF NOT EXISTS games_bd BEFORE DELETE ON games BEGIN
                  DELETE FROM fts_games WHERE docid=old.id;
                END;
                """,
            )
            database.execSQL(
                """
                CREATE TRIGGER IF NOT EXISTS games_au AFTER UPDATE ON games BEGIN
                  INSERT INTO fts_games(docid, title) VALUES(new.id, new.title);
                END;
                """,
            )
            database.execSQL(
                """
                CREATE TRIGGER IF NOT EXISTS games_ai AFTER INSERT ON games BEGIN
                  INSERT INTO fts_games(docid, title) VALUES(new.id, new.title);
                END;
                """,
            )
            database.execSQL(
                """
                INSERT INTO fts_games(docid, title) SELECT id, title FROM games;
                """,
            )
        }
    }

    /**
     * Merges the FTS4 index segments back into a single segment ("defragmentation").
     *
     * Every UPDATE to `games` fires the `games_bu`/`games_au` triggers, which delete and
     * reinsert that row's FTS posting — regardless of whether `title` actually changed.
     * Bulk writes leave the index scattered across many small segments and each MATCH must
     * merge-scan all of them, so search gets slower as catalog churn accumulates (the
     * first-boot URI rewrite alone re-touches ~30k rows). The two biggest offenders:
     * `ManifestQuickLoader`'s sentinel-URI rewrite and its manifest-field refresh on schema
     * bumps. `optimize` collapses the segments back to one, restoring MATCH speed. Cheap and
     * safe to run off the UI thread; effectively a no-op if the index is already optimal.
     */
    fun optimize(db: SupportSQLiteDatabase) {
        db.execSQL("INSERT INTO fts_games(fts_games) VALUES('optimize')")
    }

    fun search(
        query: String,
        systemIds: List<String>? = null,
        onlyInstalled: Boolean = false,
        romsPrefix: String = "",
        managedMarker: String = "",
    ): PagingSource<Int, Game> {
        val matchArg = sanitizeFtsQuery(query)
        val args = mutableListOf<Any>(matchArg)
        val sql = StringBuilder()

        sql.append("""
            SELECT games.*
            FROM fts_games
            JOIN games ON games.id = fts_games.docid
        """)

        if (onlyInstalled) {
            sql.append("""
                LEFT JOIN downloaded_roms dr ON games.systemId = dr.systemId AND games.fileName = dr.fileName
            """)
        }

        sql.append(" WHERE fts_games MATCH ? ")

        if (!systemIds.isNullOrEmpty()) {
            val placeholders = systemIds.joinToString(",") { "?" }
            sql.append(" AND games.systemId IN ($placeholders) ")
            args.addAll(systemIds)
        }

        if (onlyInstalled) {
            require(romsPrefix.isNotEmpty() && managedMarker.isNotEmpty()) {
                "romsPrefix e managedMarker são obrigatórios quando onlyInstalled=true"
            }
            sql.append("""
                AND (
                    dr.fileName IS NOT NULL
                    OR (
                        games.fileUri NOT LIKE 'file:///lemuroid_prebuilt/%'
                        AND INSTR(games.fileUri, ?) = 0
                        AND SUBSTR(games.fileUri, 1, LENGTH(?)) <> ?
                    )
                )
            """)
            args.add(managedMarker)
            args.add(romsPrefix)
            args.add(romsPrefix)
        }

        sql.append(" LIMIT 100 ")

        return internalDao.rawSearch(SimpleSQLiteQuery(sql.toString(), args.toTypedArray()))
    }

    companion object {
        /** Runs of anything the unicode61 tokenizer does not consider part of a token. */
        private val NON_TOKEN_CHARS = "[^\\p{L}\\p{N}]+".toRegex()

        /**
         * Turns raw user input into an FTS4 MATCH expression made only of prefix terms.
         *
         * Punctuation must never survive into the expression, because MATCH input is *syntax*,
         * not text. Android builds SQLite without `SQLITE_ENABLE_FTS3_PARENTHESIS`, so FTS uses
         * the **standard** query syntax, in which a term glued to a preceding '-' is a negation:
         * typing "x-men" produced the expression `x NOT men*`, which asks for rows containing
         * "x" and *not* "men" — the one query guaranteed to hide every X-Men title. '"', '(',
         * ')', ':' (column filter) and '*' are syntax too, and FTS4 additionally reads a leading
         * '^' as "must be the first token".
         *
         * Splitting on every non-letter/non-digit run covers all of them at once and mirrors
         * exactly what the unicode61 tokenizer did when it built the index: "X-Men" is stored as
         * the tokens `x` + `men`, so it must be searched as `x* men*` (implicit AND).
         *
         * The trailing '*' on each term gives prefix matching ("Samurai Sho" finds "Samurai
         * Shodown") and, as a side effect, defuses the bare keywords AND/OR/NOT/NEAR — the
         * parser only treats those as operators when they are not followed by '*'.
         */
        private fun sanitizeFtsQuery(query: String): String {
            val terms = query.split(NON_TOKEN_CHARS).filter { it.isNotEmpty() }
            if (terms.isEmpty()) return "\"\""
            return terms.joinToString(" ") { "$it*" }
        }
    }

    @Dao
    interface Internal {
        @RawQuery(observedEntities = [Game::class, DownloadedRom::class])
        fun rawSearch(query: SupportSQLiteQuery): PagingSource<Int, Game>
    }
}
