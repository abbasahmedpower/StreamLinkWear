package com.streamlink.shared.util

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.SharedPreferencesMigration
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class SettingSyncState { IDLE, PENDING, CONFIRMED, FAILED }

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
    name = "streamlink_settings_datastore",
    produceMigrations = { context ->
        listOf(SharedPreferencesMigration(context, "streamlink_settings"))
    }
)

class SystemSettingsStore private constructor(private val context: Context) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val KEY_DYNAMIC_FPS = booleanPreferencesKey("dynamic_fps")
    private val KEY_PRIVACY_BLACKOUT = booleanPreferencesKey("privacy_blackout")
    private val KEY_IMU_GESTURES = booleanPreferencesKey("imu_gestures")
    private val KEY_INSTANT_SYNC = booleanPreferencesKey("instant_sync")
    private val KEY_WATCH_NAME = stringPreferencesKey("watch_name")
    private val KEY_WATCH_IP = stringPreferencesKey("watch_ip")
    private val KEY_THEME_MODE = stringPreferencesKey("theme_mode")

    val isDynamicFpsEnabled: StateFlow<Boolean> = context.dataStore.data
        .map { it[KEY_DYNAMIC_FPS] ?: true }
        .stateIn(scope, SharingStarted.Eagerly, true)

    val isPrivacyBlackoutEnabled: StateFlow<Boolean> = context.dataStore.data
        .map { it[KEY_PRIVACY_BLACKOUT] ?: false }
        .stateIn(scope, SharingStarted.Eagerly, false)

    val isImuGesturesEnabled: StateFlow<Boolean> = context.dataStore.data
        .map { it[KEY_IMU_GESTURES] ?: false }
        .stateIn(scope, SharingStarted.Eagerly, false)

    val isInstantSyncEnabled: StateFlow<Boolean> = context.dataStore.data
        .map { it[KEY_INSTANT_SYNC] ?: true }
        .stateIn(scope, SharingStarted.Eagerly, true)

    val connectedWatchName: StateFlow<String> = context.dataStore.data
        .map { it[KEY_WATCH_NAME] ?: "" }
        .stateIn(scope, SharingStarted.Eagerly, "")

    val connectedWatchIp: StateFlow<String> = context.dataStore.data
        .map { it[KEY_WATCH_IP] ?: "" }
        .stateIn(scope, SharingStarted.Eagerly, "")

    val themeMode: StateFlow<String> = context.dataStore.data
        .map { it[KEY_THEME_MODE] ?: "SYSTEM" }
        .stateIn(scope, SharingStarted.Eagerly, "SYSTEM")

    private val _syncState = MutableStateFlow(SettingSyncState.IDLE)
    val syncState: StateFlow<SettingSyncState> = _syncState.asStateFlow()

    fun setSyncState(state: SettingSyncState) {
        _syncState.value = state
    }

    fun setDynamicFps(enabled: Boolean) {
        scope.launch { context.dataStore.edit { it[KEY_DYNAMIC_FPS] = enabled } }
    }

    fun setPrivacyBlackout(enabled: Boolean) {
        scope.launch { context.dataStore.edit { it[KEY_PRIVACY_BLACKOUT] = enabled } }
    }

    fun setImuGestures(enabled: Boolean) {
        scope.launch { context.dataStore.edit { it[KEY_IMU_GESTURES] = enabled } }
    }

    fun setInstantSync(enabled: Boolean) {
        scope.launch { context.dataStore.edit { it[KEY_INSTANT_SYNC] = enabled } }
    }

    fun setConnectedWatch(name: String, ip: String) {
        scope.launch {
            context.dataStore.edit {
                it[KEY_WATCH_NAME] = name
                it[KEY_WATCH_IP] = ip
            }
        }
    }

    fun clearConnectedWatch() {
        scope.launch {
            context.dataStore.edit {
                it.remove(KEY_WATCH_NAME)
                it.remove(KEY_WATCH_IP)
            }
        }
    }

    fun setThemeMode(mode: String) {
        scope.launch { context.dataStore.edit { it[KEY_THEME_MODE] = mode } }
    }

    companion object {
        @Volatile private var instance: SystemSettingsStore? = null
        fun get(context: Context): SystemSettingsStore =
            instance ?: synchronized(this) {
                instance ?: SystemSettingsStore(context.applicationContext).also { instance = it }
            }
    }
}
