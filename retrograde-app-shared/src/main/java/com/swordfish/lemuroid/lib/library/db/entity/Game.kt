/*
 * Game.kt
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

package com.swordfish.lemuroid.lib.library.db.entity

import androidx.recyclerview.widget.DiffUtil
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.io.Serializable

@Entity(
    tableName = "games",
    indices = [
        Index("id", unique = true),
        Index("fileUri", unique = true),
        Index("fileName"),
        Index("title"),
        Index("systemId"),
        Index("lastIndexedAt"),
        Index("lastPlayedAt"),
        Index("isFavorite"),
        Index("popularityIndex"),
        // Composite: recents query — WHERE isFavorite = 0 ORDER BY lastPlayedAt DESC
        Index(value = ["isFavorite", "lastPlayedAt"], name = "index_games_isFavorite_lastPlayedAt"),
        // Composite: per-system catalog sorted by popularity — WHERE systemId = ? ORDER BY popularityIndex DESC
        Index(value = ["systemId", "popularityIndex"], name = "index_games_systemId_popularityIndex"),
        // Composite: favorites sorted by title — WHERE isFavorite = 1 ORDER BY title ASC
        Index(value = ["isFavorite", "title"], name = "index_games_isFavorite_title"),
        // Composite: grouped catalog (one rep per title) — WHERE systemId = ? AND isRepresentative = 1
        Index(
            value = ["systemId", "isRepresentative", "popularityIndex"],
            name = "index_games_systemId_isRepresentative_popularityIndex",
        ),
    ],
)
data class Game(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val fileName: String,
    val fileUri: String,
    val title: String,
    val systemId: String,
    val developer: String?,
    val coverFrontUrl: String?,
    val lastIndexedAt: Long,
    val lastPlayedAt: Long? = null,
    val isFavorite: Boolean = false,
    val popularityIndex: Int = 0,
    // Pre-computed flag from catalog_manifest.txt (5th field).
    // True for the chosen representative of each (systemId, title) group; false for variants.
    // Manually-imported ROMs (not in the manifest) default to true so they appear individually.
    val isRepresentative: Boolean = true,
) : Serializable {
    companion object {
        val DIFF_CALLBACK =
            object : DiffUtil.ItemCallback<Game>() {
                override fun areItemsTheSame(
                    oldItem: Game,
                    newItem: Game,
                ): Boolean {
                    return oldItem.id == newItem.id
                }

                override fun areContentsTheSame(
                    oldItem: Game,
                    newItem: Game,
                ): Boolean {
                    return oldItem == newItem
                }
            }
    }
}
