package com.meshchat.app.presentation

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.core.content.ContextCompat
import com.meshchat.app.presentation.theme.MeshChatTheme
import com.meshchat.app.presentation.ui.LiveChatScreen
import com.meshchat.app.presentation.ui.DashboardScreen
import com.meshchat.app.presentation.viewmodel.MeshViewModel
import com.meshchat.app.service.MeshForegroundService
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val viewModel: MeshViewModel by viewModels()
    private var serviceStarted = false

    // Peer chat requested by a notification tap (consumed by the Compose tree)
    private val launchPeerId = mutableStateOf<String?>(null)

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        // POST_NOTIFICATIONS is optional: the foreground service runs even when it is denied
        val essentialGranted = permissions
            .filterKeys { it != Manifest.permission.POST_NOTIFICATIONS }
            .all { it.value }
        if (essentialGranted) {
            startMeshService()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleLaunchIntent(intent)
        checkAndRequestPermissions()

        setContent {
            val currentLanguage by viewModel.language.collectAsState()
            val layoutDirection = if (currentLanguage == "ar") LayoutDirection.Rtl else LayoutDirection.Ltr

            CompositionLocalProvider(LocalLayoutDirection provides layoutDirection) {
                MeshChatTheme {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        var currentDestination by remember { mutableStateOf<NavigationDestination>(NavigationDestination.Dashboard) }

                        val requestedPeer = launchPeerId.value
                        LaunchedEffect(requestedPeer) {
                            if (requestedPeer != null) {
                                currentDestination = NavigationDestination.Chat(requestedPeer)
                                launchPeerId.value = null
                            }
                        }

                        // System back from a chat returns to the peer directory
                        BackHandler(enabled = currentDestination is NavigationDestination.Chat) {
                            currentDestination = NavigationDestination.Dashboard
                        }

                        Crossfade(targetState = currentDestination, label = "ScreenTransition") { destination ->
                            when (destination) {
                                is NavigationDestination.Dashboard -> {
                                    DashboardScreen(
                                        viewModel = viewModel,
                                        onNavigateToChat = { peerId ->
                                            currentDestination = NavigationDestination.Chat(peerId)
                                        }
                                    )
                                }

                                is NavigationDestination.Chat -> {
                                    LiveChatScreen(
                                        peerId = destination.peerId,
                                        viewModel = viewModel,
                                        onBack = { currentDestination = NavigationDestination.Dashboard }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLaunchIntent(intent)
    }

    private fun handleLaunchIntent(intent: Intent?) {
        if (intent == null) return
        intent.getStringExtra(EXTRA_PEER_ID)?.let { launchPeerId.value = it }
        // Only an SOS alert may light up the screen / show over the keyguard
        if (intent.getBooleanExtra(EXTRA_SOS, false)) showOverLockScreen()
    }

    @Suppress("DEPRECATION")
    private fun showOverLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
    }

    private fun checkAndRequestPermissions() {
        val requiredPermissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.RECORD_AUDIO
        )

        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
            requiredPermissions.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            requiredPermissions.add(Manifest.permission.BLUETOOTH_SCAN)
            requiredPermissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            requiredPermissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requiredPermissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
            requiredPermissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missing = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        val essentialMissing = missing.any { it != Manifest.permission.POST_NOTIFICATIONS }
        if (missing.isEmpty()) {
            startMeshService()
        } else {
            // Ask for everything missing; the callback starts the service once the essentials are granted
            requestPermissionLauncher.launch(missing.toTypedArray())
            if (!essentialMissing) startMeshService()
        }
    }

    private fun startMeshService() {
        if (serviceStarted) return
        serviceStarted = true
        val intent = Intent(this, MeshForegroundService::class.java).apply {
            action = MeshForegroundService.ACTION_START
        }
        ContextCompat.startForegroundService(this, intent)
    }

    companion object {
        const val EXTRA_PEER_ID = "com.meshchat.app.EXTRA_PEER_ID"
        const val EXTRA_SOS = "com.meshchat.app.EXTRA_SOS"
    }

    sealed class NavigationDestination {
        object Dashboard : NavigationDestination()
        data class Chat(val peerId: String) : NavigationDestination()
    }
}
