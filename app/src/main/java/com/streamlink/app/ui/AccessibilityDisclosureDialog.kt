package com.streamlink.app.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

@Composable
fun AccessibilityDisclosureDialog(
    onAccept: () -> Unit,
    onDecline: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDecline,
        title = { Text(text = "Prominent Disclosure: Accessibility Service", fontWeight = androidx.compose.ui.text.font.FontWeight.Bold) },
        text = {
            Text(
                text = "StreamLinkWear requires the Accessibility Service permission to translate and inject remote touch gestures, swipes, and hardware button presses from your connected smartwatch onto this device.\n\n" +
                       "Why is this needed?\n" +
                       "This permission is strictly required to allow remote control of your phone from your watch during an active streaming session.\n\n" +
                       "Data Collection & Privacy:\n" +
                       "• We DO NOT collect, store, or share any personal data, screen content, or text inputs.\n" +
                       "• The service only operates during an active connection and does not run continuously in the background.\n\n" +
                       "By clicking 'I Agree', you consent to enabling the Accessibility Service for these specific functions."
            )
        },
        confirmButton = {
            Button(onClick = onAccept) {
                Text("I Agree")
            }
        },
        dismissButton = {
            TextButton(onClick = onDecline) {
                Text("Decline")
            }
        }
    )
}
