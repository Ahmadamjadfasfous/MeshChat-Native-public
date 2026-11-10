package com.meshchat.app.presentation.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meshchat.app.R
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

/**
 * Home screen: mesh radar, emergency SOS and ONE unified directory ("Available Network Peers")
 * that merges direct neighbours and multi-hop remote nodes. The search bar filters that directory
 * instantly by the three-field username (name / model / id). Tapping a peer opens its chat.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DashboardScreen(
    viewModel: MeshViewModel,
    onNavigateToChat: (String) -> Unit
) {
    val networkPeers by viewModel.networkPeers.collectAsState()
    val currentLang by viewModel.language.collectAsState()
    val localNodeId by viewModel.localNodeId.collectAsState()
    val localName by viewModel.localNodeName.collectAsState()

    var query by rememberSaveable { mutableStateOf("") }
    var showRenameDialog by remember { mutableStateOf(false) }

    // Instant filtering: recomputed on every keystroke, no debounce, no I/O
    val filteredPeers = remember(networkPeers, query) {
        networkPeers.filter { PeerUsername.matches(query, it.peer) }
    }
    val reachableCount = networkPeers.count { it.isReachable }
    val directCount = networkPeers.count { it.isDirect }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item(key = "header") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.app_name),
                        color = NeonCyan,
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 1.5.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = stringResource(R.string.tagline),
                        color = CyberTextSecondary,
                        fontSize = 12.sp
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = { showRenameDialog = true },
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(CyberSurfaceVariant)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = stringResource(R.string.peers_edit_name),
                            tint = MatrixGreen
                        )
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    // Dynamic LTR/RTL language switch
                    IconButton(
                        onClick = {
                            val nextLang = if (currentLang == "ar") "en" else "ar"
                            viewModel.setLanguage(nextLang)
                        },
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(CyberSurfaceVariant)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Translate,
                            contentDescription = "Language",
                            tint = NeonCyan
                        )
                    }
                }
            }
        }

        item(key = "radar") {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(CyberSurface)
                    .border(1.dp, CyberBorder, RoundedCornerShape(16.dp)),
                contentAlignment = Alignment.Center
            ) {
                PulsingRadarCanvas(activePeersCount = reachableCount)

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(12.dp)
                ) {
                    Text(
                        text = stringResource(R.string.radar_scanning),
                        color = NeonCyan,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.radar_connected_nodes, reachableCount),
                        color = CyberTextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = stringResource(R.string.peers_direct_remote, directCount, reachableCount - directCount),
                        color = CyberTextSecondary,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = "You: ${PeerUsername.display(localName, viewModel.localDeviceModel, localNodeId)}",
                        color = MatrixGreen,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        // Global emergency panic action (omni-directional SOS broadcast)
        item(key = "sos") {
            SosPanicButton(viewModel = viewModel)
        }

        // Search bar stays pinned while the directory scrolls underneath it
        stickyHeader(key = "directory_header") {
            DirectoryHeader(
                query = query,
                onQueryChange = { query = it },
                shownCount = filteredPeers.size,
                totalCount = networkPeers.size
            )
        }

        if (networkPeers.isEmpty()) {
            item(key = "empty") {
                CenteredNote(stringResource(R.string.peers_empty))
            }
        } else if (filteredPeers.isEmpty()) {
            item(key = "no_match") {
                CenteredNote(stringResource(R.string.peers_no_match, query.trim()))
            }
        } else {
            items(filteredPeers, key = { it.peer.id }) { entry ->
                NetworkPeerCard(
                    entry = entry,
                    onClick = { onNavigateToChat(entry.peer.id) }
                )
            }
        }
    }

    if (showRenameDialog) {
        RenameDialog(
            currentName = localName,
            onConfirm = { newName ->
                viewModel.setDisplayName(newName)
                showRenameDialog = false
            },
            onDismiss = { showRenameDialog = false }
        )
    }
}

@Composable
private fun DirectoryHeader(
    query: String,
    onQueryChange: (String) -> Unit,
    shownCount: Int,
    totalCount: Int
) {
    val focusManager = LocalFocusManager.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(top = 8.dp, bottom = 4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.nav_peers),
                color = CyberTextPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = if (query.isBlank()) "$totalCount" else "$shownCount / $totalCount",
                color = CyberTextSecondary,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = RoundedCornerShape(24.dp),
            placeholder = {
                Text(
                    text = stringResource(R.string.peers_search_hint),
                    color = CyberTextSecondary,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = null,
                    tint = NeonCyan
                )
            },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(
                            imageVector = Icons.Default.Clear,
                            contentDescription = stringResource(R.string.peers_search_clear),
                            tint = CyberTextSecondary
                        )
                    }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
            colors = directoryFieldColors()
        )
    }
}

@Composable
private fun directoryFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = NeonCyan,
    unfocusedBorderColor = CyberBorder,
    focusedTextColor = CyberTextPrimary,
    unfocusedTextColor = CyberTextPrimary,
    focusedLabelColor = NeonCyan,
    unfocusedLabelColor = CyberTextSecondary,
    cursorColor = NeonCyan,
    focusedContainerColor = CyberSurface,
    unfocusedContainerColor = CyberSurface
)

@Composable
private fun CenteredNote(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 32.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = CyberTextSecondary,
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
private fun RenameDialog(
    currentName: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(currentName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CyberSurface,
        title = {
            Text(
                text = stringResource(R.string.peers_edit_name),
                color = CyberTextPrimary,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.peers_edit_name_desc),
                    color = CyberTextSecondary,
                    fontSize = 12.sp
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(PeerUsername.MAX_FIELD_CHARS) },
                    singleLine = true,
                    colors = directoryFieldColors(),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name) },
                enabled = name.isNotBlank()
            ) {
                Text(text = stringResource(R.string.peers_save), color = NeonCyan, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.peers_cancel), color = CyberTextSecondary)
            }
        }
    )
}

@Composable
fun PulsingRadarCanvas(activePeersCount: Int) {
    val infiniteTransition = rememberInfiniteTransition(label = "RadarPulse")
    val pulseFraction by infiniteTransition.animateFloat(
        initialValue = 0.1f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(2800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "Radius"
    )

    Canvas(modifier = Modifier.fillMaxSize()) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val maxRadius = Math.min(size.width, size.height) / 2f * 0.9f

        // Concentric grid rings
        for (i in 1..3) {
            drawCircle(
                color = CyberBorder,
                radius = maxRadius * (i / 3f),
                center = center,
                style = Stroke(width = 1.dp.toPx())
            )
        }

        // Radar crosshairs
        drawLine(
            color = CyberBorder,
            start = Offset(center.x - maxRadius, center.y),
            end = Offset(center.x + maxRadius, center.y),
            strokeWidth = 1.dp.toPx()
        )
        drawLine(
            color = CyberBorder,
            start = Offset(center.x, center.y - maxRadius),
            end = Offset(center.x, center.y + maxRadius),
            strokeWidth = 1.dp.toPx()
        )

        // One blip per reachable node, spread on the rings (more hops = further out)
        val blips = activePeersCount.coerceAtMost(12)
        for (i in 0 until blips) {
            val angle = (i * 2.399963f) // golden angle keeps blips from stacking
            val ring = maxRadius * (0.35f + 0.55f * ((i % 3) / 2f))
            drawCircle(
                color = MatrixGreen.copy(alpha = 0.85f),
                radius = 3.dp.toPx(),
                center = Offset(
                    center.x + ring * kotlin.math.cos(angle),
                    center.y + ring * kotlin.math.sin(angle)
                )
            )
        }

        // Pulsing radar wave
        drawCircle(
            color = NeonCyan.copy(alpha = (1f - pulseFraction) * 0.45f),
            radius = maxRadius * pulseFraction,
            center = center,
            style = Stroke(width = 2.dp.toPx())
        )
    }
}

/**
 * One row of the unified directory. The whole card is the tap target; a direct neighbour and a
 * multi-hop node look the same except for the link badge.
 */
@Composable
fun NetworkPeerCard(
    entry: NetworkPeer,
    onClick: () -> Unit
) {
    val peer = entry.peer
    val dim = if (entry.isReachable) 1f else 0.55f
    val accent = if (peer.isEncrypted) MatrixGreen else NeonCyan

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(dim)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = CyberSurface),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, CyberBorder)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(CyberSurfaceVariant)
                        .border(1.dp, accent, RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = peer.initials,
                        color = CyberTextPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = peer.displayName,
                            color = CyberTextPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Icon(
                            imageVector = if (peer.isEncrypted) Icons.Default.Lock else Icons.Default.LockOpen,
                            contentDescription = peer.securityStatus,
                            tint = if (peer.isEncrypted) MatrixGreen else ElectricCrimson,
                            modifier = Modifier.size(15.dp)
                        )
                    }
                    Text(
                        text = "${peer.deviceModel} · ${PeerUsername.shortId(peer.id)}",
                        color = CyberTextSecondary,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    LinkBadge(entry = entry)
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            Icon(
                imageVector = Icons.Default.Chat,
                contentDescription = stringResource(R.string.chat_send),
                tint = NeonCyan,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

@Composable
private fun LinkBadge(entry: NetworkPeer) {
    val hops = entry.hopCount
    val (label, color) = when {
        hops == null -> stringResource(R.string.peers_offline) to CyberTextSecondary
        entry.isDirect -> stringResource(R.string.peers_direct) to MatrixGreen
        else -> stringResource(R.string.peers_hops, hops) to NeonCyan
    }
    Text(
        text = label,
        color = if (hops == null) CyberTextSecondary else Color.Black,
        fontSize = 9.sp,
        fontWeight = FontWeight.Bold,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(if (hops == null) CyberSurfaceVariant else color)
            .padding(horizontal = 6.dp, vertical = 2.dp)
    )
}
