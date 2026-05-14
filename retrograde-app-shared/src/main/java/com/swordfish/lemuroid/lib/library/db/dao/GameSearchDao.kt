package com.swordfish.lemuroid.lib.library.db.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.RawQuery
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteQuery
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

    fun search(query: String, systemIds: List<String>? = null): PagingSource<Int, Game> {
        val matchArg = sanitizeFtsQuery(query)
        return if (systemIds != null && systemIds.isNotEmpty()) {
            val placeholders = systemIds.joinToString(",") { "?" }
            internalDao.rawSearch(
                SimpleSQLiteQuery(
                    """
                    SELECT games.*
                        FROM fts_games
                        JOIN games ON games.id = fts_games.docid
                        WHERE fts_games MATCH ?
                        AND games.systemId IN ($placeholders)
                        LIMIT 100
                    """,
                    arrayOf(matchArg, *systemIds.toTypedArray()),
                ),
            )
        } else {
            internalDao.rawSearch(
                SimpleSQLiteQuery(
                    """
                    SELECT games.*
                        FROM fts_games
                        JOIN games ON games.id = fts_games.docid
                        WHERE fts_games MATCH ?
                        LIMIT 100
                    """,
                    arrayOf(matchArg),
                ),
            )
        }
    }

    companion object {
        /**
         * Sanitizes an FTS4 MATCH query to prevent SQLiteException on malformed input.
         * Strips characters that are illegal or cause parse errors in SQLite FTS4 MATCH expressions.
         * Appends '*' to each term to enable prefix matching (e.g. "Samurai Sho" matches "Samurai Shodown").
         */
        private fun sanitizeFtsQuery(query: String): String {
            // Remove characters that can cause FTS4 parse errors:
            // quotes, parentheses, hyphens/colons as operators, etc.
            val sanitized = query
                .replace('"', ' ')
                .replace('\'', ' ')
                .replace('(', ' ')
                .replace(')', ' ')
                .replace(':', ' ')
                .replace('*', ' ')
                .trim()
            if (sanitized.isEmpty()) return "\"\""
            // Append '*' to each word for prefix matching
            return sanitized.split("\\s+".toRegex())
                .filter { it.isNotEmpty() }
                .joinToString(" ") { "$it*" }
        }
    }

    @Dao
    interface Internal {
        @RawQuery(observedEntities = [(Game::class)])
        fun rawSearch(query: SupportSQLiteQuery): PagingSource<Int, Game>
    }
}
