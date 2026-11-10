package com.meshchat.app.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Security state of a conversation, persisted on the peer row.
 * A conversation always opens as [UNVERIFIED] (plain text); it only becomes [ENCRYPTED] after the
 * sender activated the asymmetric ECDH key exchange from the inline banner.
 */
object SecurityStatus {
    const val UNVERIFIED = "UNVERIFIED"
    const val ENCRYPTED = "AES-GCM ENCRYPTED"
}

/** Values of [MessageEntity.type]. */
object MessageTypes {
    /** AES-GCM protected text. */
    const val TEXT = "TEXT"

    /** First-contact text that travelled as raw, unencrypted plain text. */
    const val TEXT_PLAIN = "TEXT_PLAIN"

    /** Local, centred timeline notice (handshake finished, message rejected, ...). */
    const val SYSTEM = "SYSTEM"
}

/**
 * Direct or indirect (multi-hop) peer node discovered in the mesh network.
 * The three user-visible username fields are [displayName], [deviceModel] and the node [id].
 */
@Entity(tableName = "peers")
data class PeerEntity(
    @PrimaryKey
    val id: String,
    val displayName: String,
    val deviceModel: String,
    val initials: String,
    val appName: String,
    val packageName: String,
    val securityStatus: String = SecurityStatus.UNVERIFIED,
    val lastSeen: Long = System.currentTimeMillis()
) {
    val isEncrypted: Boolean get() = securityStatus == SecurityStatus.ENCRYPTED
}

/**
 * Text or multimedia message exchanged over direct or multi-hop routes.
 */
@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = PeerEntity::class,
            parentColumns = ["id"],
            childColumns = ["peerId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["peerId"]), Index(value = ["timestamp"])]
)
data class MessageEntity(
    @PrimaryKey
    val id: String,
    val peerId: String,
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isFromMe: Boolean,
    val type: String // see MessageTypes
)

/**
 * Media attachment referenced by a message.
 */
@Entity(
    tableName = "media_attachments",
    foreignKeys = [
        ForeignKey(
            entity = MessageEntity::class,
            parentColumns = ["id"],
            childColumns = ["messageId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["messageId"])]
)
data class MediaEntity(
    @PrimaryKey
    val id: String,
    val messageId: String,
    val localFilePathUri: String,
    val mimeType: String,
    val fileSize: Long,
    val duration: Long = 0L // Milliseconds for audio
)

/**
 * Cryptographic trust metadata.
 * Only the peer's identity public key and the MasterKey-encrypted symmetric session key are
 * persisted; ephemeral private keys never touch the disk.
 */
@Entity(tableName = "trust_store")
data class TrustEntity(
    @PrimaryKey
    val peerId: String,
    val publicKeyBytes: ByteArray, // peer identity key (ECDSA, X.509) pinned on first use
    val sessionKeyEncrypted: ByteArray, // Encrypted via Android Keystore MasterKey
    val trustLevel: Int = 1 // 0: untrusted, 1: ECDH verified, 2: pinned
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as TrustEntity
        if (peerId != other.peerId) return false
        if (!publicKeyBytes.contentEquals(other.publicKeyBytes)) return false
        if (!sessionKeyEncrypted.contentEquals(other.sessionKeyEncrypted)) return false
        if (trustLevel != other.trustLevel) return false
        return true
    }

    override fun hashCode(): Int {
        var result = peerId.hashCode()
        result = 31 * result + publicKeyBytes.contentHashCode()
        result = 31 * result + sessionKeyEncrypted.contentHashCode()
        result = 31 * result + trustLevel
        return result
    }
}
