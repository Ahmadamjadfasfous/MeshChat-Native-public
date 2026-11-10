package com.meshchat.app.data.local.database

import androidx.room.Database
import androidx.room.RoomDatabase
import com.meshchat.app.data.local.dao.MessageDao
import com.meshchat.app.data.local.dao.PeerDao
import com.meshchat.app.data.local.dao.TrustDao
import com.meshchat.app.data.local.entity.MediaEntity
import com.meshchat.app.data.local.entity.MessageEntity
import com.meshchat.app.data.local.entity.PeerEntity
import com.meshchat.app.data.local.entity.TrustEntity

@Database(
    entities = [
        PeerEntity::class,
        MessageEntity::class,
        MediaEntity::class,
        TrustEntity::class
    ],
    version = 2,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun peerDao(): PeerDao
    abstract fun messageDao(): MessageDao
    abstract fun trustDao(): TrustDao

    companion object {
        const val DATABASE_NAME = "meshchat_secure_db.db"
    }
}
