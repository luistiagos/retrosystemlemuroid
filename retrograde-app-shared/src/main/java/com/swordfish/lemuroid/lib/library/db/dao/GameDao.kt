/*
 * GameDao.kt
 *
 * Copyright (C) 2017 Retrograde Project
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.swordfish.lemuroid.lib.library.db.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.swordfish.lemuroid.lib.library.db.entity.Game
import kotlinx.coroutines.flow.Flow

@Dao
interface GameDao {
    @Query("SELECT * FROM games WHERE id = :id")
    suspend fun selectById(id: Int): Game?

    @Query("SELECT * FROM games WHERE fileUri = :fileUri")
    suspend fun selectByFileUri(fileUri: String): Game?

    @Query("SELECT * FROM games WHERE lastIndexedAt < :lastIndexedAt")
    suspend fun selectByLastIndexedAtLessThan(lastIndexedAt: Long): List<Game>

    @Query("DELETE FROM games WHERE lastIndexedAt < :lastIndexedAt AND fileName NOT IN (SELECT fileName FROM downloaded_roms)")
    suspend fun deleteByLastIndexedAtLessThan(lastIndexedAt: Long)

    @Query("SELECT * FROM games WHERE isFavorite = 1 ORDER BY title ASC")
    fun selectFavorites(): PagingSource<Int, Game>

    @Query(
        """
        SELECT * FROM games WHERE lastPlayedAt IS NOT NULL AND isFavorite = 0 ORDER BY lastPlayedAt DESC LIMIT :limit
        """,
    )
    fun selectFirstUnfavoriteRecents(limit: Int): Flow<List<Game>>

    @Query("SELECT * FROM games WHERE isFavorite = 1 ORDER BY lastPlayedAt DESC LIMIT :limit")
    fun selectFirstFavoritesRecents(limit: Int): Flow<List<Game>>

    @Query("SELECT * FROM games WHERE lastPlayedAt IS NOT NULL ORDER BY lastPlayedAt DESC LIMIT :limit")
    suspend fun asyncSelectFirstRecents(limit: Int): List<Game>

    @Query("SELECT * FROM games WHERE isFavorite = 1 ORDER BY lastPlayedAt DESC LIMIT :limit")
    fun selectFirstFavorites(limit: Int): Flow<List<Game>>

    @Query("SELECT * FROM games WHERE lastPlayedAt IS NULL LIMIT :limit")
    fun selectFirstNotPlayed(limit: Int): Flow<List<Game>>

    @Query("SELECT * FROM games WHERE lastPlayedAt IS NULL AND systemId NOT IN (:excludedSystemIds) LIMIT :limit")
    fun selectFirstNotPlayedExcluding(limit: Int, excludedSystemIds: Set<String>): Flow<List<Game>>

    @Query(
        """
        SELECT * FROM games WHERE lastPlayedAt IS NOT NULL AND isFavorite = 0 AND systemId NOT IN (:excludedSystemIds)
        ORDER BY lastPlayedAt DESC LIMIT :limit
        """,
    )
    fun selectFirstUnfavoriteRecentsExcluding(limit: Int, excludedSystemIds: Set<String>): Flow<List<Game>>

    @Query("SELECT * FROM games WHERE isFavorite = 1 AND systemId NOT IN (:excludedSystemIds) ORDER BY lastPlayedAt DESC LIMIT :limit")
    fun selectFirstFavoritesExcluding(limit: Int, excludedSystemIds: Set<String>): Flow<List<Game>>

    @Query("""
        SELECT games.* FROM games
        LEFT JOIN downloaded_roms ON games.fileName = downloaded_roms.fileName
        WHERE games.systemId = :systemId
        ORDER BY (downloaded_roms.fileName IS NOT NULL) DESC, games.title ASC, games.id DESC
    """)
    fun selectBySystem(systemId: String): PagingSource<Int, Game>

    @Query("""
        SELECT games.* FROM games
        LEFT JOIN downloaded_roms ON games.fileName = downloaded_roms.fileName
        WHERE games.systemId IN (:systemIds)
        ORDER BY (downloaded_roms.fileName IS NOT NULL) DESC, games.title ASC, games.id DESC
    """)
    fun selectBySystems(systemIds: List<String>): PagingSource<Int, Game>

    @Query("""
        SELECT games.* FROM games
        LEFT JOIN downloaded_roms ON games.fileName = downloaded_roms.fileName
        WHERE games.systemId = :systemId
        ORDER BY (downloaded_roms.fileName IS NOT NULL) DESC, games.popularityIndex DESC, games.title ASC
    """)
    fun selectBySystemSortedByPopularity(systemId: String): PagingSource<Int, Game>

    @Query("""
        SELECT games.* FROM games
        LEFT JOIN downloaded_roms ON games.fileName = downloaded_roms.fileName
        WHERE games.systemId IN (:systemIds)
        ORDER BY (downloaded_roms.fileName IS NOT NULL) DESC, games.popularityIndex DESC, games.title ASC
    """)
    fun selectBySystemsSortedByPopularity(systemIds: List<String>): PagingSource<Int, Game>

    // ── Grouped queries (one representative per title) ────────────────────────
    // The representative is pre-computed in catalog_manifest.txt (5th field) and persisted
    // as Game.isRepresentative. Filter is now a trivial WHERE clause; the
    // (systemId, isRepresentative, popularityIndex) composite index makes these queries
    // O(log n) instead of the previous correlated-subquery scan.

    @Query("""
        SELECT g.* FROM games g
        LEFT JOIN downloaded_roms dr ON g.fileName = dr.fileName
        WHERE g.systemId = :systemId AND g.isRepresentative = 1
        ORDER BY (dr.fileName IS NOT NULL) DESC, g.popularityIndex DESC, g.title ASC
    """)
    fun selectGroupedBySystemSortedByPopularity(systemId: String): PagingSource<Int, Game>

    @Query("""
        SELECT g.* FROM games g
        LEFT JOIN downloaded_roms dr ON g.fileName = dr.fileName
        WHERE g.systemId IN (:systemIds) AND g.isRepresentative = 1
        ORDER BY (dr.fileName IS NOT NULL) DESC, g.popularityIndex DESC, g.title ASC
    """)
    fun selectGroupedBySystemsSortedByPopularity(systemIds: List<String>): PagingSource<Int, Game>

    @Query("""
        SELECT g.* FROM games g
        LEFT JOIN downloaded_roms dr ON g.fileName = dr.fileName
        WHERE g.systemId = :systemId AND g.isRepresentative = 1
        ORDER BY (dr.fileName IS NOT NULL) DESC, g.title ASC
    """)
    fun selectGroupedBySystem(systemId: String): PagingSource<Int, Game>

    @Query("""
        SELECT g.* FROM games g
        LEFT JOIN downloaded_roms dr ON g.fileName = dr.fileName
        WHERE g.systemId IN (:systemIds) AND g.isRepresentative = 1
        ORDER BY (dr.fileName IS NOT NULL) DESC, g.title ASC
    """)
    fun selectGroupedBySystems(systemIds: List<String>): PagingSource<Int, Game>

    // All variants (same title + systemId) — used by GameVariantsModal
    @Query("SELECT * FROM games WHERE systemId = :systemId AND title = :title ORDER BY fileName ASC")
    fun selectVariantsByTitle(systemId: String, title: String): Flow<List<Game>>

    // Composite keys "systemId/title" for all titles that have more than one ROM variant.
    // Used to decide whether tapping a game should open the variants modal.
    @Query("""
        SELECT systemId || '/' || title
        FROM games
        GROUP BY systemId, title
        HAVING COUNT(*) > 1
    """)
    fun selectAllCompositeKeysWithVariants(): Flow<List<String>>

    @Query("""
        SELECT * FROM games
        WHERE coverFrontUrl IS NOT NULL AND popularityIndex > 0
        AND systemId NOT IN (:excludedSystemIds)
        ORDER BY popularityIndex DESC
        LIMIT :limit
    """)
    fun selectTopPopularWithCoversExcluding(limit: Int, excludedSystemIds: Set<String>): Flow<List<Game>>

    @Query("""
        SELECT * FROM games
        WHERE coverFrontUrl IS NOT NULL AND popularityIndex > 0
        ORDER BY popularityIndex DESC
        LIMIT :limit
    """)
    fun selectTopPopularWithCovers(limit: Int): Flow<List<Game>>

    /**
     * Refreshes the catalog-derived fields for an already-existing row. Called by
     * [ManifestQuickLoader] after an app update so that changes to `popularityIndex`
     * or `isRepresentative` in `catalog_manifest.txt` are picked up without re-inserting.
     */
    @Query("""
        UPDATE games SET popularityIndex = :popularityIndex, isRepresentative = :isRepresentative
        WHERE fileUri = :fileUri
    """)
    suspend fun updateManifestFields(fileUri: String, popularityIndex: Int, isRepresentative: Boolean)

    @Query("SELECT * FROM games ORDER BY title ASC")
    suspend fun selectAll(): List<Game>

    @Query("SELECT DISTINCT systemId FROM games ORDER BY systemId ASC")
    suspend fun selectSystems(): List<String>

    @Query("SELECT count(*) count, systemId systemId FROM games GROUP BY systemId")
    fun selectSystemsWithCount(): Flow<List<SystemCount>>

    @Insert
    suspend fun insert(games: List<Game>): List<Long>

    /**
     * Inserts only games whose [Game.fileUri] is not already present in the DB.
     * Used by [ManifestQuickLoader] so the fast manifest-based load does NOT overwrite
     * games that were previously enriched by the LibretroDB scan (manual import path).
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfNotExists(games: List<Game>): List<Long>

    @Delete
    suspend fun delete(games: List<Game>)

    @Update
    suspend fun update(game: Game)

    @Update
    suspend fun update(games: List<Game>)
}

data class SystemCount(val systemId: String, val count: Int)
