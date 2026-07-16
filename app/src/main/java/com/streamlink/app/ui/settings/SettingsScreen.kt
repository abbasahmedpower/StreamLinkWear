package com.streamlink.app.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.streamlink.shared.util.SystemSettingsStore

@Composable
fun SettingsScreen(settingsStore: SystemSettingsStore) {
    var isDynamicFpsEnabled by remember { mutableStateOf(settingsStore.isDynamicFpsEnabled) }
    var isPrivacyBlackoutEnabled by remember { mutableStateOf(settingsStore.isPrivacyBlackoutEnabled) }
    var isImuGesturesEnabled by remember { mutableStateOf(settingsStore.isImuGesturesEnabled) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            text = "⚙️ إعدادات النظام المتقدمة",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(bottom = 24.dp)
        )

        // 1. ميزة الـ Dynamic FPS
        SettingsToggleItem(
            title = "توفير طاقة الساعة الذكي (Dynamic FPS)",
            description = "يخفض تحديث الشاشة على الساعة لـ 1 FPS تلقائياً عند ثبات المحتوى لتوفير 50% من طاقة البطارية.",
            checked = isDynamicFpsEnabled,
            onCheckedChange = {
                isDynamicFpsEnabled = it
                settingsStore.setDynamicFps(it)
            }
        )

        Divider(modifier = Modifier.padding(vertical = 16.dp))

        // 2. ميزة الـ Privacy Blackout
        SettingsToggleItem(
            title = "وضع تعتيم شاشة الهاتف (Privacy Blackout)",
            description = "يقوم بإطفاء شاشة الهاتف بالكامل أثناء البث لحماية خصوصيتك وتوفير طاقة بطارية الهاتف.",
            checked = isPrivacyBlackoutEnabled,
            onCheckedChange = {
                isPrivacyBlackoutEnabled = it
                settingsStore.setPrivacyBlackout(it)
            }
        )

        Divider(modifier = Modifier.padding(vertical = 16.dp))

        // 3. ميزة الـ IMU Gestures
        SettingsToggleItem(
            title = "إيماءات المعصم اللامسية (IMU Air Gestures)",
            description = "التحكم في سحب الشاشة والعودة عبر حركة اليد فقط دون لمس شاشة الساعة. (مثالية للمهندسين والرياضيين).",
            checked = isImuGesturesEnabled,
            onCheckedChange = {
                isImuGesturesEnabled = it
                settingsStore.setImuGestures(it)
            }
        )
    }
}

@Composable
fun SettingsToggleItem(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 16.sp
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}
