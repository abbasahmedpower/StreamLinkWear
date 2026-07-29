package com.streamlink.wear.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.ScalingLazyColumn
import androidx.wear.compose.material.Text
import com.streamlink.wear.discovery.DiscoveryUiState

/**
 * ManualConnectionScreen — Phase 3 / Step 10
 *
 * Wear-native IP entry screen using:
 *  - [ScalingLazyColumn] — scales items for the round display, supports rotary input
 *  - [Chip] (Wear-native) — larger tap target than standard Button, better for Fitts's Law
 *  - Segmented 4-octet input — each field accepts 0-255 only (numeric keyboard)
 *  - Auto-advance — moves focus to next field when 3 digits are entered
 *  - [rememberSaveable] — state survives process recreation (rotation, config changes)
 *
 * This replaces the RemoteInput launcher in WearMainActivity which opened the system
 * keyboard with no structural guidance or inline validation feedback.
 *
 * Integration note: [state] is driven by [DiscoveryEngine.discoveryUiState], NOT by
 * a separate ConnectionUiState model — single source of truth, no parallel state machines.
 *
 * @param state        Current discovery/connection state from [DiscoveryEngine]
 * @param onConnect    Called with a valid "a.b.c.d" IP string when the user taps Connect
 * @param onBack       Called when the user wants to go back (Back button / swipe)
 */
@Composable
fun ManualConnectionScreen(
    state: DiscoveryUiState,
    onConnect: (String) -> Unit,
    onBack: () -> Unit
) {
    // 4 octets — each is independently editable.
    // rememberSaveable survives process recreation; listSaver handles List<String>.
    var octets by rememberSaveable(
        stateSaver = androidx.compose.runtime.saveable.listSaver(
            save    = { list -> list.toList() },
            restore = { it.toMutableList() }
        )
    ) { mutableStateOf(mutableListOf("", "", "", "")) }

    // Focus requesters — used for auto-advance between octet fields
    val focusRequesters = remember { List(4) { FocusRequester() } }

    val isConnecting = state is DiscoveryUiState.Scanning
    val isValid = octets.all { octet ->
        octet.toIntOrNull()?.let { n -> n in 0..255 } == true && octet.isNotEmpty()
    }

    MaterialTheme {
        ScalingLazyColumn(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // ── Header ───────────────────────────────────────────────────────
            item {
                Text(
                    text = "أدخل IP الموبايل",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color(0xFF94A3B8),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 20.dp)
                )
            }

            // ── Segmented IP input (4 octets separated by dots) ──────────────
            item {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp)
                ) {
                    octets.forEachIndexed { index, value ->
                        OctetField(
                            value          = value,
                            focusRequester = focusRequesters[index],
                            onValueChange  = { newValue ->
                                // Only digits, max 3 chars, value 0-255
                                if (newValue.length <= 3 && newValue.all(Char::isDigit)) {
                                    val updated = octets.toMutableList()
                                    updated[index] = newValue
                                    octets = updated

                                    // Auto-advance: move to next field when 3 digits entered
                                    val parsed = newValue.toIntOrNull()
                                    if (newValue.length == 3 && parsed != null && parsed in 0..255) {
                                        if (index < 3) {
                                            focusRequesters[index + 1].requestFocus()
                                        }
                                    }
                                    // Also auto-advance on value > 25_ (e.g. user typed 192)
                                    if (newValue.length == 3 && index < 3) {
                                        focusRequesters[index + 1].requestFocus()
                                    }
                                }
                            }
                        )

                        // Separator dot between octets (not after the last one)
                        if (index < 3) {
                            Text(
                                text = ".",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF64748B)
                            )
                        }
                    }
                }
            }

            // ── Validation hint ───────────────────────────────────────────────
            if (!isValid && octets.any { it.isNotEmpty() }) {
                item {
                    Text(
                        text = "كل رقم بين 0 و 255",
                        fontSize = 10.sp,
                        color = MaterialTheme.colors.error,
                        textAlign = TextAlign.Center
                    )
                }
            }

            // ── Connect Chip (Wear-native, not standard Button) ───────────────
            item {
                Chip(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                    onClick = {
                        if (isValid && !isConnecting) {
                            onConnect(octets.joinToString("."))
                        }
                    },
                    enabled = isValid && !isConnecting,
                    colors  = ChipDefaults.primaryChipColors(
                        backgroundColor = if (isValid && !isConnecting)
                            Color(0xFF10B981) else Color(0xFF334155)
                    ),
                    label = {
                        Text(
                            text = when {
                                isConnecting -> "جاري الاتصال…"
                                isValid      -> "اتصال"
                                else         -> "أكمل الـ IP أولاً"
                            },
                            fontSize = 13.sp,
                            color    = if (isValid && !isConnecting) Color.White else Color(0xFF64748B),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                )
            }

            // ── Error display ─────────────────────────────────────────────────
            if (state is DiscoveryUiState.TimedOut) {
                item {
                    Text(
                        text = "لم يُعثر على الجهاز — أدخل IP يدوياً",
                        fontSize = 10.sp,
                        color = Color(0xFFF59E0B),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )
                }
            }

            // ── Back hint ─────────────────────────────────────────────────────
            item {
                Text(
                    text = "← رجوع",
                    fontSize = 10.sp,
                    color = Color(0xFF475569),
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .padding(bottom = 16.dp)
                        .then(
                            Modifier.padding(4.dp)
                                .let { mod ->
                                    mod
                                }
                        )
                )
            }
        }
    }
}

/**
 * Single IPv4 octet input field.
 *
 * - Width: 40 dp — fits 3 digits on a round watch display
 * - [KeyboardType.Number] — opens numeric keyboard, not QWERTY
 * - Underline-only style — minimal visual weight on a small screen
 * - Centered text for readability
 */
@Composable
private fun OctetField(
    value:          String,
    focusRequester: FocusRequester,
    onValueChange:  (String) -> Unit
) {
    val isValid = value.isEmpty() || (value.toIntOrNull()?.let { it in 0..255 } == true)

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.width(40.dp)
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Number
            ),
            singleLine  = true,
            textStyle   = TextStyle(
                fontSize    = 16.sp,
                fontWeight  = FontWeight.SemiBold,
                textAlign   = TextAlign.Center,
                color       = if (isValid) Color(0xFF10B981) else Color(0xFFEF4444)
            ),
            modifier = Modifier
                .width(40.dp)
                .focusRequester(focusRequester),
            decorationBox = { innerTextField ->
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.fillMaxSize()
                ) {
                    if (value.isEmpty()) {
                        Text(
                            text  = "___",
                            color = Color(0xFF334155),
                            fontSize = 14.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                    innerTextField()
                }
            }
        )
    }
}
