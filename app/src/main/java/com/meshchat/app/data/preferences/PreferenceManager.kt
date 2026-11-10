package com.meshchat.app.data.preferences

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.meshchat.app.domain.mesh.PeerUsername
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "meshchat_settings")

@Singleton
class PreferenceManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        val KEY_LANGUAGE = stringPreferencesKey("app_language") // "ar" or "en"
        val KEY_NODE_ID = stringPreferencesKey("local_node_id")
        val KEY_NODE_NAME = stringPreferencesKey("local_node_name")
        val KEY_ROUTING_ENABLED = booleanPreferencesKey("multi_hop_routing_enabled")
        val KEY_AUDIO_LATENCY_MODE = stringPreferencesKey("audio_latency_mode")
    }

    val languageFlow: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[KEY_LANGUAGE] ?: "ar" // Default Arabic (RTL) per architectural specification
    }

    // Emits the persisted node id (creating and saving it on first run) so the UI and the
    // background service always agree on the same id.
    val nodeIdFlow: Flow<String> = context.dataStore.data
        .map { preferences -> preferences[KEY_NODE_ID] }
        .onStart { getOrCreateNodeId() }
        .filterNotNull()

    /** First field of the username template (default: the device manufacturer, e.g. "Samsung"). */
    val nodeNameFlow: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[KEY_NODE_NAME] ?: defaultDisplayName()
    }

    /** Second field of the username template: the hardware model. */
    val deviceModel: String
        get() = PeerUsername.sanitizeField(Build.MODEL).ifBlank { "Android" }

    private fun defaultDisplayName(): String =
        PeerUsername.sanitizeField(Build.MANUFACTURER)
            .replaceFirstChar { it.titlecase(Locale.ROOT) }
            .ifBlank { "Mesh User" }

    val routingEnabledFlow: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[KEY_ROUTING_ENABLED] ?: true
    }

    suspend fun setLanguage(languageCode: String) {
        context.dataStore.edit { preferences ->
            preferences[KEY_LANGUAGE] = languageCode
        }
    }

    suspend fun setNodeName(name: String) {
        val clean = PeerUsername.sanitizeField(name).ifBlank { defaultDisplayName() }
        context.dataStore.edit { preferences ->
            preferences[KEY_NODE_NAME] = clean
        }
    }

    suspend fun setRoutingEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[KEY_ROUTING_ENABLED] = enabled
        }
    }

    suspend fun getOrCreateNodeId(): String {
        var id = ""
        context.dataStore.edit { preferences ->
            val existing = preferences[KEY_NODE_ID]
            if (existing != null) {
                id = existing
            } else {
                val newId = "node_" + UUID.randomUUID().toString().substring(0, 8)
                preferences[KEY_NODE_ID] = newId
                id = newId
            }
        }
        return id
    }

    /**
     * Applies dynamic Locale and Configuration changes without requiring activity restart.
     */
    fun applyLocaleToContext(baseContext: Context, languageCode: String): Context {
        val locale = Locale(languageCode)
        Locale.setDefault(locale)
        val config = Configuration(baseContext.resources.configuration)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            config.setLocales(LocaleList(locale))
        } else {
            config.setLocale(locale)
        }
        config.setLayoutDirection(locale)
        return baseContext.createConfigurationContext(config)
    }
}
