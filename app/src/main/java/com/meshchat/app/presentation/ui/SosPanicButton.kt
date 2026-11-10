package com.meshchat.app.presentation.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meshchat.app.domain.audio.WalkieTalkieEngine
import com.meshchat.app.presentation.theme.ElectricCrimson
import com.meshchat.app.presentation.viewmodel.MeshViewModel
import kotlinx.coroutines.delay

/**
 * Global panic control for the main dashboard.
 * Long-press arms the SOS broadcast (prevents pocket triggers); a single tap stops it.
 */
@Composable
fun SosPanicButton(
    viewModel: MeshViewModel,
    modifier: Modifier = Modifier
) {
    val transmitMode by viewModel.transmitMode.collectAsState()
    val sosIncoming by viewModel.isSosIncoming.collectAsState()
    val active = transmitMode == WalkieTalkieEngine.StreamMode.SOS
    var hint by remember { mutableStateOf<String?>(null) }

    val pulse = rememberInfiniteTransition(label = "SosPulse")
    val pulseAlpha by pulse.animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(450, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "SosPulseAlpha"
    )

    LaunchedEffect(hint) {
        if (hint != null) {
            delay(2500)
            hint = null
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(if (active) ElectricCrimson else ElectricCrimson.copy(alpha = 0.18f))
                .border(1.dp, ElectricCrimson, RoundedCornerShape(12.dp))
                .alpha(if (active || sosIncoming) pulseAlpha else 1f)
                .pointerInput(active) {
                    detectTapGestures(
                        onTap = {
                            if (active) {
                                viewModel.stopSos()
                            } else {
                                hint = "Press and hold to broadcast SOS"
                            }
                        },
                        onLongPress = {
                            if (!active && !viewModel.startSos()) {
                                hint = "SOS needs a connected node and microphone permission"
                            }
                        }
                    )
                }
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = "SOS",
                tint = if (active) Color.White else ElectricCrimson,
                modifier = Modifier.size(28.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = when {
                        active -> "SOS BROADCASTING - TAP TO STOP"
                        sosIncoming -> "INCOMING SOS AUDIO"
                        else -> "EMERGENCY SOS"
                    },
                    color = if (active) Color.White else ElectricCrimson,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = if (active) {
                        "Live microphone audio is playing on every connected node at full volume"
                    } else {
                        "Hold to stream your microphone to ALL connected nodes"
                    },
                    color = if (active) Color.White else Color.LightGray,
                    fontSize = 11.sp
                )
            }
        }
        hint?.let {
            Text(
                text = it,
                color = ElectricCrimson,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 4.dp, start = 4.dp)
            )
        }
    }
}
