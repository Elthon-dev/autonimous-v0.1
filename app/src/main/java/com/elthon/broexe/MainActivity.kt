package com.elthon.broexe

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.elthon.broexe.ui.AppRoot
import com.elthon.broexe.ui.theme.BroExeTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BroExeTheme {
                AppRoot()
            }
        }
    }
}
