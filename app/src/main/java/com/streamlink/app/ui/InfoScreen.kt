package com.streamlink.app.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.streamlink.app.about.AboutStrings

@Composable
fun InfoScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val bgBrush = Brush.verticalGradient(
        colors = listOf(Color(0xFF040B16), Color(0xFF0A192F))
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(bgBrush)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Horus Al-Ferdous Logo
            Surface(
                modifier = Modifier
                    .size(160.dp)
                    .clip(CircleShape),
                color = Color(0xFF112240),
                tonalElevation = 12.dp,
                shadowElevation = 8.dp
            ) {
                Image(
                    painter = painterResource(id = R.drawable.horus_logo),
                    contentDescription = "Horus Al-Ferdous Logo",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }

            Spacer(modifier = Modifier.height(32.dp))

            // Company Name
            Text(
                text = AboutStrings.get("app_name"),
                fontSize = 32.sp,
                fontWeight = FontWeight.ExtraBold,
                color = Color(0xFF64FFDA), // Cyan/Teal tone
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Developer Info
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF112240)),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = AboutStrings.get("developer"),
                        fontSize = 16.sp,
                        color = Color(0xFF8892B0)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Eng/Abbas AboAlatta",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFE6F1FF)
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Actions Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                // Email
                Button(
                    onClick = {
                        val intent = Intent(Intent.ACTION_SENDTO).apply {
                            data = Uri.parse("mailto:support@horus-alferdous.com")
                            putExtra(Intent.EXTRA_SUBJECT, "StreamLinkWear Support")
                        }
                        context.startActivity(Intent.createChooser(intent, "Send Email"))
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF233554)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(AboutStrings.get("contact"), color = Color(0xFF64FFDA))
                }

                // Website
                Button(
                    onClick = {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://horus-alferdous.com"))
                        context.startActivity(intent)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF233554)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(AboutStrings.get("website"), color = Color(0xFF64FFDA))
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Version Info
            Text(
                text = "${AboutStrings.get("version")} 4.0-ultra",
                fontSize = 14.sp,
                color = Color(0xFF8892B0),
                fontWeight = FontWeight.Medium
            )

            Spacer(modifier = Modifier.height(48.dp))

            // Back Button
            Button(
                onClick = onBack,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF64FFDA)),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text("Back to Home", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0A192F))
            }
        }
    }
}
