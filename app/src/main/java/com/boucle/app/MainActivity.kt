package com.boucle.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.boucle.app.ui.BoucleRoot
import com.boucle.app.ui.theme.BoucleTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BoucleTheme {
                BoucleRoot()
            }
        }
    }
}
