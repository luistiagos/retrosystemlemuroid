package com.swordfish.lemuroid.lib.library.catalog

import com.swordfish.lemuroid.lib.library.db.entity.Game

/**
 * Plans how to bring `games` rows living under a managed ROMs root of ANOTHER volume back to
 * the current one. Pure (no Android/DB types beyond [Game]) so it is unit-testable; executed by
 * [ManifestQuickLoader].
 *
 * Rules:
 *  - a row whose file is NOT on disk (placeholder, or volume gone) is re-pointed to the current
 *    root — it is just a catalog entry;
 *  - a row whose file IS on disk stays where it is: moving GBs of downloads at startup is not
 *    an option, and `resolveDestFile` follows the row's `fileUri` anyway;
 *  - when the re-pointed URI already exists (duplicate inserted by a full manifest pass after a
 *    volume change) only one row survives: the one with a file, else the current-root one.
 *    The loser's favourite/last-played state is merged into the survivor.
 */
object RomsRootRealigner {
    data class Plan(
        /** Rows to re-point: (id, new fileUri). */
        val repoint: List<Pair<Int, String>>,
        /** Survivors whose favourite/last-played changed after a merge — written by id. */
        val update: List<Game>,
        val delete: List<Game>,
    ) {
        val isEmpty: Boolean get() = repoint.isEmpty() && update.isEmpty() && delete.isEmpty()
    }

    /**
     * @param foreignRows rows under [foreignRoots].
     * @param currentRows rows already under [currentRoot] (only those can collide).
     * @param isOnDisk true when the file behind a `fileUri` exists with content.
     */
    fun plan(
        foreignRows: List<Game>,
        foreignRoots: List<String>,
        currentRows: List<Game>,
        currentRoot: String,
        isOnDisk: (String) -> Boolean,
    ): Plan {
        val byUri = currentRows.associateByTo(HashMap()) { it.fileUri }
        val repoint = mutableListOf<Pair<Int, String>>()
        val update = LinkedHashMap<Int, Game>()
        val delete = mutableListOf<Game>()

        for (row in foreignRows) {
            val root = foreignRoots.firstOrNull { row.fileUri.startsWith("$it/") } ?: continue
            val newUri = currentRoot + row.fileUri.substring(root.length)
            val rowOnDisk = isOnDisk(row.fileUri)
            val other = byUri[newUri]

            when {
                other == null && rowOnDisk -> Unit // download on the old volume: stays there
                other == null -> {
                    repoint += row.id to newUri
                    byUri[newUri] = row.copy(fileUri = newUri)
                }
                rowOnDisk && isOnDisk(newUri) -> Unit // downloaded on both volumes: leave both
                rowOnDisk -> {
                    val otherState = update.remove(other.id) ?: other
                    update[row.id] = merge(update[row.id] ?: row, otherState)
                    delete += other
                    byUri.remove(newUri)
                }
                else -> {
                    val survivor = merge(update[other.id] ?: other, row)
                    update[other.id] = survivor
                    byUri[newUri] = survivor
                    delete += row
                }
            }
        }

        val deletedIds = delete.mapTo(HashSet()) { it.id }
        return Plan(
            repoint = repoint.filter { it.first !in deletedIds },
            update = update.values.filter { it.id !in deletedIds },
            delete = delete,
        )
    }

    private fun merge(
        survivor: Game,
        loser: Game,
    ): Game {
        val lastPlayed = listOfNotNull(survivor.lastPlayedAt, loser.lastPlayedAt).maxOrNull()
        return survivor.copy(
            isFavorite = survivor.isFavorite || loser.isFavorite,
            lastPlayedAt = lastPlayed,
        )
    }
}
