package com.streamlink.app.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.invisibleToUser
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.streamlink.app.BuildConfig
import com.streamlink.app.R

/**
 * Safely launches a URL, falling back to a toast if no browser / intent handler is available.
 * Fixes issue 3.3 — prevents ActivityNotFoundException crash on custom ROMs.
 */
private fun openUrlSafely(context: Context, url: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, context.getString(R.string.error_no_browser), Toast.LENGTH_SHORT).show()
    }
}

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
                title = { Text(stringResource(R.string.info_about_title), fontWeight = FontWeight.Bold) },
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

                // App Name & Version — fix 6: read from BuildConfig, not hardcoded string
                Text(
                    text = androidx.compose.ui.res.stringResource(R.string.app_title),
                    fontSize = 32.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
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
                            text = stringResource(R.string.info_developer_label).uppercase(),
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
                            text = stringResource(R.string.info_tagline),
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                        )
                    }
                }

                Spacer(modifier = Modifier.height(32.dp))

                Text(
                    text = stringResource(R.string.info_social_title),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.align(Alignment.Start)
                )
                Spacer(modifier = Modifier.height(16.dp))

                // Grid of Social Links — all URLs launched via openUrlSafely (fix 3.3)
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        SocialButton(Modifier.weight(1f), stringResource(R.string.info_join_telegram), "📢", Color(0xFF0088CC)) { openUrlSafely(context, "https://t.me/HoruselfardosTech") }
                        SocialButton(Modifier.weight(1f), stringResource(R.string.info_join_group), "👥", Color(0xFF0088CC)) { openUrlSafely(context, "https://t.me/+YqkCX65xYhQxNDQ0") }
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        SocialButton(Modifier.weight(1f), "Facebook", "📘", Color(0xFF1877F2)) { openUrlSafely(context, "https://facebook.com/AbbasAhmedpower") }
                        SocialButton(Modifier.weight(1f), "Twitter (X)", "𝕏", Color(0xFF000000)) { openUrlSafely(context, "https://x.com/abbasahmedhero") }
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        SocialButton(Modifier.weight(1f), "Instagram", "📸", Color(0xFFE1306C)) { openUrlSafely(context, "https://www.instagram.com/abbasahmedpower/") }
                        SocialButton(Modifier.weight(1f), "Snapchat", "👻", Color(0xFFFFFC00), textColor = Color.Black) { openUrlSafely(context, "https://www.snapchat.com/add/abbasahmedpower") }
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        SocialButton(Modifier.weight(1f), "TikTok", "🎵", Color(0xFF010101)) { openUrlSafely(context, "https://www.tiktok.com/@abbasahmedpower") }
                        SocialButton(Modifier.weight(1f), "Twitch", "👾", Color(0xFF9146FF)) { openUrlSafely(context, "https://www.twitch.tv/abbasahmedpower") }
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        SocialButton(Modifier.weight(1f), "Discord", "💬", Color(0xFF5865F2)) { openUrlSafely(context, "https://discord.gg/9AUKAVY4Y") }
                        SocialButton(Modifier.weight(1f), stringResource(R.string.info_github), "💻", Color(0xFF333333)) { openUrlSafely(context, "https://github.com/abbasahmedpower/StreamLinkWear") }
                    }
                    // Privacy Policy — fix 4.2: uses previously orphaned info_privacy_policy key
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // fix 6: Check for Updates — links directly to GitHub Releases page
                        OutlinedButton(
                            onClick = { openUrlSafely(context, "https://github.com/abbasahmedpower/StreamLinkWear/releases") },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(stringResource(R.string.info_check_updates))
                        }
                        OutlinedButton(
                            onClick = { openUrlSafely(context, "https://abbasahmedpower.github.io/StreamLinkWear/privacy") },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(stringResource(R.string.info_privacy_policy))
                        }
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
            .semantics(mergeDescendants = true) {
                contentDescription = "$title button"
            }
            .clickable(role = Role.Button) { onClick() },
        color = containerColor,
        shape = RoundedCornerShape(16.dp),
        shadowElevation = 4.dp
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Text(text = icon, fontSize = 24.sp, modifier = Modifier.semantics { invisibleToUser() })
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
