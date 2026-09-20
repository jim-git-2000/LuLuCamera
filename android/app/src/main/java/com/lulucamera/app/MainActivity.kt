package com.lulucamera.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.lulucamera.app.ui.CameraScreen
import com.lulucamera.app.ui.CameraViewModel
import com.lulucamera.app.ui.LuLuTheme

class MainActivity : ComponentActivity() {
    private val viewModel by viewModels<CameraViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LuLuTheme {
                CameraScreen(viewModel)
            }
        }
    }
}
