package com.streamlink.wear.ui

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.*

@Composable
fun WearInfoScreen(
    appVersion: String = "v2.0.0-Titan",
    onBackClick: () -> Unit
) {
    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // شعار النظام الرقمي
        item {
            Text(
                text = "HORUS OS",
                style = MaterialTheme.typography.title1,
                color = Color(0xFFD4AF37) // الذهبي الملكي لشعار حورس
            )
        }

        // تفاصيل النظام وهندسة البث
        item {
            Text(
                text = "StreamLink Wear Engine",
                style = MaterialTheme.typography.caption1,
                color = Color(0xFF00E5FF), // السيان السيبراني
                textAlign = TextAlign.Center
            )
        }

        item {
            Spacer(modifier = Modifier.height(8.dp))
        }

        item {
            Card(
                onClick = {},
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)
            ) {
                Column(horizontalAlignment = Alignment.Start) {
                    Text(text = "النسخة: $appVersion", style = MaterialTheme.typography.body2)
                    Text(text = "المطور: Eng. Abbas", style = MaterialTheme.typography.caption2, color = Color.Gray)
                    Text(text = "القناة العكسية: نشطة (٢٦ بايت)", style = MaterialTheme.typography.caption2, color = Color(0xFF00E5FF))
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(8.dp))
        }

        // زر العودة الآمن
        item {
            CompactButton(
                onClick = onBackClick,
                colors = ButtonDefaults.buttonColors(backgroundColor = Color(0x26, 0x31, 0x48))
            ) {
                Text(text = "رجوع", color = Color.White)
            }
        }
    }
}
