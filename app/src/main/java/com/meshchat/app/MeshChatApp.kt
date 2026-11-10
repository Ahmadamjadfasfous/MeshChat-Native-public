package com.meshchat.app

import android.app.Activity
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.graphics.Color
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import dagger.hilt.android.HiltAndroidApp
import java.util.concurrent.atomic.AtomicInteger

@HiltAndroidApp
class MeshChatApp : Application() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        registerVisibilityTracker()
    }

    /** Counts started activities so the service knows whether the UI is currently in view. */
    private fun registerVisibilityTracker() {
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) {
                startedActivities.incrementAndGet()
            }
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) {
                if (startedActivities.decrementAndGet() < 0) startedActivities.set(0)
            }
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)

        // 1. Silent ongoing foreground-service channel
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "MeshChat Mesh Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Ongoing peer discovery, multi-hop packet routing, and audio streaming."
                setShowBadge(false)
            }
        )

        // 2. Incoming (encrypted) messages and authentication requests
        val messageSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val messageAudio = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_INSTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_MESSAGES,
                "Secure messages",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "New mesh messages and authentication requests."
                enableLights(true)
                lightColor = Color.CYAN
                enableVibration(true)
                vibrationPattern = MESSAGE_VIBRATION
                setSound(messageSound, messageAudio)
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                setShowBadge(true)
            }
        )

        // 3. Incoming push-to-talk voice streams
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_VOICE,
                "Incoming voice",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Walkie-talkie audio arriving from a nearby node."
                enableLights(true)
                lightColor = Color.MAGENTA
                enableVibration(true)
                vibrationPattern = VOICE_VIBRATION
                setSound(messageSound, messageAudio)
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            }
        )

        // 4. Emergency SOS broadcasts: alarm sound, long vibration, red light, may bypass Do Not Disturb
        val alarmSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        val alarmAudio = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SOS,
                "Emergency SOS",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Emergency audio broadcasts from nearby nodes."
                enableLights(true)
                lightColor = Color.RED
                enableVibration(true)
                vibrationPattern = SOS_VIBRATION
                setSound(alarmSound, alarmAudio)
                setBypassDnd(true) // only effective if the user grants Do Not Disturb access
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
        )
    }

    companion object {
        const val CHANNEL_ID = "meshchat_engine_service"
        const val CHANNEL_MESSAGES = "meshchat_messages_v2"
        const val CHANNEL_VOICE = "meshchat_voice_v2"
        const val CHANNEL_SOS = "meshchat_sos_v2"

        val MESSAGE_VIBRATION = longArrayOf(0, 180, 120, 180)
        val VOICE_VIBRATION = longArrayOf(0, 120, 80, 120, 80, 120)
        val SOS_VIBRATION = longArrayOf(0, 600, 200, 600, 200, 600, 200, 1200)

        private val startedActivities = AtomicInteger(0)

        /** True while at least one MeshChat activity is visible. */
        val isInForeground: Boolean
            get() = startedActivities.get() > 0
    }
}
