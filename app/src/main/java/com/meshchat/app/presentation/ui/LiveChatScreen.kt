package com.meshchat.app.presentation.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meshchat.app.R
import com.meshchat.app.data.local.entity.MessageEntity
import com.meshchat.app.data.local.entity.MessageTypes
import com.meshchat.app.data.local.entity.PeerEntity
import com.meshchat.app.data.local.entity.SecurityStatus
import com.meshchat.app.domain.audio.WalkieTalkieEngine
import com.meshchat.app.domain.mesh.HandshakeManager
import com.meshchat.app.domain.mesh.HandshakePolicy
import com.meshchat.app.domain.mesh.PeerUsername
import com.meshchat.app.presentation.theme.CyberBorder
import com.meshchat.app.presentation.theme.CyberSurface
import com.meshchat.app.presentation.theme.CyberSurfaceVariant
import com.meshchat.app.presentation.theme.CyberTextPrimary
import com.meshchat.app.presentation.theme.CyberTextSecondary
import com.meshchat.app.presentation.theme.ElectricCrimson
import com.meshchat.app.presentation.theme.MatrixGreen
import com.meshchat.app.presentation.theme.NeonCyan
import com.meshchat.app.presentation.viewmodel.MeshViewModel
import com.meshchat.app.presentation.viewmodel.NetworkPeer
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val BANNER_KEY = "verify_banner"

/** One row of the chat timeline: a stored message, or the inline verification banner. */
private sealed class TimelineItem(val key: String) {
    class Message(val msg: MessageEntity) : TimelineItem("m_${msg.id}")
    object VerifyBanner : TimelineItem(BANNER_KEY)
}

/** The banner is the last row of the timeline so it appears inline, right below the peer's reply. */
private fun buildTimeline(messages: List<MessageEntity>, showBanner: Boolean): List<TimelineItem> {
    val items = ArrayList<TimelineItem>(messages.size + 1)
    messages.forEach { items.add(TimelineItem.Message(it)) }
    if (showBanner) items.add(TimelineItem.VerifyBanner)
    return items
}

/**
 * Conversation workspace of one peer. It always opens immediately and unverified: the first message
 * travels as raw plain text, the peer answers in plain text, and only then - on the screen of the
 * sender (Peer A) - the inline banner "Verify Peer Profile. [Activate Asymmetric ECDH Encryption]"
 * flashes. Clicking it runs the asymmetric key exchange; from then on everything is AES-GCM.
 * There are no dialogs in this flow.
 */
@Composable
fun LiveChatScreen(
    peerId: String,
    viewModel: MeshViewModel,
    onBack: () -> Unit
) {
    val messages by remember(peerId) { viewModel.messagesFor(peerId) }.collectAsState(initial = emptyList())
    val peer by remember(peerId) { viewModel.observePeer(peerId) }.collectAsState(initial = null)
    val link by remember(peerId) { viewModel.observeLink(peerId) }.collectAsState(initial = null)
    val phases by viewModel.handshakePhases.collectAsState()
    val streamState by viewModel.walkieTalkieState.collectAsState()

    val peerValue = peer
    val linkValue = link
    val securityStatus = peerValue?.securityStatus ?: SecurityStatus.UNVERIFIED
    val handshakePhase = phases[peerId]

    // Only the sender of the first message, after the peer's plain-text reply, ever gets the banner
    val showBanner = remember(securityStatus, messages) {
        HandshakePolicy.shouldOfferVerification(securityStatus, messages)
    }

    var inputText by remember(peerId) { mutableStateOf("") }
    var pttActive by remember(peerId) { mutableStateOf(false) }
    var pttHint by remember(peerId) { mutableStateOf<String?>(null) }

    val timeline = remember(messages, showBanner) { buildTimeline(messages, showBanner) }
    val listState = rememberLazyListState()
    var initialScrollDone by remember(peerId) { mutableStateOf(false) }

    // Scroll anchor locked to the active message flow: the first load jumps to the newest row
    // without animation, every later row (sent, received, banner, system notice) glides to the end.
    LaunchedEffect(timeline.size, timeline.lastOrNull()?.key) {
        if (timeline.isNotEmpty()) {
            if (!initialScrollDone) {
                listState.scrollToItem(timeline.lastIndex)
                initialScrollDone = true
            } else {
                listState.animateScrollToItem(timeline.lastIndex)
            }
        }
    }

    LaunchedEffect(pttHint) {
        if (pttHint != null) {
            delay(2500)
            pttHint = null
        }
    }

    // Never leave the microphone open if the screen disappears while the PTT key is held
    DisposableEffect(peerId) {
        onDispose { viewModel.stopPtt() }
    }

    val canSend = inputText.isNotBlank()
    val sendCurrentInput = {
        if (inputText.isNotBlank()) {
            viewModel.sendMessage(peerId, inputText)
            inputText = ""
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .imePadding()
    ) {
        ChatTopBar(
            peerId = peerId,
            peer = peerValue,
            link = linkValue,
            onBack = onBack
        )

        val currentStream = streamState
        if (currentStream is WalkieTalkieEngine.StreamState.Receiving) {
            StatusStrip(
                text = stringResource(R.string.ptt_receiving, currentStream.senderPeerId),
                color = NeonCyan
            )
        }
        if (pttActive) {
            StatusStrip(
                text = stringResource(R.string.ptt_unicast_transmitting, peerValue?.displayName ?: peerId.take(12)),
                color = ElectricCrimson
            )
        }

        // Message timeline
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(items = timeline, key = { it.key }) { item ->
                when (item) {
                    is TimelineItem.Message -> {
                        if (item.msg.type == MessageTypes.SYSTEM) {
                            SystemNotice(text = item.msg.text)
                        } else {
                            ChatBubble(msg = item.msg)
                        }
                    }

                    is TimelineItem.VerifyBanner -> VerificationBanner(
                        phase = handshakePhase,
                        onActivate = { viewModel.activateEncryption(peerId) },
                        onDismissFailure = { viewModel.dismissHandshakeFailure(peerId) }
                    )
                }
            }
        }

        pttHint?.let { hint ->
            Text(
                text = hint,
                color = ElectricCrimson,
                fontSize = 11.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(CyberSurface)
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }

        // Input bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(CyberSurface)
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Hold-to-talk: private (unicast) PTT bound to this peer's endpoint only
            val pttAvailable = linkValue?.isDirect == true
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(if (pttActive) ElectricCrimson else CyberSurfaceVariant)
                    .border(1.dp, ElectricCrimson, CircleShape)
                    .alpha(if (pttAvailable) 1f else 0.5f)
                    .pointerInput(peerId) {
                        detectTapGestures(
                            onPress = {
                                val started = viewModel.startPrivatePtt(peerId)
                                if (started) {
                                    pttActive = true
                                    pttHint = null
                                } else {
                                    pttHint = "Private PTT needs a direct link to this peer and microphone permission."
                                }
                                try {
                                    tryAwaitRelease()
                                } finally {
                                    if (started) viewModel.stopPtt()
                                    pttActive = false
                                }
                            }
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Mic,
                    contentDescription = "Hold to talk",
                    tint = if (pttActive) Color.White else ElectricCrimson
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            OutlinedTextField(
                value = inputText,
                onValueChange = { inputText = it },
                modifier = Modifier.weight(1f),
                placeholder = {
                    Text(
                        text = stringResource(R.string.chat_input_placeholder),
                        color = CyberTextSecondary,
                        fontSize = 13.sp
                    )
                },
                colors = chatFieldColors(),
                shape = RoundedCornerShape(24.dp),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { sendCurrentInput() })
            )

            Spacer(modifier = Modifier.width(8.dp))

            IconButton(
                onClick = { sendCurrentInput() },
                enabled = canSend,
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(NeonCyan)
                    .alpha(if (canSend) 1f else 0.4f)
            ) {
                Icon(
                    imageVector = Icons.Default.Send,
                    contentDescription = "Send",
                    tint = Color.Black
                )
            }
        }
    }
}

@Composable
private fun ChatTopBar(
    peerId: String,
    peer: PeerEntity?,
    link: NetworkPeer?,
    onBack: () -> Unit
) {
    val encrypted = peer?.isEncrypted == true
    val title = peer?.displayName ?: peerId.take(16)
    val subtitle = if (peer != null) {
        "${peer.deviceModel} · ${PeerUsername.shortId(peer.id)}"
    } else {
        PeerUsername.shortId(peerId)
    }
    val hops = link?.hopCount
    val linkLabel = when {
        link?.isDirect == true -> "DIRECT LINK"
        hops != null -> "MULTI-HOP · $hops HOPS"
        else -> "OFFLINE"
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(CyberSurface)
            .padding(horizontal = 8.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = Icons.Default.ArrowBack,
                contentDescription = "Back",
                tint = NeonCyan
            )
        }

        Spacer(modifier = Modifier.width(4.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = CyberTextPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "$subtitle · $linkLabel",
                color = CyberTextSecondary,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = if (encrypted) peer?.securityStatus ?: SecurityStatus.ENCRYPTED else "UNVERIFIED · PLAIN TEXT",
                color = if (encrypted) MatrixGreen else ElectricCrimson,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
        }

        Icon(
            imageVector = if (encrypted) Icons.Default.Lock else Icons.Default.LockOpen,
            contentDescription = if (encrypted) "Encrypted" else "Not encrypted",
            tint = if (encrypted) MatrixGreen else ElectricCrimson,
            modifier = Modifier
                .padding(end = 12.dp)
                .size(20.dp)
        )
    }
}

@Composable
private fun StatusStrip(text: String, color: Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(CyberSurfaceVariant)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.VolumeUp,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = text,
            color = color,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
private fun chatFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = NeonCyan,
    unfocusedBorderColor = CyberBorder,
    focusedTextColor = CyberTextPrimary,
    unfocusedTextColor = CyberTextPrimary,
    focusedLabelColor = NeonCyan,
    unfocusedLabelColor = CyberTextSecondary,
    cursorColor = NeonCyan
)

/**
 * Inline action banner shown on the sender's (Peer A) timeline only. It flashes until it is used.
 * A single tap anywhere on the banner starts the Keystore-backed ECDH key exchange.
 */
@Composable
private fun VerificationBanner(
    phase: HandshakeManager.Phase?,
    onActivate: () -> Unit,
    onDismissFailure: () -> Unit
) {
    val negotiating = phase is HandshakeManager.Phase.Negotiating
    val failed = phase as? HandshakeManager.Phase.Failed

    val transition = rememberInfiniteTransition(label = "BannerFlash")
    val flash by transition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 650, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "FlashAlpha"
    )

    val shape = RoundedCornerShape(14.dp)
    val accent = if (failed != null) ElectricCrimson else NeonCyan
    val borderAlpha = if (phase == null) flash else 1f

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(CyberSurfaceVariant)
            .border(2.dp, accent.copy(alpha = borderAlpha), shape)
            .clickable(enabled = !negotiating, role = Role.Button, onClick = onActivate)
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Default.VerifiedUser,
                contentDescription = null,
                tint = accent.copy(alpha = borderAlpha),
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = buildAnnotatedString {
                    append(HandshakePolicy.BANNER_MESSAGE)
                    append(" ")
                    withStyle(
                        SpanStyle(
                            color = accent,
                            fontWeight = FontWeight.Bold,
                            textDecoration = TextDecoration.Underline
                        )
                    ) {
                        append(
                            if (failed != null) "[Retry Asymmetric ECDH Encryption]" else HandshakePolicy.BANNER_ACTION
                        )
                    }
                },
                color = CyberTextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
        }

        when {
            negotiating -> {
                Spacer(modifier = Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = NeonCyan
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Negotiating asymmetric keys with the peer...",
                        color = CyberTextSecondary,
                        fontSize = 12.sp
                    )
                }
            }

            failed != null -> {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = when (failed.reason) {
                        HandshakeManager.FailReason.TIMEOUT -> "The peer did not answer the key exchange in time."
                        HandshakeManager.FailReason.PEER_UNREACHABLE -> "The peer is not reachable right now."
                        HandshakeManager.FailReason.CRYPTO_ERROR -> "The key exchange failed on this device."
                    },
                    color = ElectricCrimson,
                    fontSize = 12.sp
                )
                TextButton(onClick = onDismissFailure) {
                    Text(text = "Dismiss", color = CyberTextSecondary, fontSize = 12.sp)
                }
            }

            else -> {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "The peer answered in plain text. If this is the person you expect, upgrade this chat to " +
                        "asymmetric ECDH key exchange with an AES-GCM session key.",
                    color = CyberTextSecondary,
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
private fun SystemNotice(text: String) {
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = CyberTextSecondary,
            fontSize = 11.sp,
            fontStyle = FontStyle.Italic,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(CyberSurface)
                .padding(horizontal = 12.dp, vertical = 6.dp)
        )
    }
}

@Composable
fun ChatBubble(msg: MessageEntity) {
    val isMe = msg.isFromMe
    val isPlain = msg.type == MessageTypes.TEXT_PLAIN
    val timeFormatted = remember(msg.timestamp) {
        val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
        sdf.format(Date(msg.timestamp))
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isMe) Arrangement.End else Arrangement.Start
    ) {
        Column(
            horizontalAlignment = if (isMe) Alignment.End else Alignment.Start,
            modifier = Modifier.widthIn(max = 300.dp)
        ) {
            Box(
                modifier = Modifier
                    .clip(
                        RoundedCornerShape(
                            topStart = 14.dp,
                            topEnd = 14.dp,
                            bottomStart = if (isMe) 14.dp else 2.dp,
                            bottomEnd = if (isMe) 2.dp else 14.dp
                        )
                    )
                    .background(if (isMe) CyberSurfaceVariant else CyberSurface)
                    .border(
                        width = 1.dp,
                        color = if (isMe) NeonCyan.copy(alpha = 0.5f) else CyberBorder,
                        shape = RoundedCornerShape(14.dp)
                    )
                    .padding(12.dp)
            ) {
                Column {
                    Text(
                        text = msg.text,
                        color = CyberTextPrimary,
                        fontSize = 14.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = if (isPlain) Icons.Default.LockOpen else Icons.Default.Lock,
                                contentDescription = if (isPlain) "Unencrypted" else "Encrypted",
                                tint = if (isPlain) ElectricCrimson else MatrixGreen,
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(modifier = Modifier.width(3.dp))
                            Text(
                                text = if (isPlain) "PLAIN TEXT" else "AES-GCM",
                                color = if (isPlain) ElectricCrimson else MatrixGreen,
                                fontSize = 9.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = timeFormatted,
                            color = CyberTextSecondary,
                            fontSize = 10.sp
                        )
                    }
                }
            }
        }
    }
}
