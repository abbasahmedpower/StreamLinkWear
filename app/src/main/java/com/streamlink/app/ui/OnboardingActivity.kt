package com.streamlink.app.ui

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
// import androidx.appcompat.app.AppCompatActivity (Inherits from BaseActivity instead)
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.streamlink.app.ui.MainActivity
import com.streamlink.app.utils.AccessibilityUtil

class OnboardingActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // التحقق الاستباقي: لو مفعله بالفعل لا داعي لعرض الشاشة
        if (AccessibilityUtil.isAccessibilityServiceEnabled(this)) {
            navigateToMain()
            return
        }

        setContent {
            OnboardingScreen(
                onGrantPermissionClick = { launchAccessibilitySettings() }
            )
        }
    }

    override fun onResume() {
        super.onResume()
        // فحص تلقائي عند عودة المستخدم من شاشة إعدادات النظام
        if (AccessibilityUtil.isAccessibilityServiceEnabled(this)) {
            Toast.makeText(this, "✅ تم تفعيل الصلاحية بنجاح!", Toast.LENGTH_SHORT).show()
            navigateToMain()
        }
    }

    private fun launchAccessibilitySettings() {
        try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            startActivity(intent)
        } catch (e: Exception) {
            val fallbackIntent = Intent(Settings.ACTION_SETTINGS)
            startActivity(fallbackIntent)
            Toast.makeText(this, "يرجى التوجة إلى إمكانية الوصول وتفعيل StreamLinkWear يدوياً", Toast.LENGTH_LONG).show()
        }
    }

    private fun navigateToMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}

@Composable
fun OnboardingScreen(onGrantPermissionClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color(0xFF0F172A) // المظهر الاحترافي الداكن للمشروع
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(180.dp)
                    .background(Color(0xFF1E293B), shape = RoundedCornerShape(24.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "⚙️\nAccessibility",
                    color = Color(0xFF38BDF8),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
            }

            Spacer(modifier = Modifier.height(32.dp))

            Text(
                text = "صلاحية التحكم والمحاكاة",
                color = Color.White,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "يتطلب StreamLinkWear تفعيل خدمة إمكانية الوصول ليتمكن من استقبال إحداثيات اللمس من ساعتك وحقنها داخل الهاتف بدقة. نحن نلتزم بحماية خصوصيتك: لا يتم حفظ أو مشاركة أي بيانات تفاعلية نهائياً.",
                color = Color(0xFF94A3B8),
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                lineHeight = 22.sp
            )

            Spacer(modifier = Modifier.height(48.dp))

            Button(
                onClick = onGrantPermissionClick,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
            ) {
                Text(
                    text = "الانتقال إلى الإعدادات للتفعيل ➔",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
