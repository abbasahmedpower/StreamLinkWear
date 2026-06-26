package com.streamlink.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.viewmodel.compose.viewModel
import com.streamlink.wear.ui.PredictiveStreamScreen
import com.streamlink.wear.ui.WearTheme

class WearMainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            WearTheme {
                val viewModel: com.streamlink.wear.ui.StreamViewModel = viewModel()
                PredictiveStreamScreen(viewModel = viewModel)
            }
        }
    }
}
