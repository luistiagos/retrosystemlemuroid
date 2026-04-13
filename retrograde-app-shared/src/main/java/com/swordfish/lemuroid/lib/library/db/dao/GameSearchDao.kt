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
            MIGRATION.migrate(db)
        }
    }

    object MIGRATION : Migration(7, 8) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL(
                """
                CREATE VIRTUAL TABLE fts_games USING FTS4(
                  tokenize=unicode61 "remove_diacritics=1",
                  content="games",
                  title);
                """,
            )
            database.execSQL(
                """
                CREATE TRIGGER games_bu BEFORE UPDATE ON games BEGIN
                  DELETE FROM fts_games WHERE docid=old.id;
                END;
                """,
            )
            database.execSQL(
                """
                CREATE TRIGGER games_bd BEFORE DELETE ON games BEGIN
                  DELETE FROM fts_games WHERE docid=old.id;
                END;
                """,
            )
            database.execSQL(
                """
                CREATE TRIGGER games_au AFTER UPDATE ON games BEGIN
                  INSERT INTO fts_games(docid, title) VALUES(new.id, new.title);
                END;
                """,
            )
            database.execSQL(
                """
                CREATE TRIGGER games_ai AFTER INSERT ON games BEGIN
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

    fun search(query: String): PagingSource<Int, Game> =
        internalDao.rawSearch(
            SimpleSQLiteQuery(
                """
                SELECT games.*
                    FROM fts_games
                    JOIN games ON games.id = fts_games.docid
                    WHERE fts_games MATCH ?
                """,
                arrayOf(sanitizeFtsQuery(query)),
            ),
        )

    companion object {
        /**
         * Sanitizes an FTS4 MATCH query to prevent SQLiteException on malformed input.
         * Strips characters that are illegal or cause parse errors in SQLite FTS4 MATCH expressions.
         */
        private fun sanitizeFtsQuery(query: String): String {
            // Remove characters that can cause FTS4 parse errors:
            // quotes, parentheses, asterisks (wildcard only valid at end of term),
            // hyphens/colons as operators, etc.
            return query
                .replace('"', ' ')
                .replace('\'', ' ')
                .replace('(', ' ')
                .replace(')', ' ')
                .replace(':', ' ')
                .trim()
                .ifEmpty { "\"\"" }
        }
    }

    @Dao
    interface Internal {
        @RawQuery(observedEntities = [(Game::class)])
        fun rawSearch(query: SupportSQLiteQuery): PagingSource<Int, Game>
    }
}
