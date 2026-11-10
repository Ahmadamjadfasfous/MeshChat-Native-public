package com.meshchat.app.domain.mesh

import android.util.Log
import com.meshchat.app.data.local.dao.MessageDao
import com.meshchat.app.data.local.dao.PeerDao
import com.meshchat.app.data.local.entity.MessageEntity
import com.meshchat.app.data.local.entity.MessageTypes
import com.meshchat.app.data.local.entity.SecurityStatus
import com.meshchat.app.data.model.MeshPacket
import com.meshchat.app.domain.security.CryptoEngine
import com.meshchat.app.domain.security.HandshakeKeys
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runs the asymmetric key exchange that upgrades a plain-text conversation to AES-GCM.
 *
 *   Peer A (clicked the banner)                          Peer B (automatic, no UI)
 *   ------------------------------                       -------------------------
 *   create Keystore ECDH key pair
 *   KEY_EXCHANGE_REQUEST  (ephemeral pub + signed)  --->  verify signature, create own ECDH pair,
 *                                                        derive AES-GCM key (HKDF), pin identity,
 *                                                        status = "AES-GCM ENCRYPTED"
 *   derive AES-GCM key, pin identity,                <---  KEY_EXCHANGE_RESPONSE (ephemeral pub + signed)
 *   status = "AES-GCM ENCRYPTED"
 *
 * The packets are ordinary [MeshPacket]s, so they cross relays like any other packet.
 */
@Singleton
class HandshakeManager @Inject constructor(
    private val cryptoEngine: CryptoEngine,
    private val peerDao: PeerDao,
    private val messageDao: MessageDao
) {
    enum class FailReason { TIMEOUT, CRYPTO_ERROR, PEER_UNREACHABLE }

    /** Per-peer progress shown by the banner of the initiating side. Absent key = idle. */
    sealed class Phase {
        object Negotiating : Phase()
        data class Failed(val reason: FailReason) : Phase()
    }

    private class Pending(val keys: HandshakeKeys)

    companion object {
        private const val TAG = "HandshakeManager"
        private const val RESPONSE_TIMEOUT_MS = 20_000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val pending = ConcurrentHashMap<String, Pending>()

    private val _phases = MutableStateFlow<Map<String, Phase>>(emptyMap())
    val phases: StateFlow<Map<String, Phase>> = _phases.asStateFlow()

    @Volatile
    private var localNodeId: String = ""

    @Volatile
    private var transmit: ((MeshPacket) -> Boolean)? = null

    /** Wired by the router once the local node id and the transport are known. */
    fun configure(localNodeId: String, transmit: (MeshPacket) -> Boolean) {
        this.localNodeId = localNodeId
        this.transmit = transmit
    }

    // ---------------------------------------------------------------------------------------------
    // Peer A: clicked "Activate Asymmetric ECDH Encryption"
    // ---------------------------------------------------------------------------------------------

    fun initiate(peerId: String) {
        scope.launch {
            val send = transmit
            if (send == null || localNodeId.isEmpty()) {
                setPhase(peerId, Phase.Failed(FailReason.PEER_UNREACHABLE))
                return@launch
            }
            val keys = try {
                cryptoEngine.createHandshakeKeys()
            } catch (e: Exception) {
                Log.e(TAG, "Could not create handshake keys", e)
                setPhase(peerId, Phase.Failed(FailReason.CRYPTO_ERROR))
                return@launch
            }
            val entry = Pending(keys)
            if (pending.putIfAbsent(peerId, entry) != null) {
                cryptoEngine.discardHandshakeKeys(keys) // a handshake with this peer is already running
                return@launch
            }
            setPhase(peerId, Phase.Negotiating)

            val sent = try {
                val meta = cryptoEngine.buildHandshakeMeta(
                    CryptoEngine.ROLE_REQUEST, localNodeId, peerId, keys.publicKeyBytes
                )
                send(
                    MeshPacket(
                        sourceNodeId = localNodeId,
                        targetNodeId = peerId,
                        finalDestinationId = peerId,
                        type = MeshPacket.PacketType.KEY_EXCHANGE_REQUEST,
                        nonce = cryptoEngine.generateNonce(),
                        handshake = meta
                    )
                )
            } catch (e: Exception) {
                Log.e(TAG, "Key exchange request failed", e)
                abort(peerId, entry, FailReason.CRYPTO_ERROR)
                return@launch
            }
            if (!sent) {
                abort(peerId, entry, FailReason.PEER_UNREACHABLE)
                return@launch
            }

            delay(RESPONSE_TIMEOUT_MS)
            if (pending[peerId] === entry) abort(peerId, entry, FailReason.TIMEOUT)
        }
    }

    /** Clears a failure state (e.g. when the user dismisses the banner). */
    fun dismiss(peerId: String) {
        if (!pending.containsKey(peerId)) clearPhase(peerId)
    }

    // ---------------------------------------------------------------------------------------------
    // Peer B: incoming request (fully automatic)
    // ---------------------------------------------------------------------------------------------

    suspend fun onRequest(packet: MeshPacket) {
        val peerId = packet.sourceNodeId
        val meta = packet.handshake ?: return
        if (packet.finalDestinationId != localNodeId) return

        // Only accept a key exchange inside a conversation that already exists (plain text first)
        if (messageDao.countConversationMessages(peerId) == 0) {
            Log.w(TAG, "Ignored unsolicited key exchange from $peerId")
            return
        }
        if (!cryptoEngine.verifyHandshakeMeta(CryptoEngine.ROLE_REQUEST, meta, peerId, localNodeId)) {
            Log.w(TAG, "Ignored key exchange with invalid signature from $peerId")
            return
        }

        val mine = pending[peerId]
        if (mine != null) {
            // Both sides clicked at the same moment: the request of the lower node id wins
            if (localNodeId < peerId) return
            if (pending.remove(peerId, mine)) cryptoEngine.discardHandshakeKeys(mine.keys)
            clearPhase(peerId)
        }

        val keys = try {
            cryptoEngine.createHandshakeKeys()
        } catch (e: Exception) {
            Log.e(TAG, "Could not create handshake keys", e)
            return
        }
        try {
            val sessionKey = cryptoEngine.deriveSessionKey(keys, meta.ephemeralPublicKey, localNodeId, peerId)
            val identityChanged = identityKeyChanged(peerId, meta.identityPublicKey)
            transmit?.invoke(
                MeshPacket(
                    sourceNodeId = localNodeId,
                    targetNodeId = peerId,
                    finalDestinationId = peerId,
                    type = MeshPacket.PacketType.KEY_EXCHANGE_RESPONSE,
                    nonce = cryptoEngine.generateNonce(),
                    handshake = cryptoEngine.buildHandshakeMeta(
                        CryptoEngine.ROLE_RESPONSE, localNodeId, peerId, keys.publicKeyBytes
                    )
                )
            )
            commit(peerId, meta.identityPublicKey, sessionKey, identityChanged)
        } catch (e: Exception) {
            Log.e(TAG, "Responding to key exchange failed", e)
        } finally {
            cryptoEngine.discardHandshakeKeys(keys)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Peer A: response arrived
    // ---------------------------------------------------------------------------------------------

    suspend fun onResponse(packet: MeshPacket) {
        val peerId = packet.sourceNodeId
        val meta = packet.handshake ?: return
        if (packet.finalDestinationId != localNodeId) return

        val entry = pending[peerId] ?: return // we never asked: ignore
        if (!cryptoEngine.verifyHandshakeMeta(CryptoEngine.ROLE_RESPONSE, meta, peerId, localNodeId)) {
            Log.w(TAG, "Ignored key exchange response with invalid signature from $peerId")
            return
        }
        if (!pending.remove(peerId, entry)) return // completed or aborted meanwhile

        try {
            val sessionKey = cryptoEngine.deriveSessionKey(
                entry.keys, meta.ephemeralPublicKey, localNodeId, peerId
            )
            val identityChanged = identityKeyChanged(peerId, meta.identityPublicKey)
            commit(peerId, meta.identityPublicKey, sessionKey, identityChanged)
            clearPhase(peerId)
        } catch (e: Exception) {
            Log.e(TAG, "Completing key exchange failed", e)
            setPhase(peerId, Phase.Failed(FailReason.CRYPTO_ERROR))
        } finally {
            cryptoEngine.discardHandshakeKeys(entry.keys)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Shared
    // ---------------------------------------------------------------------------------------------

    /** Drops the trust record of a peer and returns its conversation to plain text / unverified. */
    suspend fun resetPeerSecurity(peerId: String, notice: String? = null) {
        cryptoEngine.revokePeerTrust(peerId)
        peerDao.setSecurityStatus(peerId, SecurityStatus.UNVERIFIED)
        clearPhase(peerId)
        if (notice != null) postSystemNotice(peerId, notice)
    }

    private suspend fun identityKeyChanged(peerId: String, identityKey: ByteArray): Boolean {
        val pinned = cryptoEngine.getPinnedIdentityKey(peerId) ?: return false
        return !pinned.contentEquals(identityKey)
    }

    private suspend fun commit(
        peerId: String,
        identityKey: ByteArray,
        sessionKey: javax.crypto.SecretKey,
        identityChanged: Boolean
    ) {
        cryptoEngine.savePeerTrust(peerId, identityKey, sessionKey)
        peerDao.setSecurityStatus(peerId, SecurityStatus.ENCRYPTED)
        postSystemNotice(
            peerId,
            "Secure session established. ECDH key exchange complete - all further messages are AES-GCM encrypted."
        )
        if (identityChanged) {
            postSystemNotice(peerId, "Warning: this peer's identity key changed since the last secure session.")
        }
    }

    private suspend fun postSystemNotice(peerId: String, text: String) {
        if (peerDao.getPeerById(peerId) == null) return // foreign key: the peer row must exist
        messageDao.insertMessage(
            MessageEntity(
                id = UUID.randomUUID().toString(),
                peerId = peerId,
                text = text,
                timestamp = System.currentTimeMillis(),
                isFromMe = false,
                type = MessageTypes.SYSTEM
            )
        )
    }

    private fun abort(peerId: String, entry: Pending, reason: FailReason) {
        if (pending.remove(peerId, entry)) cryptoEngine.discardHandshakeKeys(entry.keys)
        setPhase(peerId, Phase.Failed(reason))
    }

    private fun setPhase(peerId: String, phase: Phase) {
        _phases.update { it + (peerId to phase) }
    }

    private fun clearPhase(peerId: String) {
        _phases.update { it - peerId }
    }
}
