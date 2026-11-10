package com.meshchat.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import com.meshchat.app.data.local.entity.MediaEntity
import com.meshchat.app.data.local.entity.MessageEntity
import com.meshchat.app.data.local.entity.PeerEntity
import com.meshchat.app.data.local.entity.TrustEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PeerDao {
    @Query("SELECT * FROM peers ORDER BY lastSeen DESC")
    fun getAllPeers(): Flow<List<PeerEntity>>

    @Query("SELECT * FROM peers WHERE securityStatus = 'AES-GCM ENCRYPTED' ORDER BY lastSeen DESC")
    fun getEncryptedPeers(): Flow<List<PeerEntity>>

    @Query("SELECT * FROM peers WHERE id = :peerId LIMIT 1")
    suspend fun getPeerById(peerId: String): PeerEntity?

    @Query("SELECT * FROM peers WHERE id = :peerId LIMIT 1")
    fun getPeerFlowById(peerId: String): Flow<PeerEntity?>

    // @Upsert (not REPLACE) so updating a peer never cascades into deleting its messages
    @Upsert
    suspend fun upsertPeer(peer: PeerEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPeerIfAbsent(peer: PeerEntity)

    @Update
    suspend fun updatePeer(peer: PeerEntity)

    /** Refreshes the advertised profile of a peer without touching its security state. */
    @Query(
        "UPDATE peers SET displayName = :displayName, deviceModel = :deviceModel, " +
            "initials = :initials, lastSeen = :lastSeen WHERE id = :peerId"
    )
    suspend fun updateProfile(
        peerId: String,
        displayName: String,
        deviceModel: String,
        initials: String,
        lastSeen: Long
    )

    @Query("UPDATE peers SET securityStatus = :status WHERE id = :peerId")
    suspend fun setSecurityStatus(peerId: String, status: String)

    /** Used when the whole trust store is purged: every conversation falls back to plain text. */
    @Query("UPDATE peers SET securityStatus = 'UNVERIFIED'")
    suspend fun resetAllSecurityStatus()

    @Query("UPDATE peers SET lastSeen = :timestamp WHERE id = :peerId")
    suspend fun updateLastSeen(peerId: String, timestamp: Long)

    @Query("DELETE FROM peers WHERE id = :peerId")
    suspend fun deletePeer(peerId: String)
}

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE peerId = :peerId ORDER BY timestamp ASC, id ASC")
    fun getMessagesForPeer(peerId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages ORDER BY timestamp DESC LIMIT :limit")
    fun getRecentMessages(limit: Int): Flow<List<MessageEntity>>

    @Query("SELECT COUNT(*) FROM messages WHERE peerId = :peerId AND type != 'SYSTEM'")
    suspend fun countConversationMessages(peerId: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: MessageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMedia(media: MediaEntity)

    @Query("SELECT * FROM media_attachments WHERE messageId = :messageId LIMIT 1")
    suspend fun getMediaForMessage(messageId: String): MediaEntity?

    @Transaction
    suspend fun insertMessageWithMedia(message: MessageEntity, media: MediaEntity?) {
        insertMessage(message)
        media?.let { insertMedia(it) }
    }

    @Query("DELETE FROM messages WHERE peerId = :peerId")
    suspend fun clearHistoryForPeer(peerId: String)
}

@Dao
interface TrustDao {
    @Query("SELECT * FROM trust_store WHERE peerId = :peerId LIMIT 1")
    suspend fun getTrustForPeer(peerId: String): TrustEntity?

    @Query("SELECT * FROM trust_store")
    fun getAllTrustRecords(): Flow<List<TrustEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveTrustRecord(trust: TrustEntity)

    @Query("DELETE FROM trust_store WHERE peerId = :peerId")
    suspend fun revokeTrust(peerId: String)

    @Query("DELETE FROM trust_store")
    suspend fun purgeAllTrust()
}
