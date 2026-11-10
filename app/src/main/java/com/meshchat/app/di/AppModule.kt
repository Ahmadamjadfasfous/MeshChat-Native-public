package com.meshchat.app.di

import android.content.Context
import androidx.room.Room
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.meshchat.app.data.local.dao.MessageDao
import com.meshchat.app.data.local.dao.PeerDao
import com.meshchat.app.data.local.dao.TrustDao
import com.meshchat.app.data.local.database.AppDatabase
import com.meshchat.app.data.preferences.PreferenceManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            AppDatabase.DATABASE_NAME
        )
            .fallbackToDestructiveMigration()
            .build()
    }

    @Provides
    @Singleton
    fun providePeerDao(db: AppDatabase): PeerDao = db.peerDao()

    @Provides
    @Singleton
    fun provideMessageDao(db: AppDatabase): MessageDao = db.messageDao()

    @Provides
    @Singleton
    fun provideTrustDao(db: AppDatabase): TrustDao = db.trustDao()

    @Provides
    @Singleton
    fun provideConnectionsClient(@ApplicationContext context: Context): ConnectionsClient {
        return Nearby.getConnectionsClient(context)
    }
}
