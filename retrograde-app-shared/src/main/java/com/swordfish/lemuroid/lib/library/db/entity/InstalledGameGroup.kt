package com.swordfish.lemuroid.lib.library.db.entity

import androidx.room.Embedded

/**
 * Projection for the "Instalados" tab in Lemuroid.
 * Represents a game group (by systemId and title) that has one or more installed versions
 * in the device (either downloaded catalog ROMs or locally scanned files).
 *
 * [game] is the representative game chosen for this group.
 * [installedVariantsCount] is the number of distinct installed variants.
 * [lastActionAt] is the maximum timestamp of user interaction (lastPlayedAt or downloadedAt).
 */
data class InstalledGameGroup(
    @Embedded val game: Game,
    val installedVariantsCount: Int,
    val lastActionAt: Long,
)
