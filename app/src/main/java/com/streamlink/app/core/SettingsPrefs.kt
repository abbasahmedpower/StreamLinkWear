package com.streamlink.app.core

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.SharedPreferencesMigration
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.streamlink.shared.util.dataStore

class SettingsPrefs private constructor(private val context: Context) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val QUALITY_KEY = stringPreferencesKey("quality")
    private val BUFFER_JITTER_KEY = intPreferencesKey("buffer_jitter_ms")

    val quality: StateFlow<com.streamlink.shared.QualityMode> = context.dataStore.data
        .map { prefs -> com.streamlink.shared.QualityMode.fromName(prefs[QUALITY_KEY]) }
        .stateIn(scope, SharingStarted.Eagerly, com.streamlink.shared.QualityMode.BALANCED)

    val bufferJitterMs: StateFlow<Int> = context.dataStore.data
        .map { prefs -> prefs[BUFFER_JITTER_KEY] ?: 150 }
        .stateIn(scope, SharingStarted.Eagerly, 150)

    fun setQuality(q: com.streamlink.shared.QualityMode) {
        scope.launch {
            context.dataStore.edit { prefs ->
                prefs[QUALITY_KEY] = q.name
            }
        }
    }

    fun setBufferJitterMs(ms: Int) {
        val clamped = ms.coerceIn(0, 1000)
        scope.launch {
            context.dataStore.edit { prefs ->
                prefs[BUFFER_JITTER_KEY] = clamped
            }
        }
    }

    companion object {
        @Volatile private var instance: SettingsPrefs? = null
        fun get(context: Context): SettingsPrefs =
            instance ?: synchronized(this) { instance ?: SettingsPrefs(context).also { instance = it } }
    }
}
