package com.meshchat.app.data.model

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.util.UUID

/** Advertised identity of a node: the first two fields of the three-field username template. */
data class NodeProfile(
    val displayName: String,
    val deviceModel: String
)

/**
 * Key-exchange material carried by KEY_EXCHANGE_REQUEST / KEY_EXCHANGE_RESPONSE packets.
 *
 * @property ephemeralPublicKey X.509 encoded ECDH public key (secp256r1), used once.
 * @property identityPublicKey  X.509 encoded long-term ECDSA key of the sender (pinned on first use).
 * @property signature          ECDSA signature of the sender's identity key over the exchange context.
 */
class HandshakeMeta(
    val ephemeralPublicKey: ByteArray,
    val identityPublicKey: ByteArray,
    val signature: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HandshakeMeta) return false
        return ephemeralPublicKey.contentEquals(other.ephemeralPublicKey) &&
            identityPublicKey.contentEquals(other.identityPublicKey) &&
            signature.contentEquals(other.signature)
    }

    override fun hashCode(): Int {
        var result = ephemeralPublicKey.contentHashCode()
        result = 31 * result + identityPublicKey.contentHashCode()
        result = 31 * result + signature.contentHashCode()
        return result
    }
}

/**
 * Protocol data unit of MeshChat.
 *
 * Layout of a packet:
 *  - routing header : source, last relay, next hop, final destination, hop counter / TTL
 *  - anti-replay    : timestamp + random nonce
 *  - body           : [payload] (plain text OR AES-GCM ciphertext, flagged by [isEncrypted]),
 *                     optional key-exchange material, optional node profile (route announcements)
 *
 * Relays only ever rewrite the routing header (see [forwardedVia]); the body is passed on as the
 * very same bytes, so an intermediate hop can neither read nor alter an encrypted payload.
 *
 * The wire format is a hand written, length-bounded binary encoding. Java serialization is
 * deliberately NOT used because packets are parsed from untrusted radio input.
 */
class MeshPacket(
    val packetId: String = UUID.randomUUID().toString(),
    val sourceNodeId: String,
    val intermediateRelayId: String? = null,
    val targetNodeId: String, // Direct recipient (next hop) of this transmission
    val finalDestinationId: String, // Ultimate destination or BROADCAST for a mesh-wide flood
    val type: PacketType,
    val hopCount: Int = 0,
    val maxHops: Int = DEFAULT_MAX_HOPS,
    val timestamp: Long = System.currentTimeMillis(),
    val nonce: Long, // Anti-replay nonce
    val payload: ByteArray? = null,
    val isEncrypted: Boolean = false, // true: [payload] is AES-GCM ciphertext and [initializationVector] is set
    val initializationVector: ByteArray? = null, // AES-GCM 12-byte IV
    val signature: ByteArray? = null, // ECDSA signature over [payload]
    val handshake: HandshakeMeta? = null,
    val profile: NodeProfile? = null
) {

    enum class PacketType(val wireId: Int) {
        ROUTED_MESSAGE(1),
        ROUTED_MEDIA(2),
        KEY_EXCHANGE_REQUEST(3),
        KEY_EXCHANGE_RESPONSE(4),
        ROUTE_ANNOUNCEMENT(5);

        companion object {
            fun fromWire(id: Int): PacketType? = values().firstOrNull { it.wireId == id }
        }
    }

    fun canRelay(): Boolean = hopCount < maxHops

    /** Same packet addressed to another next hop (originating node, hop counter untouched). */
    fun addressedTo(nextHopNodeId: String): MeshPacket = MeshPacket(
        packetId = packetId,
        sourceNodeId = sourceNodeId,
        intermediateRelayId = intermediateRelayId,
        targetNodeId = nextHopNodeId,
        finalDestinationId = finalDestinationId,
        type = type,
        hopCount = hopCount,
        maxHops = maxHops,
        timestamp = timestamp,
        nonce = nonce,
        payload = payload,
        isEncrypted = isEncrypted,
        initializationVector = initializationVector,
        signature = signature,
        handshake = handshake,
        profile = profile
    )

    /**
     * Header-only rewrite used by relays. The returned packet shares the exact same body
     * (payload, IV, signature, handshake, profile) with the original; nothing is decrypted.
     */
    fun forwardedVia(relayNodeId: String, nextHopNodeId: String): MeshPacket = MeshPacket(
        packetId = packetId,
        sourceNodeId = sourceNodeId,
        intermediateRelayId = relayNodeId,
        targetNodeId = nextHopNodeId,
        finalDestinationId = finalDestinationId,
        type = type,
        hopCount = hopCount + 1,
        maxHops = maxHops,
        timestamp = timestamp,
        nonce = nonce,
        payload = payload,
        isEncrypted = isEncrypted,
        initializationVector = initializationVector,
        signature = signature,
        handshake = handshake,
        profile = profile
    )

    // ---------------------------------------------------------------------------------------------
    // Wire codec
    // ---------------------------------------------------------------------------------------------

    fun toByteArray(): ByteArray {
        val bos = ByteArrayOutputStream(256 + (payload?.size ?: 0))
        DataOutputStream(bos).use { out ->
            out.writeByte(WIRE_VERSION)
            out.writeUTF(packetId)
            out.writeUTF(sourceNodeId)
            writeNullableUtf(out, intermediateRelayId)
            out.writeUTF(targetNodeId)
            out.writeUTF(finalDestinationId)
            out.writeByte(type.wireId)
            out.writeByte(hopCount)
            out.writeByte(maxHops)
            out.writeLong(timestamp)
            out.writeLong(nonce)
            out.writeBoolean(isEncrypted)
            writeBlob(out, payload)
            writeBlob(out, initializationVector)
            writeBlob(out, signature)
            if (handshake != null) {
                out.writeBoolean(true)
                writeBlob(out, handshake.ephemeralPublicKey)
                writeBlob(out, handshake.identityPublicKey)
                writeBlob(out, handshake.signature)
            } else {
                out.writeBoolean(false)
            }
            if (profile != null) {
                out.writeBoolean(true)
                out.writeUTF(profile.displayName)
                out.writeUTF(profile.deviceModel)
            } else {
                out.writeBoolean(false)
            }
        }
        return bos.toByteArray()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MeshPacket) return false
        return packetId == other.packetId &&
            sourceNodeId == other.sourceNodeId &&
            finalDestinationId == other.finalDestinationId &&
            nonce == other.nonce
    }

    override fun hashCode(): Int {
        var result = packetId.hashCode()
        result = 31 * result + sourceNodeId.hashCode()
        result = 31 * result + finalDestinationId.hashCode()
        result = 31 * result + nonce.hashCode()
        return result
    }

    companion object {
        /** Destination id of a mesh-wide flood. */
        const val BROADCAST = "*"
        const val DEFAULT_MAX_HOPS = 5
        const val MAX_BLOB_BYTES = 60 * 1024
        const val MAX_ID_CHARS = 96
        const val MAX_NAME_CHARS = 64
        private const val WIRE_VERSION = 2

        /** Parses untrusted bytes; returns null for anything malformed, oversized or truncated. */
        fun fromByteArrayOrNull(bytes: ByteArray): MeshPacket? = try {
            fromByteArray(bytes)
        } catch (e: IOException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }

        @Throws(IOException::class)
        fun fromByteArray(bytes: ByteArray): MeshPacket {
            val bis = ByteArrayInputStream(bytes)
            DataInputStream(bis).use { input ->
                val version = input.readUnsignedByte()
                if (version != WIRE_VERSION) throw IOException("Unsupported wire version $version")

                val packetId = readBoundedUtf(input, MAX_ID_CHARS)
                val sourceNodeId = readBoundedUtf(input, MAX_ID_CHARS)
                val relayId = if (input.readBoolean()) readBoundedUtf(input, MAX_ID_CHARS) else null
                val targetNodeId = readBoundedUtf(input, MAX_ID_CHARS)
                val finalDestinationId = readBoundedUtf(input, MAX_ID_CHARS)
                val type = PacketType.fromWire(input.readUnsignedByte())
                    ?: throw IOException("Unknown packet type")
                val hopCount = input.readUnsignedByte()
                val maxHops = input.readUnsignedByte()
                val timestamp = input.readLong()
                val nonce = input.readLong()
                val encrypted = input.readBoolean()
                val payload = readBlob(input, bis)
                val iv = readBlob(input, bis)
                val signature = readBlob(input, bis)

                val handshake = if (input.readBoolean()) {
                    HandshakeMeta(
                        ephemeralPublicKey = readBlob(input, bis) ?: throw IOException("Missing ephemeral key"),
                        identityPublicKey = readBlob(input, bis) ?: throw IOException("Missing identity key"),
                        signature = readBlob(input, bis) ?: throw IOException("Missing handshake signature")
                    )
                } else {
                    null
                }

                val profile = if (input.readBoolean()) {
                    NodeProfile(
                        displayName = readBoundedUtf(input, MAX_NAME_CHARS),
                        deviceModel = readBoundedUtf(input, MAX_NAME_CHARS)
                    )
                } else {
                    null
                }

                if (encrypted && (iv == null || payload == null)) {
                    throw IOException("Encrypted frame without IV or payload")
                }

                return MeshPacket(
                    packetId = packetId,
                    sourceNodeId = sourceNodeId,
                    intermediateRelayId = relayId,
                    targetNodeId = targetNodeId,
                    finalDestinationId = finalDestinationId,
                    type = type,
                    hopCount = hopCount,
                    maxHops = maxHops,
                    timestamp = timestamp,
                    nonce = nonce,
                    payload = payload,
                    isEncrypted = encrypted,
                    initializationVector = iv,
                    signature = signature,
                    handshake = handshake,
                    profile = profile
                )
            }
        }

        private fun writeNullableUtf(out: DataOutputStream, value: String?) {
            if (value == null) {
                out.writeBoolean(false)
            } else {
                out.writeBoolean(true)
                out.writeUTF(value)
            }
        }

        private fun writeBlob(out: DataOutputStream, value: ByteArray?) {
            if (value == null) {
                out.writeInt(-1)
            } else {
                require(value.size <= MAX_BLOB_BYTES) { "Blob too large: ${value.size}" }
                out.writeInt(value.size)
                out.write(value)
            }
        }

        private fun readBoundedUtf(input: DataInputStream, maxChars: Int): String {
            val value = input.readUTF()
            if (value.length > maxChars) throw IOException("String field too long")
            return value
        }

        private fun readBlob(input: DataInputStream, source: ByteArrayInputStream): ByteArray? {
            val length = input.readInt()
            if (length == -1) return null
            if (length < 0 || length > MAX_BLOB_BYTES || length > source.available()) {
                throw IOException("Invalid blob length $length")
            }
            val data = ByteArray(length)
            input.readFully(data)
            return data
        }
    }
}
