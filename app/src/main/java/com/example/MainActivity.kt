package com.example

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.example.ui.AuraShareScreen
import com.example.ui.MainViewModel
import com.example.ui.theme.MatteBackground
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                Log.e("AuraShare", "Global exception captured: ${throwable.message}", throwable)
                defaultHandler?.uncaughtException(thread, throwable)
            }
        } catch (t: Throwable) {
            Log.w("AuraShare", "Could not set uncaught exception handler", t)
        }

        handleIntent(intent)

        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MatteBackground
                ) {
                    AuraShareScreen(viewModel = viewModel)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val dataUri = intent?.data?.toString() ?: return
        Log.i("AuraShare", "Handling incoming pairing intent URI: $dataUri")
        viewModel.applyPairingUri(dataUri)
    }
}
