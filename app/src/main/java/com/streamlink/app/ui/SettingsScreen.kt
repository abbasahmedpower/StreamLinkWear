package com.streamlink.app.ui

import androidx.compose.material3.ExperimentalMaterial3Api

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.streamlink.app.R
import com.streamlink.app.core.LocaleManager
import com.streamlink.app.core.SettingsPrefs
import com.streamlink.shared.QualityMode
import com.streamlink.app.ui.theme.ForceLtr
import com.streamlink.app.ui.theme.SemanticColors
import com.streamlink.app.ui.theme.ThemeMode
import com.streamlink.shared.GlobalStreamState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    watchIp: String? = null
) {
    val context = LocalContext.current
    val prefs = remember { SettingsPrefs.get(context) }
    val quality by prefs.quality.collectAsState()
    val bufferJitterMs by prefs.bufferJitterMs.collectAsState()
    // ✅ تحقق: نفس النمط المستخدم فعليًا في MainActivity.kt سطر 151 (collectAsStateWithLifecycle)
    val streamState by GlobalStreamState.snapshot.collectAsStateWithLifecycle()
    val isStreaming = streamState.state == GlobalStreamState.State.STREAMING

    var showQualityMenu by remember { mutableStateOf(false) }
    var showLanguageDialog by remember { mutableStateOf(false) }
    var instantSync by remember { mutableStateOf(true) } // TODO: شكلي حاليًا — راجع ملاحظة تحت

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.cd_settings_icon)) }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            item { Spacer(Modifier.height(4.dp)) }

            item {
                SettingsSectionLabel(stringResource(R.string.settings_account_status))
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
                    Row(modifier = Modifier.padding(16.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).background(if (isStreaming) SemanticColors.Excellent else MaterialTheme.colorScheme.outline, CircleShape))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                if (isStreaming) stringResource(R.string.settings_status_streaming) else stringResource(R.string.settings_status_idle),
                                style = MaterialTheme.typography.titleMedium
                            )
                            if (isStreaming) {
                                ForceLtr {
                                    Text(
                                        "${streamState.bitrateKbps} kbps · ${streamState.fps} fps · ${streamState.latencyMs} ms",
                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }

            item {
                SettingsSectionLabel(stringResource(R.string.settings_streaming_settings))
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
                    Column {
                        Box {
                            SettingsRow(
                                title = stringResource(R.string.settings_default_quality),
                                value = quality.label,
                                onClick = { showQualityMenu = true }
                            )
                            DropdownMenu(expanded = showQualityMenu, onDismissRequest = { showQualityMenu = false }) {
                                com.streamlink.shared.QualityMode.entries.forEach { q ->
                                    DropdownMenuItem(
                                        text = { Column {
                                            Text(q.label, style = MaterialTheme.typography.bodyMedium)
                                            Text(q.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        } },
                                        onClick = { prefs.setQuality(q); showQualityMenu = false },
                                        trailingIcon = { if (q == quality) Icon(Icons.Default.Check, contentDescription = null) }
                                    )
                                }
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Row(
                            modifier = Modifier.padding(16.dp).fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(stringResource(R.string.settings_buffer_size), style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    "${bufferJitterMs}ms · " + stringResource(R.string.settings_buffer_recommended),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = { if (bufferJitterMs > 0) prefs.setBufferJitterMs(bufferJitterMs - 50) }) { Text("−", fontWeight = FontWeight.Bold) }
                                IconButton(onClick = { if (bufferJitterMs < 1000) prefs.setBufferJitterMs(bufferJitterMs + 50) }) { Text("+", fontWeight = FontWeight.Bold) }
                            }
                        }
                    }
                }
            }

            item {
                SettingsSectionLabel(stringResource(R.string.settings_appearance))
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(stringResource(R.string.settings_theme_mode), style = MaterialTheme.typography.bodyLarge)
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ThemeChip(stringResource(R.string.settings_theme_system), themeMode == ThemeMode.SYSTEM) { onThemeModeChange(ThemeMode.SYSTEM) }
                            ThemeChip(stringResource(R.string.settings_theme_dark), themeMode == ThemeMode.DARK) { onThemeModeChange(ThemeMode.DARK) }
                            ThemeChip(stringResource(R.string.settings_theme_light), themeMode == ThemeMode.LIGHT) { onThemeModeChange(ThemeMode.LIGHT) }
                        }
                        Spacer(Modifier.height(16.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Spacer(Modifier.height(16.dp))
                        SettingsRow(
                            title = stringResource(R.string.settings_app_language),
                            value = LocaleManager.supported.find { it.tag == LocaleManager.currentTag() }?.nativeName ?: "English",
                            onClick = { showLanguageDialog = true },
                            noPadding = true
                        )
                    }
                }
            }

            item {
                SettingsSectionLabel(stringResource(R.string.settings_wear_sync))
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
                    Column {
                        Row(
                            modifier = Modifier.padding(16.dp).fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(R.string.settings_instant_sync), style = MaterialTheme.typography.bodyLarge)
                                Text(stringResource(R.string.settings_instant_sync_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(checked = instantSync, onCheckedChange = { instantSync = it })
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Row(
                            modifier = Modifier.padding(16.dp).fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(stringResource(R.string.settings_watch_connection), style = MaterialTheme.typography.bodyLarge)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    stringResource(R.string.settings_status_label) + ": " +
                                        (if (isStreaming) stringResource(R.string.settings_connected) else stringResource(R.string.settings_not_connected)),
                                    color = if (isStreaming) SemanticColors.Excellent else MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodySmall
                                )
                                if (watchIp != null) {
                                    Spacer(Modifier.width(8.dp))
                                    ForceLtr { Text(watchIp, style = MaterialTheme.typography.bodySmall) }
                                }
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    if (showLanguageDialog) {
        val activity = context as? Activity
        AlertDialog(
            onDismissRequest = { showLanguageDialog = false },
            title = { Text(stringResource(R.string.settings_app_language)) },
            text = {
                LazyColumn {
                    items(LocaleManager.supported) { lang ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val sharedPrefs = context.getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE)
                                    sharedPrefs.edit().putString("selected_language", lang.tag).apply()
                                    LocaleManager.setLocale(lang.tag)
                                    showLanguageDialog = false
                                    
                                    val intent = android.content.Intent(context, MainActivity::class.java).apply {
                                        flags = android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK or android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                                    }
                                    context.startActivity(intent)
                                    activity?.finish()
                                }
                                .padding(vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(lang.nativeName)
                            if (lang.tag == LocaleManager.currentTag()) Icon(Icons.Default.Check, contentDescription = null)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showLanguageDialog = false }) { Text("OK") } }
        )
    }
}

@Composable
private fun SettingsSectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 8.dp, bottom = 4.dp))
}

@Composable
private fun SettingsRow(title: String, value: String, onClick: () -> Unit, noPadding: Boolean = false) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(if (noPadding) PaddingValues(vertical = 4.dp) else PaddingValues(16.dp)),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.Default.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.outline)
    }
}

@Composable
private fun ThemeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label) })
}
