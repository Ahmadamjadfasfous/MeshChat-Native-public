package com.meshchat.app.domain.mesh

import android.util.Log
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.Payload
import com.meshchat.app.data.local.dao.MessageDao
import com.meshchat.app.data.local.dao.PeerDao
import com.meshchat.app.data.local.entity.MessageEntity
import com.meshchat.app.data.local.entity.MessageTypes
import com.meshchat.app.data.local.entity.PeerEntity
import com.meshchat.app.data.local.entity.SecurityStatus
import com.meshchat.app.data.model.MeshPacket
import com.meshchat.app.data.model.NodeProfile
import com.meshchat.app.domain.security.CryptoEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Multi-hop mesh router.
 *
 * Forwarding rule: every packet that is not addressed to this node is pushed towards its final
 * destination by [relayPacket] using the [RoutingTable]. If node C is listed in the routing map via
 * node B, then B intercepts a packet for C and forwards it instantly. Only the routing header is
 * rewritten ([MeshPacket.forwardedVia]); the payload, IV, signature and key material are passed on
 * untouched and are never decrypted on the relay.
 */
@Singleton
class MeshRouter @Inject constructor(
    private val cryptoEngine: CryptoEngine,
    private val handshakeManager: HandshakeManager,
    private val peerDao: PeerDao,
    private val messageDao: MessageDao
) {
    companion object {
        private const val TAG = "MeshRouter"
        private const val MAX_HOP_LIMIT = 6
        private const val DUPLICATE_CACHE_SIZE = 1500
        private const val MAX_PLAIN_TEXT_BYTES = 8192
        private const val ANNOUNCE_INTERVAL_MS = 20_000L
        private const val ANNOUNCE_MAX_HOPS = 4
        private const val APP_NAME = "MeshChat"
        private const val APP_PACKAGE = "com.meshchat.app"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val routingTable = RoutingTable()

    /** Key-exchange progress per peer (drives the inline banner of the initiating side). */
    val handshakePhases: StateFlow<Map<String, HandshakeManager.Phase>> = handshakeManager.phases

    // LRU Cache for packet deduplication & loop prevention
    private val seenPacketIds = Collections.newSetFromMap(
        object : java.util.LinkedHashMap<String, Boolean>(DUPLICATE_CACHE_SIZE, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean {
                return size > DUPLICATE_CACHE_SIZE
            }
        }
    )

    private val _incomingMessages = MutableSharedFlow<MessageEntity>(extraBufferCapacity = 64)
    val incomingMessages: SharedFlow<MessageEntity> = _incomingMessages.asSharedFlow()

    @Volatile
    private var connectionsClient: ConnectionsClient? = null

    @Volatile
    var localNodeId: String = "local_node"
        private set

    @Volatile
    private var localProfile: NodeProfile = NodeProfile("Mesh User", "Unknown")

    private var announcerJob: Job? = null

    fun attachConnectionsClient(client: ConnectionsClient, nodeId: String, profile: NodeProfile) {
        connectionsClient = client
        localNodeId = nodeId
        localProfile = profile
        handshakeManager.configure(nodeId) { packet -> sendPacket(packet) }

        announcerJob?.cancel()
        announcerJob = scope.launch {
            while (isActive) {
                delay(ANNOUNCE_INTERVAL_MS)
                routingTable.purgeExpired()
                broadcastRouteAnnouncement()
            }
        }
    }

    fun detach() {
        announcerJob?.cancel()
        announcerJob = null
        connectionsClient = null
    }

    /** Called when the user renames this node: the new profile is flooded with the next announcement. */
    fun updateLocalProfile(profile: NodeProfile) {
        localProfile = profile
        broadcastRouteAnnouncement()
    }

    // ---------------------------------------------------------------------------------------------
    // Links
    // ---------------------------------------------------------------------------------------------

    suspend fun onPeerConnected(endpointId: String, advert: PeerUsername.Advert) {
        val existing = peerDao.getPeerById(advert.nodeId)
        peerDao.upsertPeer(
            PeerEntity(
                id = advert.nodeId,
                displayName = advert.displayName,
                deviceModel = advert.deviceModel,
                initials = PeerUsername.initialsOf(advert.displayName),
                appName = APP_NAME,
                packageName = APP_PACKAGE,
                // Every connection opens UNVERIFIED; only the in-chat handshake can change that
                securityStatus = existing?.securityStatus ?: SecurityStatus.UNVERIFIED,
                lastSeen = System.currentTimeMillis()
            )
        )
        routingTable.addDirectNeighbor(endpointId, advert.nodeId)
        broadcastRouteAnnouncement()
    }

    fun onPeerDisconnected(endpointId: String) {
        routingTable.removeDirectNeighbor(endpointId)
    }

    // ---------------------------------------------------------------------------------------------
    // Ingress
    // ---------------------------------------------------------------------------------------------

    /** Ingress packet processing from a Nearby Connections BYTES payload. */
    fun processIncomingPacket(rawBytes: ByteArray, fromEndpointId: String) {
        scope.launch {
            try {
                val packet = MeshPacket.fromByteArrayOrNull(rawBytes)
                if (packet == null) {
                    Log.w(TAG, "Dropped malformed packet from $fromEndpointId")
                    return@launch
                }
                if (packet.hopCount > MAX_HOP_LIMIT || packet.sourceNodeId == localNodeId) return@launch

                // A packet that has made zero hops must have been written by the neighbour itself
                val viaNode = routingTable.nodeForEndpoint(fromEndpointId)
                if (packet.hopCount == 0 && viaNode != null && packet.sourceNodeId != viaNode) {
                    Log.w(TAG, "Dropped spoofed source ${packet.sourceNodeId} on link of $viaNode")
                    return@launch
                }

                // 1. Anti-replay nonce and timestamp window
                if (!cryptoEngine.validateAntiReplay(packet.nonce, packet.timestamp)) {
                    Log.w(TAG, "Dropped packet [${packet.packetId}] due to replay or expired timestamp")
                    return@launch
                }

                // 2. Loop & duplicate suppression
                synchronized(seenPacketIds) {
                    if (!seenPacketIds.add(packet.packetId)) return@launch
                }

                // 3. Distance-vector learning: the source is reachable through the link it arrived on
                if (viaNode != null) {
                    routingTable.learnRoute(packet.sourceNodeId, fromEndpointId, viaNode, packet.hopCount + 1)
                }

                // 4. Is this packet for me?
                val isForMe = packet.finalDestinationId == localNodeId
                val isBroadcast = packet.finalDestinationId == MeshPacket.BROADCAST
                if (isForMe || isBroadcast) {
                    handleLocalPacket(packet)
                }

                // 5. Multi-hop forwarding: everything not addressed to me goes on, undecrypted
                if (!isForMe) {
                    relayPacket(packet, fromEndpointId)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing incoming mesh packet", e)
            }
        }
    }

    private suspend fun handleLocalPacket(packet: MeshPacket) {
        when (packet.type) {
            MeshPacket.PacketType.ROUTE_ANNOUNCEMENT -> {
                packet.profile?.let { upsertRemotePeer(packet.sourceNodeId, it) }
            }

            MeshPacket.PacketType.KEY_EXCHANGE_REQUEST -> handshakeManager.onRequest(packet)

            MeshPacket.PacketType.KEY_EXCHANGE_RESPONSE -> handshakeManager.onResponse(packet)

            MeshPacket.PacketType.ROUTED_MESSAGE -> handleRoutedMessage(packet)

            MeshPacket.PacketType.ROUTED_MEDIA -> {
                // Media shares the sealing rules of text (see openPayload) but has no receiver yet
                Log.d(TAG, "Media packet ${packet.packetId} ignored: no media receiver installed")
            }
        }
    }

    private suspend fun handleRoutedMessage(packet: MeshPacket) {
        val peerId = packet.sourceNodeId
        ensurePeerExists(peerId)

        val opened = openPayload(peerId, packet)
        val now = System.currentTimeMillis()
        val entity = MessageEntity(
            id = packet.packetId,
            peerId = peerId,
            text = opened.text,
            // Local receive time keeps the timeline ordered even when the sender's clock is off
            timestamp = now,
            isFromMe = false,
            type = opened.type
        )
        messageDao.insertMessage(entity)
        if (opened.type != MessageTypes.SYSTEM) _incomingMessages.emit(entity)
        peerDao.updateLastSeen(peerId, now)
    }

    private class OpenedPayload(val text: String, val type: String)

    /**
     * Single choke point that decides how an incoming body is read. Text uses it today and any
     * future multimedia body has to pass through the same rules:
     *  - encrypted frame + session key  -> AES-GCM decrypt
     *  - plain frame + no session       -> raw plain text (first contact only)
     *  - plain frame from an authenticated peer -> rejected (downgrade protection)
     */
    private suspend fun openPayload(peerId: String, packet: MeshPacket): OpenedPayload {
        val body = packet.payload ?: return OpenedPayload("[Empty message]", MessageTypes.SYSTEM)
        val sessionKey = cryptoEngine.getActiveSessionKey(peerId)
        val peerIsEncrypted = peerDao.getPeerById(peerId)?.isEncrypted == true

        return if (packet.isEncrypted) {
            val iv = packet.initializationVector
            if (sessionKey == null || iv == null) {
                OpenedPayload("Encrypted message received, but no session key is available.", MessageTypes.SYSTEM)
            } else {
                try {
                    OpenedPayload(
                        String(cryptoEngine.decryptAesGcm(body, iv, sessionKey), StandardCharsets.UTF_8),
                        MessageTypes.TEXT
                    )
                } catch (e: Exception) {
                    OpenedPayload("An encrypted message could not be decrypted.", MessageTypes.SYSTEM)
                }
            }
        } else if (sessionKey != null || peerIsEncrypted) {
            OpenedPayload("Rejected an unencrypted message from an authenticated peer.", MessageTypes.SYSTEM)
        } else if (body.size > MAX_PLAIN_TEXT_BYTES) {
            OpenedPayload("Rejected an oversized plain-text message.", MessageTypes.SYSTEM)
        } else {
            OpenedPayload(String(body, StandardCharsets.UTF_8), MessageTypes.TEXT_PLAIN)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Multi-hop forwarding
    // ---------------------------------------------------------------------------------------------

    /**
     * Relays [packet] towards its final destination. Known route -> unicast to the next hop;
     * unknown destination or broadcast -> TTL-bounded flood to every other neighbour.
     * The incoming link is never used for the way back out.
     */
    private fun relayPacket(packet: MeshPacket, incomingEndpointId: String) {
        val client = connectionsClient ?: return
        if (!packet.canRelay() || packet.hopCount >= MAX_HOP_LIMIT) return

        when (val decision = routingTable.decideForward(packet.finalDestinationId, incomingEndpointId)) {
            is RoutingTable.ForwardDecision.Unicast -> {
                transmit(client, decision.endpointId, packet.forwardedVia(localNodeId, decision.nodeId))
                Log.d(TAG, "Relayed [${packet.packetId}] to ${packet.finalDestinationId} via ${decision.nodeId}")
            }

            is RoutingTable.ForwardDecision.Flood -> {
                for ((endpointId, nodeId) in decision.targets) {
                    transmit(client, endpointId, packet.forwardedVia(localNodeId, nodeId))
                }
            }

            RoutingTable.ForwardDecision.NoRoute -> Log.d(TAG, "No route for [${packet.packetId}], dropped")
        }
    }

    /**
     * Sends a packet that originates on this node: unicast along the known route, otherwise flood.
     * @return true when the packet was handed to at least one link.
     */
    fun sendPacket(packet: MeshPacket): Boolean {
        val client = connectionsClient ?: return false
        return when (val decision = routingTable.decideForward(packet.finalDestinationId, null)) {
            is RoutingTable.ForwardDecision.Unicast -> {
                transmit(client, decision.endpointId, packet.addressedTo(decision.nodeId))
                true
            }

            is RoutingTable.ForwardDecision.Flood -> {
                for ((endpointId, nodeId) in decision.targets) {
                    transmit(client, endpointId, packet.addressedTo(nodeId))
                }
                true
            }

            RoutingTable.ForwardDecision.NoRoute -> false
        }
    }

    private fun transmit(client: ConnectionsClient, endpointId: String, packet: MeshPacket) {
        client.sendPayload(endpointId, Payload.fromBytes(packet.toByteArray()))
    }

    // ---------------------------------------------------------------------------------------------
    // Egress: messages
    // ---------------------------------------------------------------------------------------------

    /**
     * Sends a chat message. Unverified conversations travel as raw plain text; once the peer is
     * "AES-GCM ENCRYPTED" every message is sealed with the session key. A verified peer without a
     * usable key is never downgraded to plain text.
     */
    fun dispatchMessage(destinationNodeId: String, text: String) {
        scope.launch {
            ensurePeerExists(destinationNodeId)
            val peer = peerDao.getPeerById(destinationNodeId)
            val sessionKey = cryptoEngine.getActiveSessionKey(destinationNodeId)

            if (peer?.isEncrypted == true && sessionKey == null) {
                postSystemNotice(
                    destinationNodeId,
                    "Not sent: the secure session key is unavailable, so the message was not downgraded to plain text."
                )
                return@launch
            }

            val plainBytes = text.toByteArray(StandardCharsets.UTF_8)
            val encrypted = sessionKey != null
            val (body, iv) = if (sessionKey != null) {
                cryptoEngine.encryptAesGcm(plainBytes, sessionKey)
            } else {
                Pair(plainBytes, null)
            }

            val packet = MeshPacket(
                sourceNodeId = localNodeId,
                targetNodeId = destinationNodeId,
                finalDestinationId = destinationNodeId,
                type = MeshPacket.PacketType.ROUTED_MESSAGE,
                nonce = cryptoEngine.generateNonce(),
                payload = body,
                isEncrypted = encrypted,
                initializationVector = iv,
                signature = cryptoEngine.sign(body)
            )

            messageDao.insertMessage(
                MessageEntity(
                    id = packet.packetId,
                    peerId = destinationNodeId,
                    text = text,
                    timestamp = packet.timestamp,
                    isFromMe = true,
                    type = if (encrypted) MessageTypes.TEXT else MessageTypes.TEXT_PLAIN
                )
            )

            if (!sendPacket(packet)) {
                postSystemNotice(destinationNodeId, "No link to this peer right now. The message was not delivered.")
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Announcements & peer directory
    // ---------------------------------------------------------------------------------------------

    /**
     * Floods this node's profile. Every node that hears it learns a route to us (distance vector)
     * and adds us to its peer directory, which is how remote multi-hop nodes show up.
     */
    fun broadcastRouteAnnouncement() {
        val client = connectionsClient ?: return
        val neighbors = routingTable.getDirectNeighborsMap()
        if (neighbors.isEmpty()) return
        val announcement = MeshPacket(
            sourceNodeId = localNodeId,
            targetNodeId = MeshPacket.BROADCAST,
            finalDestinationId = MeshPacket.BROADCAST,
            type = MeshPacket.PacketType.ROUTE_ANNOUNCEMENT,
            maxHops = ANNOUNCE_MAX_HOPS,
            nonce = cryptoEngine.generateNonce(),
            profile = localProfile
        )
        for ((endpointId, nodeId) in neighbors) {
            transmit(client, endpointId, announcement.addressedTo(nodeId))
        }
    }

    private suspend fun upsertRemotePeer(nodeId: String, profile: NodeProfile) {
        if (nodeId == localNodeId || nodeId == MeshPacket.BROADCAST) return
        val name = PeerUsername.sanitizeField(profile.displayName).ifBlank { "Mesh User" }
        val model = PeerUsername.sanitizeField(profile.deviceModel).ifBlank { "Unknown" }
        val initials = PeerUsername.initialsOf(name)
        peerDao.insertPeerIfAbsent(
            PeerEntity(
                id = nodeId,
                displayName = name,
                deviceModel = model,
                initials = initials,
                appName = APP_NAME,
                packageName = APP_PACKAGE
            )
        )
        peerDao.updateProfile(nodeId, name, model, initials, System.currentTimeMillis())
    }

    private suspend fun ensurePeerExists(nodeId: String) {
        if (nodeId == MeshPacket.BROADCAST || nodeId == localNodeId) return
        peerDao.insertPeerIfAbsent(
            PeerEntity(
                id = nodeId,
                displayName = "Mesh User",
                deviceModel = "Unknown",
                initials = PeerUsername.initialsOf(PeerUsername.shortId(nodeId)),
                appName = APP_NAME,
                packageName = APP_PACKAGE
            )
        )
    }

    private suspend fun postSystemNotice(peerId: String, text: String) {
        ensurePeerExists(peerId)
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

    // ---------------------------------------------------------------------------------------------
    // Security helpers used by the view model
    // ---------------------------------------------------------------------------------------------

    /** User clicked the banner: start the asymmetric key exchange with [peerId]. */
    fun activateEncryption(peerId: String) = handshakeManager.initiate(peerId)

    fun dismissHandshakeState(peerId: String) = handshakeManager.dismiss(peerId)
}
