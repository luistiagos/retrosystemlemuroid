package com.swordfish.lemuroid.metadata.libretrodb.db

import android.content.Context
import androidx.room.Room
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

class LibretroDBManager(private val context: Context) {
    companion object {
        private const val DB_NAME = "libretro-db"
    }

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
        @OptIn(DelicateCoroutinesApi::class)
        GlobalScope.launch(Dispatchers.IO) {
            runCatching { dbInstance }
        }
    }
}
