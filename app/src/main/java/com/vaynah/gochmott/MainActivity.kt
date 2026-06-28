package com.vaynah.gochmott

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.vaynah.gochmott.ui.GochMottNavGraph
import com.vaynah.gochmott.ui.theme.GochMottTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            GochMottTheme {
                GochMottNavGraph()
            }
        }
    }
}
