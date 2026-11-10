package com.meshchat.app.domain.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.Payload
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Real-time voice engine built on Nearby Connections Payload.Type.STREAM.
 *
 * Private PTT (strictly unicast)
 *   TX : AudioRecord (16 kHz mono PCM) -> G.711 mu-law (2:1) -> ONE pipe, handed to Nearby as a
 *        STREAM payload bound to exactly one endpointId (the peer of the open chat). Broadcasting a
 *        PTT stream to several endpoints is not possible through [startPrivateTransmission].
 *   RX : the moment a STREAM payload reaches the receiver, [onStreamPayloadReceived] spins up a
 *        dedicated URGENT_AUDIO playback thread with its own AudioTrack, raises STREAM_MUSIC to the
 *        maximum and pins the output to the built-in loudspeaker. No button, dialog or approval is
 *        involved. The line closes by itself when the sender releases the key (end of stream) or,
 *        as a safety net, when no audio arrived for [RX_IDLE_TIMEOUT_MS]; the previous volume is then
 *        restored.
 *
 * SOS : same wire format with the SOS mode flag in the stream header. Receivers raise the alarm
 *       stream volume to maximum for the duration of the stream and restore it afterwards.
 *
 * Wire format of every stream: [0xA5][mode byte] followed by raw mu-law bytes (1 byte per sample).
 * The header travels inside the stream, so there is no race with a separate control packet.
 *
 * Limitation (Nearby Connections): STREAM payloads are single-hop. Targets must be direct neighbours.
 */
@Singleton
class WalkieTalkieEngine @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "WalkieTalkieEngine"

        const val SAMPLE_RATE = 16000
        const val CHANNEL_IN = AudioFormat.CHANNEL_IN_MONO
        const val CHANNEL_OUT = AudioFormat.CHANNEL_OUT_MONO
        const val AUDIO_ENCODING = AudioFormat.ENCODING_PCM_16BIT
        const val FRAME_SIZE_BYTES = 640 // 20 ms of 16 kHz 16-bit mono PCM

        private const val ENCODED_FRAME_BYTES = FRAME_SIZE_BYTES / 2
        private const val STREAM_MAGIC: Byte = 0xA5.toByte()
        private const val HEADER_BYTES = 2
        private const val MAX_RECEIVER_SESSIONS = 4
        private const val SINK_QUEUE_CAPACITY = 64
        private const val PREBUFFER_BYTES = FRAME_SIZE_BYTES * 3 // 60 ms jitter pre-roll
        private const val TRACK_BUFFER_BYTES = FRAME_SIZE_BYTES * 12
        private const val DRAIN_TIMEOUT_MS = 600L
        private const val RX_IDLE_TIMEOUT_MS = 2_000L // sender released the key but the link did not report EOF
        private const val WATCHDOG_PERIOD_MS = 250L

        // Hard safety caps so a stuck stream can never blast the speaker (or the mic) forever.
        private const val MAX_PTT_MS = 120_000L
        private const val MAX_SOS_MS = 600_000L
    }

    enum class StreamMode(val wireId: Byte) {
        PTT(0),
        SOS(1);

        companion object {
            fun fromWire(id: Byte): StreamMode? = values().firstOrNull { it.wireId == id }
        }
    }

    /** Kept source-compatible with the existing UI (exhaustive `when` in PttWalkieTalkieScreen). */
    sealed class StreamState {
        object Idle : StreamState()
        object Transmitting : StreamState()
        data class Receiving(val senderPeerId: String) : StreamState()
    }

    /** Emitted once the header of an incoming stream has been read (mode is then known). */
    data class IncomingStream(
        val endpointId: String,
        val senderLabel: String,
        val mode: StreamMode
    )

    private val _streamState = MutableStateFlow<StreamState>(StreamState.Idle)
    val streamState: StateFlow<StreamState> = _streamState.asStateFlow()

    private val _bufferLatencyMs = MutableStateFlow(0)
    val bufferLatencyMs: StateFlow<Int> = _bufferLatencyMs.asStateFlow()

    private val _transmitMode = MutableStateFlow<StreamMode?>(null)
    /** Non-null while this device is transmitting; SOS when the panic broadcast is live. */
    val transmitMode: StateFlow<StreamMode?> = _transmitMode.asStateFlow()

    private val _transmitEndpoint = MutableStateFlow<String?>(null)

    /** The single endpointId a private PTT is currently bound to (null when not transmitting privately). */
    val transmitEndpoint: StateFlow<String?> = _transmitEndpoint.asStateFlow()

    private val _sosIncoming = MutableStateFlow(false)
    val isSosIncoming: StateFlow<Boolean> = _sosIncoming.asStateFlow()

    private val _incomingStreams = MutableSharedFlow<IncomingStream>(extraBufferCapacity = 16)
    val incomingStreams: SharedFlow<IncomingStream> = _incomingStreams.asSharedFlow()

    /** When false, incoming SOS streams are still played, but without the volume override. */
    @Volatile
    var acceptIncomingSos: Boolean = true

    /**
     * Unicast guard (set by the service): true only for endpoints that are currently connected
     * neighbours. Outgoing PTT to anything else is refused and incoming streams from anything else
     * are dropped without playing a single sample.
     */
    @Volatile
    var endpointGuard: (String) -> Boolean = { true }

    /** Maps a Nearby endpointId to a human readable label (set by the service). */
    @Volatile
    var endpointLabelResolver: (String) -> String = { it }

    @Volatile
    private var connectionsClient: ConnectionsClient? = null

    private var transmitSession: TransmitSession? = null
    private val receivers = LinkedHashMap<String, ReceiveSession>() // endpointId -> session

    private val audioManager: AudioManager by lazy {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    // Reference counted volume overrides so overlapping streams restore the user's level correctly
    private val musicBoost = VolumeBoost(AudioManager.STREAM_MUSIC) // private PTT playback
    private val alarmBoost = VolumeBoost(AudioManager.STREAM_ALARM) // SOS playback

    private val watchdogExecutor: ScheduledExecutorService by lazy {
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "WalkieTalkie-Watchdog").apply { isDaemon = true }
        }
    }

    fun attachConnectionsClient(client: ConnectionsClient) {
        connectionsClient = client
    }

    fun detachConnectionsClient() {
        release()
        connectionsClient = null
    }

    // ---------------------------------------------------------------------------------------------
    // Transmit
    // ---------------------------------------------------------------------------------------------

    /** Private PTT: streams to exactly one direct endpoint (the peer of the open chat). */
    fun startPrivateTransmission(endpointId: String): Boolean {
        if (!endpointGuard(endpointId)) {
            Log.w(TAG, "Private PTT refused: $endpointId is not a connected neighbour")
            return false
        }
        return startTransmission(listOf(endpointId), StreamMode.PTT)
    }

    /** Emergency SOS: streams to every given direct endpoint simultaneously. */
    fun startSosBroadcast(endpointIds: Collection<String>): Boolean =
        startTransmission(endpointIds, StreamMode.SOS)

    @Synchronized
    fun isTransmitting(): Boolean = transmitSession != null

    /**
     * Core TX entry point. Opens one pipe + STREAM payload per target endpoint and tees the
     * captured, compressed audio into all of them. Returns false if nothing could be started.
     */
    @SuppressLint("MissingPermission")
    @Synchronized
    fun startTransmission(endpointIds: Collection<String>, mode: StreamMode): Boolean {
        val client = connectionsClient
        if (client == null) {
            Log.w(TAG, "startTransmission: no ConnectionsClient attached")
            return false
        }
        val targets = endpointIds.distinct()
        if (targets.isEmpty()) {
            Log.w(TAG, "startTransmission: no target endpoints")
            return false
        }
        if (mode == StreamMode.PTT && targets.size != 1) {
            Log.w(TAG, "startTransmission: PTT is unicast, refusing ${targets.size} targets")
            return false
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "startTransmission: RECORD_AUDIO not granted")
            return false
        }

        val existing = transmitSession
        if (existing != null) {
            if (mode == StreamMode.SOS && existing.mode != StreamMode.SOS) {
                // SOS pre-empts any normal PTT in progress
                existing.requestStop()
                transmitSession = null
            } else {
                return false
            }
        }

        val record = createAudioRecord() ?: return false
        val sinks = ArrayList<PipeSink>(targets.size)
        for (endpointId in targets) {
            openSink(client, endpointId, mode)?.let { sinks.add(it) }
        }
        if (sinks.isEmpty()) {
            record.release()
            return false
        }

        val session = TransmitSession(mode, record, sinks)
        transmitSession = session
        refreshStateLocked()
        session.start()
        return true
    }

    @Synchronized
    fun stopTransmission() {
        transmitSession?.requestStop()
    }

    private fun openSink(client: ConnectionsClient, endpointId: String, mode: StreamMode): PipeSink? {
        var readSide: ParcelFileDescriptor? = null
        var writeSide: ParcelFileDescriptor? = null
        return try {
            val pipe = ParcelFileDescriptor.createPipe()
            readSide = pipe[0]
            writeSide = pipe[1]
            val sink = PipeSink(endpointId, ParcelFileDescriptor.AutoCloseOutputStream(writeSide), mode)
            client.sendPayload(endpointId, Payload.fromStream(readSide))
                .addOnFailureListener { e ->
                    Log.w(TAG, "sendPayload(STREAM) failed for $endpointId", e)
                    sink.close()
                }
            sink.start()
            sink
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open stream to $endpointId", e)
            try { readSide?.close() } catch (_: IOException) { }
            try { writeSide?.close() } catch (_: IOException) { }
            null
        }
    }

    @SuppressLint("MissingPermission")
    private fun createAudioRecord(): AudioRecord? {
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_IN, AUDIO_ENCODING)
        if (minBuf <= 0) return null
        val bufSize = maxOf(minBuf, FRAME_SIZE_BYTES * 8)
        val sources = intArrayOf(MediaRecorder.AudioSource.VOICE_COMMUNICATION, MediaRecorder.AudioSource.MIC)
        for (source in sources) {
            try {
                val record = AudioRecord(source, SAMPLE_RATE, CHANNEL_IN, AUDIO_ENCODING, bufSize)
                if (record.state == AudioRecord.STATE_INITIALIZED) return record
                record.release()
            } catch (e: Exception) {
                Log.w(TAG, "AudioRecord init failed for source $source", e)
            }
        }
        return null
    }

    @Synchronized
    private fun onTransmitFinished(session: TransmitSession) {
        if (transmitSession === session) {
            transmitSession = null
        }
        refreshStateLocked()
    }

    /** Capture thread: mic -> mu-law -> every sink. */
    private inner class TransmitSession(
        val mode: StreamMode,
        private val record: AudioRecord,
        private val sinks: List<PipeSink>
    ) : Thread("WalkieTalkie-Capture") {

        /** For a private PTT: the one endpoint this session is bound to. */
        val targetEndpointId: String? get() = sinks.singleOrNull()?.endpointId

        @Volatile
        private var running = true

        fun requestStop() {
            running = false
        }

        override fun run() {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            var echo: AcousticEchoCanceler? = null
            var suppressor: NoiseSuppressor? = null
            val pcm = ByteArray(FRAME_SIZE_BYTES)
            val startedAt = SystemClock.elapsedRealtime()
            val limitMs = if (mode == StreamMode.SOS) MAX_SOS_MS else MAX_PTT_MS
            try {
                val sessionId = record.audioSessionId
                if (sessionId != 0) {
                    if (AcousticEchoCanceler.isAvailable()) {
                        echo = AcousticEchoCanceler.create(sessionId)?.also { it.setEnabled(true) }
                    }
                    if (NoiseSuppressor.isAvailable()) {
                        suppressor = NoiseSuppressor.create(sessionId)?.also { it.setEnabled(true) }
                    }
                }

                record.startRecording()
                if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    throw IllegalStateException("Microphone unavailable")
                }

                while (running && sinks.any { !it.isDead }) {
                    if (SystemClock.elapsedRealtime() - startedAt > limitMs) break
                    val read = record.read(pcm, 0, pcm.size)
                    if (read < 0) break
                    val usable = read and 1.inv() // whole 16-bit samples only
                    if (usable == 0) continue
                    val frame = MuLawCodec.encode(pcm, usable)
                    for (sink in sinks) sink.offer(frame)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Capture loop failed", e)
            } finally {
                try { record.stop() } catch (_: Exception) { }
                record.release()
                try { echo?.release() } catch (_: Exception) { }
                try { suppressor?.release() } catch (_: Exception) { }
                for (sink in sinks) sink.close()
                onTransmitFinished(this)
            }
        }
    }

    /** One outbound pipe = one STREAM payload for one endpoint, drained by its own writer thread. */
    private class PipeSink(
        val endpointId: String,
        private val out: OutputStream,
        mode: StreamMode
    ) : Thread("WalkieTalkie-Sink-$endpointId") {

        private val queue = ArrayBlockingQueue<ByteArray>(SINK_QUEUE_CAPACITY)
        private val poison = ByteArray(0)

        @Volatile
        var isDead = false
            private set

        init {
            isDaemon = true
            queue.offer(byteArrayOf(STREAM_MAGIC, mode.wireId))
        }

        fun offer(frame: ByteArray) {
            if (isDead) return
            if (!queue.offer(frame)) {
                // Slow peer: drop the oldest frame instead of stalling the microphone loop
                queue.poll()
                queue.offer(frame)
            }
        }

        fun close() {
            if (isDead) return
            isDead = true
            queue.clear()
            queue.offer(poison)
            interrupt()
        }

        override fun run() {
            try {
                while (true) {
                    val frame = queue.take()
                    if (frame === poison) break
                    out.write(frame)
                }
            } catch (_: InterruptedException) {
                // closing
            } catch (e: IOException) {
                Log.w(TAG, "Stream to $endpointId ended: ${e.message}")
            } finally {
                isDead = true
                try { out.close() } catch (_: IOException) { }
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Receive (automatic activation)
    // ---------------------------------------------------------------------------------------------

    /**
     * Called from the Nearby PayloadCallback for every Payload.Type.STREAM. Spins up a dedicated
     * background playback thread immediately; no user interaction is involved.
     */
    @Synchronized
    fun onStreamPayloadReceived(endpointId: String, payloadId: Long, stream: Payload.Stream) {
        val input = stream.asInputStream()

        if (!endpointGuard(endpointId)) {
            Log.w(TAG, "Dropped stream $payloadId from unknown endpoint $endpointId")
            try { input.close() } catch (_: IOException) { }
            return
        }

        receivers.remove(endpointId)?.requestStop() // a newer stream from the same peer replaces the old one

        if (receivers.size >= MAX_RECEIVER_SESSIONS) {
            val victim = receivers.values.firstOrNull { it.mode != StreamMode.SOS }
            if (victim == null) {
                try { input.close() } catch (_: IOException) { }
                return
            }
            receivers.remove(victim.endpointId)
            victim.requestStop()
        }

        val session = ReceiveSession(endpointId, payloadId, input)
        receivers[endpointId] = session
        session.start()
    }

    @Synchronized
    fun stopReceiving() {
        for (session in receivers.values.toList()) session.requestStop()
    }

    @Synchronized
    private fun onReceiverChanged() {
        refreshStateLocked()
    }

    @Synchronized
    private fun onReceiverFinished(session: ReceiveSession) {
        if (receivers[session.endpointId] === session) {
            receivers.remove(session.endpointId)
        }
        refreshStateLocked()
    }

    private inner class ReceiveSession(
        val endpointId: String,
        private val payloadId: Long,
        private val input: InputStream
    ) : Thread("WalkieTalkie-Playback-$payloadId") {

        @Volatile
        var mode: StreamMode = StreamMode.PTT

        @Volatile
        var label: String = endpointId

        @Volatile
        private var running = true

        /** True when the sender ended the line (EOF) or went silent: the tail is still played out. */
        @Volatile
        private var lineClosedBySender = false

        @Volatile
        private var lastDataAt = SystemClock.elapsedRealtime()

        @Volatile
        var latencyMs: Int = 0

        /** Hard stop (replaced by a newer stream, engine released): buffered audio is discarded. */
        fun requestStop() {
            running = false
            try { input.close() } catch (_: IOException) { }
        }

        /** Soft stop used by the idle watchdog: lets the already buffered audio finish. */
        private fun closeIdleLine() {
            lineClosedBySender = true
            running = false
            try { input.close() } catch (_: IOException) { }
        }

        override fun run() {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            var track: AudioTrack? = null
            var focus: AudioFocusRequest? = null
            var volumeBoost: VolumeBoost? = null
            var watchdog: ScheduledFuture<*>? = null
            var started = false
            var framesWritten = 0L
            val startedAt = SystemClock.elapsedRealtime()
            try {
                val header = ByteArray(HEADER_BYTES)
                if (!input.readFully(header, HEADER_BYTES)) return
                if (header[0] != STREAM_MAGIC) {
                    Log.w(TAG, "Dropping stream $payloadId: unknown header")
                    return
                }
                val wireMode = StreamMode.fromWire(header[1]) ?: StreamMode.PTT
                mode = if (wireMode == StreamMode.SOS && !acceptIncomingSos) StreamMode.PTT else wireMode
                label = endpointLabelResolver(endpointId)

                // Automatic receiver: force the stream to maximum system volume for this session.
                // PTT plays as media on the loudspeaker (STREAM_MUSIC), SOS as an alarm (STREAM_ALARM).
                volumeBoost = if (mode == StreamMode.SOS) alarmBoost else musicBoost
                if (!volumeBoost.acquire()) volumeBoost = null

                val attributes = AudioAttributes.Builder()
                    .setUsage(if (mode == StreamMode.SOS) AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                focus = requestFocus(attributes, mode)
                track = buildAudioTrack(attributes) ?: return

                onReceiverChanged()
                _incomingStreams.tryEmit(IncomingStream(endpointId, label, mode))

                lastDataAt = SystemClock.elapsedRealtime()
                watchdog = watchdogExecutor.scheduleWithFixedDelay(
                    {
                        if (running && SystemClock.elapsedRealtime() - lastDataAt > RX_IDLE_TIMEOUT_MS) {
                            closeIdleLine()
                        }
                    },
                    WATCHDOG_PERIOD_MS,
                    WATCHDOG_PERIOD_MS,
                    TimeUnit.MILLISECONDS
                )

                val limitMs = if (mode == StreamMode.SOS) MAX_SOS_MS else MAX_PTT_MS
                val encoded = ByteArray(ENCODED_FRAME_BYTES)
                val pcm = ByteArray(FRAME_SIZE_BYTES)
                var prebuffered = 0

                while (running) {
                    if (SystemClock.elapsedRealtime() - startedAt > limitMs) break
                    val read = input.read(encoded, 0, encoded.size)
                    if (read < 0) {
                        lineClosedBySender = true // sender released the key: end of stream
                        break
                    }
                    if (read == 0) continue
                    lastDataAt = SystemClock.elapsedRealtime()

                    MuLawCodec.decode(encoded, read, pcm)
                    val pcmBytes = read * 2
                    var offset = 0
                    while (offset < pcmBytes && running) {
                        val written = track.write(pcm, offset, pcmBytes - offset)
                        if (written < 0) {
                            running = false
                            break
                        }
                        offset += written
                    }
                    framesWritten += read

                    if (!started) {
                        prebuffered += pcmBytes
                        if (prebuffered >= PREBUFFER_BYTES) {
                            track.play()
                            started = true
                        }
                    }
                    val pending = framesWritten - track.playbackHeadPosition
                    latencyMs = if (pending > 0) ((pending * 1000L) / SAMPLE_RATE).toInt() else 0
                    _bufferLatencyMs.value = latencyMs
                }
            } catch (e: IOException) {
                Log.d(TAG, "Playback stream closed: ${e.message}")
            } catch (e: Exception) {
                Log.e(TAG, "Playback loop failed", e)
            } finally {
                watchdog?.cancel(false)
                try { input.close() } catch (_: IOException) { }
                // A line closed by the sender plays out its tail; a hard stop cuts immediately
                track?.let { releaseTrack(it, started, framesWritten, lineClosedBySender) }
                focus?.let { audioManager.abandonAudioFocusRequest(it) }
                volumeBoost?.release() // restores the user's previous volume
                _bufferLatencyMs.value = 0
                onReceiverFinished(this)
            }
        }
    }

    private fun buildAudioTrack(attributes: AudioAttributes): AudioTrack? {
        return try {
            val minBuf = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_OUT, AUDIO_ENCODING)
            val bufSize = maxOf(minBuf, TRACK_BUFFER_BYTES)
            val format = AudioFormat.Builder()
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(CHANNEL_OUT)
                .setEncoding(AUDIO_ENCODING)
                .build()
            val track = AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()
            // Route to the physical loudspeaker, not the earpiece / a headset
            val speaker = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            if (speaker != null) track.setPreferredDevice(speaker)
            track
        } catch (e: Exception) {
            Log.e(TAG, "AudioTrack initialisation failed", e)
            null
        }
    }

    private fun releaseTrack(track: AudioTrack, started: Boolean, framesWritten: Long, drain: Boolean) {
        try {
            if (drain) {
                if (!started) track.play()
                track.stop() // streaming mode: plays out everything already written
                val deadline = SystemClock.elapsedRealtime() + DRAIN_TIMEOUT_MS
                while (SystemClock.elapsedRealtime() < deadline && track.playbackHeadPosition < framesWritten) {
                    Thread.sleep(10)
                }
            } else {
                track.pause()
                track.flush()
            }
        } catch (_: Exception) {
            // best effort
        } finally {
            try { track.release() } catch (_: Exception) { }
        }
    }

    private fun requestFocus(attributes: AudioAttributes, mode: StreamMode): AudioFocusRequest? {
        return try {
            val gain = if (mode == StreamMode.SOS) {
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE
            } else {
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            }
            val request = AudioFocusRequest.Builder(gain)
                .setAudioAttributes(attributes)
                .setOnAudioFocusChangeListener { }
                .build()
            audioManager.requestAudioFocus(request) // we play regardless of the answer
            request
        } catch (e: Exception) {
            null
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Volume override (max system stream volume while a stream is being received)
    // ---------------------------------------------------------------------------------------------

    /**
     * Raises one audio stream to its maximum and restores the user's level once the last holder
     * released it, so overlapping streams never leave the device stuck at full volume.
     */
    private inner class VolumeBoost(private val streamType: Int) {
        private val lock = Any()
        private var refs = 0
        private var savedVolume = -1

        fun acquire(): Boolean {
            return try {
                synchronized(lock) {
                    if (refs == 0) {
                        savedVolume = audioManager.getStreamVolume(streamType)
                        audioManager.setStreamVolume(streamType, audioManager.getStreamMaxVolume(streamType), 0)
                    }
                    refs++
                }
                true
            } catch (e: SecurityException) {
                // e.g. Do-Not-Disturb policy: playback still works at the current level
                Log.w(TAG, "Cannot override volume of stream $streamType", e)
                false
            }
        }

        fun release() {
            synchronized(lock) {
                if (refs == 0) return
                refs--
                if (refs == 0 && savedVolume >= 0) {
                    try {
                        audioManager.setStreamVolume(streamType, savedVolume, 0)
                    } catch (e: SecurityException) {
                        Log.w(TAG, "Cannot restore volume of stream $streamType", e)
                    }
                    savedVolume = -1
                }
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // State
    // ---------------------------------------------------------------------------------------------

    private fun refreshStateLocked() {
        val tx = transmitSession
        _transmitMode.value = tx?.mode
        _transmitEndpoint.value = if (tx != null && tx.mode == StreamMode.PTT) tx.targetEndpointId else null
        _sosIncoming.value = receivers.values.any { it.mode == StreamMode.SOS }
        _streamState.value = when {
            tx != null -> StreamState.Transmitting
            receivers.isNotEmpty() -> StreamState.Receiving(receivers.values.last().label)
            else -> StreamState.Idle
        }
    }

    @Synchronized
    fun release() {
        transmitSession?.requestStop()
        for (session in receivers.values.toList()) session.requestStop()
    }
}

private fun InputStream.readFully(buffer: ByteArray, length: Int): Boolean {
    var offset = 0
    while (offset < length) {
        val n = read(buffer, offset, length - offset)
        if (n < 0) return false
        offset += n
    }
    return true
}

/** ITU-T G.711 mu-law: 16-bit PCM <-> 8-bit, little-endian PCM byte order. */
private object MuLawCodec {
    private const val BIAS = 0x84
    private const val CLIP = 32635

    private val decodeTable = ShortArray(256) { index ->
        val u = index.inv() and 0xFF
        val sign = u and 0x80
        val exponent = (u shr 4) and 0x07
        val mantissa = u and 0x0F
        val magnitude = (((mantissa shl 3) + BIAS) shl exponent) - BIAS
        (if (sign != 0) -magnitude else magnitude).toShort()
    }

    fun encode(pcm: ByteArray, pcmLength: Int): ByteArray {
        val samples = pcmLength / 2
        val out = ByteArray(samples)
        for (i in 0 until samples) {
            val sample = (pcm[2 * i].toInt() and 0xFF) or (pcm[2 * i + 1].toInt() shl 8)
            out[i] = encodeSample(sample)
        }
        return out
    }

    fun decode(encoded: ByteArray, length: Int, pcmOut: ByteArray) {
        for (i in 0 until length) {
            val value = decodeTable[encoded[i].toInt() and 0xFF].toInt()
            pcmOut[2 * i] = (value and 0xFF).toByte()
            pcmOut[2 * i + 1] = (value shr 8).toByte()
        }
    }

    private fun encodeSample(input: Int): Byte {
        var sample = input
        val sign = (sample shr 8) and 0x80
        if (sign != 0) sample = -sample
        if (sample > CLIP) sample = CLIP
        sample += BIAS
        var exponent = 7
        var mask = 0x4000
        while (exponent > 0 && (sample and mask) == 0) {
            exponent--
            mask = mask shr 1
        }
        val mantissa = (sample shr (exponent + 3)) and 0x0F
        return (sign or (exponent shl 4) or mantissa).inv().toByte()
    }
}
