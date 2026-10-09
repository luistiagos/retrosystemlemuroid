package com.swordfish.lemuroid.app.mobile.feature.installed

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import com.swordfish.lemuroid.lib.library.db.entity.Game
import com.swordfish.lemuroid.lib.library.db.entity.InstalledGameGroup
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.io.File

data class InstalledGameGroupUiModel(
    val group: InstalledGameGroup,
    val isAvailable: Boolean = true,
)

class StorageAvailabilityMonitor(private val context: Context) {

    val mediaStateChanges: Flow<Long> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                trySend(System.currentTimeMillis())
            }
        }

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_MEDIA_MOUNTED)
            addAction(Intent.ACTION_MEDIA_UNMOUNTED)
            addAction(Intent.ACTION_MEDIA_EJECT)
            addAction(Intent.ACTION_MEDIA_REMOVED)
            addAction(Intent.ACTION_MEDIA_BAD_REMOVAL)
            addDataScheme("file")
        }

        context.registerReceiver(receiver, filter)
        // Initial emission
        trySend(System.currentTimeMillis())

        awaitClose {
            runCatching { context.unregisterReceiver(receiver) }
        }
    }

    fun isGameAvailable(game: Game): Boolean {
        val uri = Uri.parse(game.fileUri)
        return when (uri.scheme) {
            "file" -> {
                val path = uri.path ?: return false
                val file = File(path)
                file.exists() && file.length() > 0
            }
            "content" -> {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { true } ?: false
                }.getOrDefault(false)
            }
            else -> {
                val file = File(game.fileUri)
                file.exists() && file.length() > 0
            }
        }
    }
}
