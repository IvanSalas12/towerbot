package com.arisa.towerbot.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

enum class Route { HOME, CALIBRATION, STRATEGY, HISTORY, SETTINGS }

/** Una captura recién hecha desde la burbuja, esperando a que la calibres. */
data class CaptureRequest(val path: String, val gamePackage: String?)

class MainActivity : ComponentActivity() {
    private val capture = mutableStateOf<CaptureRequest?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handle(intent)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFFFFB74D), secondary = Color(0xFF4FC3F7))) {
                Surface(Modifier.fillMaxSize()) {
                    Box(Modifier.safeDrawingPadding()) { App() }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        val path = intent?.getStringExtra(EXTRA_CAPTURE) ?: return
        capture.value = CaptureRequest(path, intent.getStringExtra(EXTRA_PACKAGE))
        intent.removeExtra(EXTRA_CAPTURE)
    }

    @Composable
    private fun App() {
        var route by rememberSaveable { mutableStateOf(Route.HOME) }
        val pending = capture.value
        if (pending != null) {
            BackHandler { capture.value = null }
            EditorScreen(pending, onDone = {
                capture.value = null
                route = Route.CALIBRATION
            })
            return
        }
        if (route != Route.HOME) BackHandler { route = Route.HOME }
        val back = { route = Route.HOME }
        when (route) {
            Route.HOME -> HomeScreen(navigate = { route = it })
            Route.CALIBRATION -> CalibrationScreen(back)
            Route.STRATEGY -> StrategyScreen(back)
            Route.HISTORY -> HistoryScreen(back)
            Route.SETTINGS -> SettingsScreen(back)
        }
    }

    companion object {
        const val EXTRA_CAPTURE = "capture"
        const val EXTRA_PACKAGE = "package"
    }
}
