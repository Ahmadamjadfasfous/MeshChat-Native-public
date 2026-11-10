package com.meshchat.app.presentation.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val CyberBg = Color(0xFF0B0B0F)
val CyberSurface = Color(0xFF14141E)
val CyberSurfaceVariant = Color(0xFF1E1E2E)
val NeonCyan = Color(0xFF00E5FF)
val NeonCyanGlow = Color(0x3300E5FF)
val ElectricCrimson = Color(0xFFFF0055)
val CrimsonGlow = Color(0x33FF0055)
val MatrixGreen = Color(0xFF00FF66)
val CyberTextPrimary = Color(0xFFF0F3F8)
val CyberTextSecondary = Color(0xFF8C9BAE)
val CyberBorder = Color(0xFF25293A)

private val DarkColorScheme = darkColorScheme(
    primary = NeonCyan,
    onPrimary = Color.Black,
    primaryContainer = Color(0xFF00363D),
    onPrimaryContainer = NeonCyan,
    secondary = ElectricCrimson,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF490016),
    onSecondaryContainer = ElectricCrimson,
    tertiary = MatrixGreen,
    background = CyberBg,
    onBackground = CyberTextPrimary,
    surface = CyberSurface,
    onSurface = CyberTextPrimary,
    surfaceVariant = CyberSurfaceVariant,
    onSurfaceVariant = CyberTextSecondary,
    outline = CyberBorder
)

@Composable
fun MeshChatTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        content = content
    )
}
