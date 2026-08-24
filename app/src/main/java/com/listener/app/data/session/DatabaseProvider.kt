package com.listener.app.data.session

import android.content.Context
import androidx.room.Room

object DatabaseProvider {
    @Volatile private var instance: SessionDatabase? = null

    fun get(context: Context): SessionDatabase = instance ?: synchronized(this) {
        instance ?: Room.databaseBuilder(
            context.applicationContext,
            SessionDatabase::class.java,
            "listener.db",
        ).build().also { instance = it }
    }
}
