package com.sonora.music.ui

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import com.sonora.music.AppGraph
import com.sonora.music.ui.theme.SonoraTheme

class MainActivity : ComponentActivity() {

    private val openPlayer = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        AppGraph.player.connect()
        openPlayer.value = intent?.getBooleanExtra(EXTRA_OPEN_PLAYER, false) == true

        setContent {
            SonoraTheme {
                SonoraRoot(
                    openPlayerRequest = openPlayer.value,
                    onPlayerRequestHandled = { openPlayer.value = false },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_OPEN_PLAYER, false)) openPlayer.value = true
    }

    companion object {
        const val EXTRA_OPEN_PLAYER = "open_player"
    }
}
