package com.streamlink.app.ui

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.streamlink.shared.DirectSocketServer
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MobilePinInputActivity : ComponentActivity() {

    @Inject
    lateinit var socketServer: DirectSocketServer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = Color(0xFF1E293B)
            ) {
                PinInputScreen(
                    onPinComplete = { enteredPin ->
                        // 1. تمرير الرمز للمحرك التشفيري — سيُستخدم في الـ HKDF
                        socketServer.pairingCode = enteredPin
                        Toast.makeText(
                            this@MobilePinInputActivity,
                            "جاري التحقق التشفيري من الرمز...",
                            Toast.LENGTH_SHORT
                        ).show()
                        // 2. العودة لـ MainActivity لبدء البث
                        finish()
                    }
                )
            }
        }
    }
}

@Composable
fun PinInputScreen(onPinComplete: (String) -> Unit) {
    val pinLength = 6
    val pinValues = remember { mutableStateListOf(*Array(pinLength) { "" }) }
    val focusRequesters = remember { List(pinLength) { FocusRequester() } }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "🔐 رمز الاقتران",
            color = Color.White,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = "يرجى كتابة الرمز المكون من 6 أرقام الظاهر على شاشة ساعتك الآن لتأمين القناة المشفرة.",
            color = Color(0xFF94A3B8),
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
            lineHeight = 20.sp
        )

        Spacer(modifier = Modifier.height(32.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            for (i in 0 until pinLength) {
                OutlinedTextField(
                    value = pinValues[i],
                    onValueChange = { newValue ->
                        if (newValue.length <= 1) {
                            pinValues[i] = newValue
                            if (newValue.isNotEmpty() && i < pinLength - 1) {
                                focusRequesters[i + 1].requestFocus()
                            }
                            val currentPin = pinValues.joinToString("")
                            if (currentPin.length == pinLength) {
                                onPinComplete(currentPin)
                            }
                        }
                    },
                    modifier = Modifier
                        .size(46.dp)
                        .focusRequester(focusRequesters[i])
                        .border(1.dp, Color(0xFF475569), RoundedCornerShape(8.dp)),
                    textStyle = TextStyle(
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    ),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF2563EB),
                        unfocusedBorderColor = Color.Transparent
                    )
                )
            }
        }

        LaunchedEffect(Unit) {
            focusRequesters[0].requestFocus()
        }
    }
}
