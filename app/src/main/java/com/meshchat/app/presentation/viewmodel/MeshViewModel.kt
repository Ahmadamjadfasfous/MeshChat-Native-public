package com.meshchat.app.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meshchat.app.data.local.dao.MessageDao
import com.meshchat.app.data.local.dao.PeerDao
import com.meshchat.app.data.local.entity.MessageEntity
import com.meshchat.app.data.local.entity.PeerEntity
import com.meshchat.app.data.model.NodeProfile
import com.meshchat.app.data.preferences.PreferenceManager
import com.meshchat.app.domain.audio.WalkieTalkieEngine
import com.meshchat.app.domain.mesh.HandshakeManager
import com.meshchat.app.domain.mesh.MeshRouter
import com.meshchat.app.domain.mesh.PeerUsername
import com.meshchat.app.domain.mesh.username
import com.meshchat.app.domain.security.CryptoEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * One row of the unified "Available Network Peers" directory. Direct neighbours (hopCount 1) and
 * remote nodes learned through the multi-hop routing table live in the same list.
 *
 * @property hopCount number of hops to the peer, or null when there is no live route to it.
 */
data class NetworkPeer(
    val peer: PeerEntity,
    val hopCount: Int?,
    val isDirect: Boolean
) {
    val isReachable: Boolean get() = hopCount != null
    val username: String get() = peer.username()
}

@HiltViewModel
class MeshViewModel @Inject constructor(
    private val peerDao: PeerDao,
    private val messageDao: MessageDao,
    private val meshRouter: MeshRouter,
    private val cryptoEngine: CryptoEngine,
    val walkieTalkieEngine: WalkieTalkieEngine,
    private val preferenceManager: PreferenceManager
) : ViewModel() {

    companion object {
        private const val MAX_MESSAGE_CHARS = 2000 // keeps a plain-text frame below the 8 KB receive cap
    }

    val language: StateFlow<String> = preferenceManager.languageFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, "ar")

    val localNodeId: StateFlow<String> = preferenceManager.nodeIdFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, "local_node")

    val localNodeName: StateFlow<String> = preferenceManager.nodeNameFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, "Mesh User")

    val localDeviceModel: String get() = preferenceManager.deviceModel

    // --- Unified peer directory ----------------------------------------------------------------

    /**
     * Every discovered node in ONE list: reachable peers first (nearest first), then known but
     * currently unreachable peers (kept so their chat history stays accessible).
     */
    val networkPeers: StateFlow<List<NetworkPeer>> = combine(
        peerDao.getAllPeers(),
        meshRouter.routingTable.routes,
        preferenceManager.nodeIdFlow
    ) { peers, routes, selfId ->
        val routeByNode = routes.associateBy { it.destinationNodeId }
        peers
            .filter { it.id != selfId }
            .map { peer ->
                val route = routeByNode[peer.id]
                NetworkPeer(peer = peer, hopCount = route?.hopCount, isDirect = route?.isDirect == true)
            }
            .sortedWith(
                compareBy<NetworkPeer> { it.hopCount == null }
                    .thenBy { it.hopCount ?: Int.MAX_VALUE }
                    .thenBy { it.peer.displayName.lowercase() }
            )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setLanguage(langCode: String) {
        viewModelScope.launch {
            preferenceManager.setLanguage(langCode)
        }
    }

    /** Renames this node (first field of its username) and floods the new profile. */
    fun setDisplayName(name: String) {
        viewModelScope.launch {
            preferenceManager.setNodeName(name)
            val saved = PeerUsername.sanitizeField(name)
            if (saved.isNotBlank()) {
                meshRouter.updateLocalProfile(NodeProfile(saved, preferenceManager.deviceModel))
            }
        }
    }

    // --- Audio -------------------------------------------------------------------------------
    val walkieTalkieState: StateFlow<WalkieTalkieEngine.StreamState> = walkieTalkieEngine.streamState
    val bufferLatencyMs: StateFlow<Int> = walkieTalkieEngine.bufferLatencyMs
    val transmitMode: StateFlow<WalkieTalkieEngine.StreamMode?> = walkieTalkieEngine.transmitMode
    val isSosIncoming: StateFlow<Boolean> = walkieTalkieEngine.isSosIncoming

    // --- Chat --------------------------------------------------------------------------------

    fun messagesFor(peerId: String): Flow<List<MessageEntity>> = messageDao.getMessagesForPeer(peerId)

    fun observePeer(peerId: String): Flow<PeerEntity?> = peerDao.getPeerFlowById(peerId)

    /** Link state of a single peer (hop count, direct or not) for the chat header. */
    fun observeLink(peerId: String): Flow<NetworkPeer?> =
        networkPeers.map { list -> list.firstOrNull { it.peer.id == peerId } }

    /** Key-exchange progress per peer, shown by the verification banner of the initiating side. */
    val handshakePhases: StateFlow<Map<String, HandshakeManager.Phase>> = meshRouter.handshakePhases

    /**
     * Plain text while the conversation is unverified, AES-GCM once "AES-GCM ENCRYPTED"; the
     * router decides, the UI does not have to care.
     */
    fun sendMessage(targetPeerId: String, text: String) {
        val clean = text.trim().take(MAX_MESSAGE_CHARS)
        if (clean.isEmpty()) return
        meshRouter.dispatchMessage(targetPeerId, clean)
    }

    /** Peer A clicked "[Activate Asymmetric ECDH Encryption]". */
    fun activateEncryption(peerId: String) {
        meshRouter.activateEncryption(peerId)
    }

    fun dismissHandshakeFailure(peerId: String) {
        meshRouter.dismissHandshakeState(peerId)
    }

    // --- Private PTT (strictly unicast) --------------------------------------------------------

    /** STREAM payloads are single-hop, so a private PTT needs a direct Nearby link to the peer. */
    fun isDirectlyLinked(peerNodeId: String): Boolean = directEndpointFor(peerNodeId) != null

    /** Starts streaming the microphone to this one peer's endpoint; false if it is not a direct neighbour. */
    fun startPrivatePtt(peerNodeId: String): Boolean {
        val endpointId = directEndpointFor(peerNodeId) ?: return false
        return walkieTalkieEngine.startPrivateTransmission(endpointId)
    }

    fun stopPtt() {
        if (walkieTalkieEngine.transmitMode.value == WalkieTalkieEngine.StreamMode.PTT) {
            walkieTalkieEngine.stopTransmission()
        }
    }

    private fun directEndpointFor(peerNodeId: String): String? =
        meshRouter.routingTable.directEndpointFor(peerNodeId)

    // --- Emergency SOS (omni-directional) ----------------------------------------------------

    fun startSos(): Boolean {
        val endpoints = meshRouter.routingTable.getDirectEndpoints()
        return walkieTalkieEngine.startSosBroadcast(endpoints)
    }

    fun stopSos() {
        if (walkieTalkieEngine.transmitMode.value == WalkieTalkieEngine.StreamMode.SOS) {
            walkieTalkieEngine.stopTransmission()
        }
    }

    fun purgeTrustStore() {
        viewModelScope.launch {
            cryptoEngine.purgeAllTrust()
            peerDao.resetAllSecurityStatus()
        }
    }
}
