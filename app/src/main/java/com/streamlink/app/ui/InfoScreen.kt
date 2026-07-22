package com.streamlink.app.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.streamlink.app.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InfoScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    val gradientBrush = Brush.verticalGradient(
        colors = listOf(
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
            MaterialTheme.colorScheme.background
        )
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("About Developer", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Back") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        },
        containerColor = Color.Transparent
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(gradientBrush)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(scrollState)
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(modifier = Modifier.height(16.dp))

                // Horus Al-Ferdous Logo
                Surface(
                    modifier = Modifier
                        .size(120.dp)
                        .clip(CircleShape),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    tonalElevation = 12.dp,
                    shadowElevation = 16.dp
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.horus_logo),
                        contentDescription = "Horus Al-Ferdous Logo",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                // App Name & Version
                Text(
                    text = androidx.compose.ui.res.stringResource(R.string.app_title),
                    fontSize = 32.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = "Version 4.0-ultra (Phase 1.5)",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Medium
                )

                Spacer(modifier = Modifier.height(32.dp))

                // Developer Info Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    shape = RoundedCornerShape(24.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .padding(24.dp)
                            .fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "DEVELOPED BY",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            letterSpacing = 2.sp
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Abbas Ahmed",
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Black,
                            color = MaterialTheme.colorScheme.onSurface,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Building the future of WearOS connectivity.",
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                        )
                    }
                }

                Spacer(modifier = Modifier.height(32.dp))

                Text(
                    text = "Socials & Communities",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.align(Alignment.Start)
                )
                Spacer(modifier = Modifier.height(16.dp))

                // Grid of Social Links
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        SocialButton(Modifier.weight(1f), "Channel", "📢", Color(0xFF0088CC)) { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/HoruselfardosTech"))) }
                        SocialButton(Modifier.weight(1f), "Group", "👥", Color(0xFF0088CC)) { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/+YqkCX65xYhQxNDQ0"))) }
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        SocialButton(Modifier.weight(1f), "Facebook", "📘", Color(0xFF1877F2)) { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://facebook.com/AbbasAhmedpower"))) }
                        SocialButton(Modifier.weight(1f), "Twitter (X)", "𝕏", Color(0xFF000000)) { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://x.com/abbasahmedhero"))) }
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        SocialButton(Modifier.weight(1f), "Instagram", "📸", Color(0xFFE1306C)) { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.instagram.com/abbasahmedpower/"))) }
                        SocialButton(Modifier.weight(1f), "Snapchat", "👻", Color(0xFFFFFC00), textColor = Color.Black) { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.snapchat.com/add/abbasahmedpower"))) }
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        SocialButton(Modifier.weight(1f), "TikTok", "🎵", Color(0xFF010101)) { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.tiktok.com/@abbasahmedpower"))) }
                        SocialButton(Modifier.weight(1f), "Twitch", "👾", Color(0xFF9146FF)) { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.twitch.tv/abbasahmedpower"))) }
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        SocialButton(Modifier.weight(1f), "Discord", "💬", Color(0xFF5865F2)) { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://discord.gg/9AUKAVY4Y"))) }
                        SocialButton(Modifier.weight(1f), "GitHub", "💻", Color(0xFF333333)) { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/abbasahmedpower/StreamLinkWear"))) }
                    }
                }

                Spacer(modifier = Modifier.height(48.dp))
            }
        }
    }
}

@Composable
fun SocialButton(modifier: Modifier = Modifier, title: String, icon: String, containerColor: Color, textColor: Color = Color.White, onClick: () -> Unit) {
    Surface(
        modifier = modifier
            .height(64.dp)
            .clickable { onClick() },
        color = containerColor,
        shape = RoundedCornerShape(16.dp),
        shadowElevation = 4.dp
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Text(text = icon, fontSize = 24.sp)
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = title,
                color = textColor,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp
            )
        }
    }
}
