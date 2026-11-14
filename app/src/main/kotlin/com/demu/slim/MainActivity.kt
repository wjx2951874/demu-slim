package com.demu.slim

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.demu.slim.ui.SlimApp
import com.demu.slim.ui.SlimViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val viewModel = SlimViewModel(applicationContext)
        setContent {
            SlimApp(viewModel)
        }
    }
}
