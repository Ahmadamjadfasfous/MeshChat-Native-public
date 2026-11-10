package com.meshchat.app.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import com.meshchat.app.MeshChatApp
import com.meshchat.app.R
import com.meshchat.app.data.local.dao.PeerDao
import com.meshchat.app.data.local.entity.MessageEntity
import com.meshchat.app.data.local.entity.SecurityStatus
import com.meshchat.app.data.model.NodeProfile
import com.meshchat.app.data.preferences.PreferenceManager
import com.meshchat.app.domain.audio.WalkieTalkieEngine
import com.meshchat.app.domain.mesh.MeshRouter
import com.meshchat.app.domain.mesh.PeerUsername
import com.meshchat.app.domain.security.CryptoEngine
import com.meshchat.app.presentation.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

@AndroidEntryPoint
class MeshForegroundService : Service() {

    companion object {
        private const val TAG = "MeshForegroundService"
        private const val NOTIFICATION_ID = 9001
        private const val SOS_NOTIFICATION_ID = 9100
        private const val VOICE_NOTIFICATION_BASE = 9200
        private const val SERVICE_ID = "com.meshchat.app.SERVICE_ID"
        private const val MAX_PACKET_BYTES = 64 * 1024
        private const val SCREEN_WAKE_MS = 10_000L
        private const val MESSAGE_TYPE_PLAIN = "TEXT_PLAIN"
        const val ACTION_START = "ACTION_START_MESH"
        const val ACTION_STOP = "ACTION_STOP_MESH"
    }

    @Inject lateinit var preferenceManager: PreferenceManager
    @Inject lateinit var peerDao: PeerDao
    @Inject lateinit var meshRouter: MeshRouter
    @Inject lateinit var cryptoEngine: CryptoEngine
    @Inject lateinit var walkieTalkieEngine: WalkieTalkieEngine

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var connectionsClient: ConnectionsClient

    private var localNodeId: String = ""
    private var localNodeName: String = ""
    private var localDeviceModel: String = ""
    private var engineStarted = false

    // endpointId -> "name|nodeId" announced by the remote side
    private val endpointNames = ConcurrentHashMap<String, String>()

    inner class LocalBinder : Binder() {
        fun getService(): MeshForegroundService = this@MeshForegroundService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        connectionsClient = Nearby.getConnectionsClient(this)
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopMeshEngine()
                stopSelf()
            }
            else -> {
                startForegroundNotification()
                initMeshEngine()
            }
        }
        return START_STICKY
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "MeshChat::EngineWakeLock"
        ).apply {
            setReferenceCounted(false)
            acquire(24 * 60 * 60 * 1000L) // 24 hours safeguard
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Foreground notification
    // ---------------------------------------------------------------------------------------------

    private fun startForegroundNotification() {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification: Notification = NotificationCompat.Builder(this, MeshChatApp.CHANNEL_ID)
            .setContentTitle(getString(R.string.service_notification_title))
            .setContentText(getString(R.string.service_notification_desc))
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            try {
                startForeground(NOTIFICATION_ID, notification, type)
            } catch (e: SecurityException) {
                // Microphone type is rejected when RECORD_AUDIO is not granted: keep the mesh alive without it
                Log.w(TAG, "Foreground start with microphone type rejected, retrying without it", e)
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                )
            }
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Mesh engine
    // ---------------------------------------------------------------------------------------------

    @SuppressLint("MissingPermission")
    private fun initMeshEngine() {
        if (engineStarted) return
        engineStarted = true
        serviceScope.launch {
            localNodeId = preferenceManager.getOrCreateNodeId()
            localNodeName = preferenceManager.nodeNameFlow.first()
            localDeviceModel = preferenceManager.deviceModel
            meshRouter.attachConnectionsClient(
                connectionsClient,
                localNodeId,
                NodeProfile(localNodeName, localDeviceModel)
            )

            // Restore AES-GCM session keys of "AES-GCM ENCRYPTED" peers (they live in memory only).
            // A peer whose key cannot be restored falls back to the plain-text / unverified state
            // instead of silently sending plain text while the UI claims encryption.
            try {
                peerDao.getEncryptedPeers().first().forEach { peer ->
                    if (!cryptoEngine.loadPeerTrust(peer.id)) {
                        peerDao.setSecurityStatus(peer.id, SecurityStatus.UNVERIFIED)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not restore session keys", e)
            }

            walkieTalkieEngine.attachConnectionsClient(connectionsClient)
            // Unicast guard: PTT streams are only sent to / accepted from connected neighbours
            walkieTalkieEngine.endpointGuard = { endpointId -> meshRouter.routingTable.isDirectEndpoint(endpointId) }
            walkieTalkieEngine.endpointLabelResolver = { endpointId -> labelFor(endpointId) }

            startAlertCollectors()
            startAdvertising()
            startDiscovery()
        }
    }

    @SuppressLint("MissingPermission")
    private fun startAdvertising() {
        val advertisingOptions = AdvertisingOptions.Builder()
            .setStrategy(Strategy.P2P_CLUSTER)
            .build()

        connectionsClient.startAdvertising(
            advertName(),
            SERVICE_ID,
            connectionLifecycleCallback,
            advertisingOptions
        ).addOnSuccessListener {
            Log.d(TAG, "P2P_CLUSTER Advertising started successfully")
        }.addOnFailureListener { e ->
            Log.e(TAG, "Failed to start advertising", e)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startDiscovery() {
        val discoveryOptions = DiscoveryOptions.Builder()
            .setStrategy(Strategy.P2P_CLUSTER)
            .build()

        connectionsClient.startDiscovery(
            SERVICE_ID,
            endpointDiscoveryCallback,
            discoveryOptions
        ).addOnSuccessListener {
            Log.d(TAG, "P2P_CLUSTER Discovery active")
        }.addOnFailureListener { e ->
            Log.e(TAG, "Failed to start discovery", e)
        }
    }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        @SuppressLint("MissingPermission")
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            Log.d(TAG, "Discovered endpoint: $endpointId (${info.endpointName})")
            val advert = PeerUsername.decodeAdvert(info.endpointName)
            val peerId = advert?.nodeId ?: endpointId

            // Both sides discover each other; only the side with the smaller node id initiates,
            // otherwise both would request a connection at the same time and collide.
            if (advert == null || localNodeId < peerId) {
                connectionsClient.requestConnection(
                    advertName(),
                    endpointId,
                    connectionLifecycleCallback
                )
            }
        }

        override fun onEndpointLost(endpointId: String) {
            meshRouter.onPeerDisconnected(endpointId)
        }
    }

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionInitiated(endpointId: String, connectionInfo: ConnectionInfo) {
            endpointNames[endpointId] = connectionInfo.endpointName
            // Auto accept cluster connection
            connectionsClient.acceptConnection(endpointId, payloadCallback)
        }

        override fun onConnectionResult(endpointId: String, resolution: ConnectionResolution) {
            if (resolution.status.isSuccess) {
                Log.d(TAG, "Mesh link established with $endpointId")
                // The mesh is addressed by node id (not by the transient Nearby endpoint id)
                val advert = PeerUsername.decodeAdvert(endpointNames[endpointId] ?: "")
                if (advert == null) {
                    Log.w(TAG, "Endpoint $endpointId does not advertise a MeshChat username, disconnecting")
                    connectionsClient.disconnectFromEndpoint(endpointId)
                    return
                }
                // Every connection opens UNVERIFIED; the in-chat plain-text handshake is the only way up
                serviceScope.launch { meshRouter.onPeerConnected(endpointId, advert) }
            } else {
                Log.w(TAG, "Connection failed to $endpointId: ${resolution.status.statusMessage}")
            }
        }

        override fun onDisconnected(endpointId: String) {
            endpointNames.remove(endpointId)
            meshRouter.onPeerDisconnected(endpointId)
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            when (payload.type) {
                Payload.Type.BYTES -> {
                    payload.asBytes()?.let { bytes ->
                        if (bytes.size <= MAX_PACKET_BYTES) {
                            meshRouter.processIncomingPacket(bytes, endpointId)
                        } else {
                            Log.w(TAG, "Dropped oversized packet (${bytes.size} bytes) from $endpointId")
                        }
                    }
                }
                Payload.Type.STREAM -> {
                    // Automatic receiver activation: playback starts immediately, no user interaction.
                    // The header-based alert (message/voice/SOS notification) is raised through
                    // walkieTalkieEngine.incomingStreams once the stream mode has been read.
                    payload.asStream()?.let { stream ->
                        walkieTalkieEngine.onStreamPayloadReceived(endpointId, payload.id, stream)
                    }
                }
                Payload.Type.FILE -> {
                    Log.d(TAG, "Received file payload")
                }
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            if (update.status == PayloadTransferUpdate.Status.SUCCESS) {
                Log.d(TAG, "Payload transfer finished for ${update.payloadId}")
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Background alert persistence
    // ---------------------------------------------------------------------------------------------

    private fun startAlertCollectors() {
        serviceScope.launch {
            meshRouter.incomingMessages.collect { message -> notifyIncomingMessage(message) }
        }
        serviceScope.launch {
            walkieTalkieEngine.incomingStreams.collect { stream -> notifyIncomingStream(stream) }
        }
    }

    private suspend fun notifyIncomingMessage(message: MessageEntity) {
        if (MeshChatApp.isInForeground) return
        val peerName = peerDao.getPeerById(message.peerId)?.displayName ?: "Mesh peer"
        val isPlain = message.type == MESSAGE_TYPE_PLAIN
        // Never put message content on the lock screen: only the fact that something arrived
        val text = if (isPlain) "New message (first contact, unencrypted)" else "New encrypted message"
        postAlert(
            channelId = MeshChatApp.CHANNEL_MESSAGES,
            notificationId = message.peerId.hashCode(),
            title = peerName,
            text = text,
            category = NotificationCompat.CATEGORY_MESSAGE,
            openPeerId = message.peerId,
            sos = false
        )
    }

    private fun notifyIncomingStream(stream: WalkieTalkieEngine.IncomingStream) {
        if (MeshChatApp.isInForeground) return
        val nodeId = meshRouter.routingTable.getDirectNeighborsMap()[stream.endpointId]
        if (stream.mode == WalkieTalkieEngine.StreamMode.SOS) {
            wakeScreenBriefly()
            postAlert(
                channelId = MeshChatApp.CHANNEL_SOS,
                notificationId = SOS_NOTIFICATION_ID,
                title = "EMERGENCY SOS",
                text = "${stream.senderLabel} is broadcasting an emergency audio stream",
                category = NotificationCompat.CATEGORY_ALARM,
                openPeerId = nodeId,
                sos = true
            )
        } else {
            postAlert(
                channelId = MeshChatApp.CHANNEL_VOICE,
                notificationId = VOICE_NOTIFICATION_BASE + (stream.endpointId.hashCode() and 0xFF),
                title = stream.senderLabel,
                text = "Incoming walkie-talkie audio",
                category = NotificationCompat.CATEGORY_CALL,
                openPeerId = nodeId,
                sos = false
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun postAlert(
        channelId: String,
        notificationId: Int,
        title: String,
        text: String,
        category: String,
        openPeerId: String?,
        sos: Boolean
    ) {
        val manager = NotificationManagerCompat.from(this)
        if (!manager.areNotificationsEnabled()) return

        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (openPeerId != null) putExtra(MainActivity.EXTRA_PEER_ID, openPeerId)
            if (sos) putExtra(MainActivity.EXTRA_SOS, true)
        }
        val contentIntent = PendingIntent.getActivity(
            this,
            notificationId, // unique request code so extras are never shared between alerts
            openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val publicVersion = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle("MeshChat")
            .setContentText(if (sos) "Emergency alert" else "New activity")
            .build()

        val builder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(category)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .setPublicVersion(publicVersion)
            .setVisibility(if (sos) NotificationCompat.VISIBILITY_PUBLIC else NotificationCompat.VISIBILITY_PRIVATE)

        if (sos) {
            // Wakes the screen / opens the app over the keyguard where the OS allows it
            builder.setFullScreenIntent(contentIntent, true)
            builder.setOngoing(false)
        }

        try {
            manager.notify(notificationId, builder.build())
        } catch (e: SecurityException) {
            Log.w(TAG, "Notification permission revoked", e)
        }
    }

    /** Short screen wake for SOS only; deep-sleep alerts otherwise rely on the foreground service. */
    @Suppress("DEPRECATION")
    private fun wakeScreenBriefly() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            powerManager.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "MeshChat::SosScreenWake"
            ).acquire(SCREEN_WAKE_MS)
        } catch (e: Exception) {
            Log.w(TAG, "Could not wake the screen", e)
        }
    }

    /** Advertisement / connection name carrying the three username fields: name|model|nodeId. */
    private fun advertName(): String = PeerUsername.encodeAdvert(localNodeName, localDeviceModel, localNodeId)

    private fun labelFor(endpointId: String): String =
        endpointNames[endpointId]?.substringBefore('|')?.takeIf { it.isNotBlank() } ?: endpointId

    // ---------------------------------------------------------------------------------------------

    private fun stopMeshEngine() {
        try {
            connectionsClient.stopAdvertising()
            connectionsClient.stopDiscovery()
            connectionsClient.stopAllEndpoints()
            engineStarted = false
            walkieTalkieEngine.detachConnectionsClient()
            meshRouter.detach()
            wakeLock?.let {
                if (it.isHeld) it.release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping mesh engine", e)
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        stopMeshEngine()
        super.onDestroy()
    }
}
