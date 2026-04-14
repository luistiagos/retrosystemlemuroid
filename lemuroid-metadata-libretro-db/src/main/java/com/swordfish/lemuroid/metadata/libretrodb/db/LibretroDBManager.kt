package com.swordfish.lemuroid.metadata.libretrodb.db

import android.content.Context
import androidx.room.Room
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import timber.log.Timber

class LibretroDBManager(private val context: Context) {
    companion object {
        private const val DB_NAME = "libretro-db"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val dbInstance: LibretroDatabase by lazy {
        Room.databaseBuilder(context, LibretroDatabase::class.java, DB_NAME)
            .createFromAsset("libretro-db.sqlite")
            .fallbackToDestructiveMigration()
            .build()
    }

    init {
        // Pre-warm: Room copies libretro-db.sqlite from assets on the first open.
        // Trigger this in background at DI-graph construction time so the first library
        // scan does not have to wait for the copy on slow TV-box flash storage.
        scope.launch {
            val start = System.currentTimeMillis()
            runCatching { dbInstance }
                .onSuccess {
                    Timber.d("LibretroDBManager pre-warm completed in ${System.currentTimeMillis() - start}ms")
                }
                .onFailure {
                    Timber.e(it, "LibretroDBManager pre-warm failed")
                }
        }
    }

    fun close() {
        scope.cancel()
    }
}
