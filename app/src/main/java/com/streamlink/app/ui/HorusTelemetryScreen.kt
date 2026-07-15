package com.streamlink.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// الألوان الخاصة بالتصميم السايبربانك الجديد
val CyberBackground = Color(0xFF0C0E15)
val CyberPrimary = Color(0xFF00E5FF)
val CyberOnPrimary = Color(0xFF00363D)
val CyberSurface = Color(0x99191B23) // زجاجي شفاف بدرجة 60%
val CyberBorder = Color(0x2600E5FF)  // حواف سيان شفافة بدرجة 15%

@Composable
fun HorusTelemetryScreen(
    onSettingsClick: () -> Unit,
    onStartCasting: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(CyberBackground)
            // رسم تدرج هالة الضوء الفسفورية (Radial Glow) في الخلفية
            .drawBehind {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(CyberPrimary.copy(alpha = 0.08f), Color.Transparent),
                        center = this.center.copy(y = 0f),
                        radius = size.width * 0.8f
                    )
                )
            }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp)
                .statusBarsPadding()
        ) {
            // 1. شريط العنوان العلوي (Top Bar)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Star, // أيقونة حورس المراقبة
                        contentDescription = "Logo",
                        tint = CyberPrimary,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "HORUS LINK",
                        color = CyberPrimary,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 2.sp
                    )
                }
                // أيقونة الإعدادات بتأثير النيون
                IconButton(onClick = onSettingsClick) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Settings",
                        tint = Color(0xFFE2E2EC)
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // 2. ترويسة الصفحة (Header)
            Text(
                text = "Telemetry Hub",
                color = Color.White,
                fontSize = 32.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "SYSTEM NODES ONLINE: 01 ACTIVE",
                color = CyberPrimary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )

            Spacer(modifier = Modifier.height(24.dp))

            // 3. لوحة جودة الشبكة (Glassmorphic Network Quality Panel)
            GlassCard {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "NETWORK QUALITY",
                            color = Color(0xFFBAC9CC),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "95% STABLE",
                            color = CyberPrimary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    // شريط الاستقرار النيوني التفاعلي
                    LinearProgressIndicator(
                        progress = { 0.95f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = CyberPrimary,
                        trackColor = Color(0x3300E5FF)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 4. لوحة الـ AI Bitrate Optimizer التفاعلية
            var isOptimizerEnabled by remember { mutableStateOf(true) }
            GlassCard {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "AI BITRATE OPTIMIZER",
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Adaptive control active in real-time",
                            color = Color(0xFFBAC9CC),
                            fontSize = 12.sp
                        )
                    }
                    Switch(
                        checked = isOptimizerEnabled,
                        onCheckedChange = { isOptimizerEnabled = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = CyberOnPrimary,
                            checkedTrackColor = CyberPrimary,
                            uncheckedThumbColor = Color.Gray,
                            uncheckedTrackColor = Color(0x33FFFFFF)
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            // 5. زر البدء الفوسفوري المتوهج (Casting Button)
            Button(
                onClick = onStartCasting,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .border(1.dp, CyberPrimary, RoundedCornerShape(8.dp)),
                colors = ButtonDefaults.buttonColors(
                    containerColor = CyberPrimary,
                    contentColor = CyberOnPrimary
                ),
                shape = RoundedCornerShape(8.dp)
            ) {
                Row(
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "Start",
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "START CASTING SCREEN",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 2.sp
                    )
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

/**
 * تصميم اللوح الزجاجي (Glassmorphic Container)
 */
@Composable
fun GlassCard(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(CyberSurface)
            .border(1.dp, CyberBorder, RoundedCornerShape(12.dp))
    ) {
        content()
    }
}
