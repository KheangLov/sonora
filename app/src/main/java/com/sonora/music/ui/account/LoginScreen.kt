package com.sonora.music.ui.account

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.sonora.music.AppGraph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val LOGIN_URL =
    "https://accounts.google.com/ServiceLogin?ltmpl=music&service=youtube&passive=true" +
        "&continue=https%3A%2F%2Fwww.youtube.com%2Fsignin%3Faction_handle_signin%3Dtrue%26next%3Dhttps%253A%252F%252Fmusic.youtube.com%252F"

/** Google sign-in in a WebView; we keep only the music.youtube.com session cookie. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LoginScreen(bottomPadding: Dp, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var finished by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }

    fun onSignedIn(cookie: String) {
        if (finished) return
        finished = true
        AppGraph.account.signIn(cookie)
        AppGraph.youtube.clearHomeCache()
        scope.launch {
            val (name, photo) = runCatching { withContext(Dispatchers.IO) { AppGraph.ytMusic.accountInfo() } }
                .getOrDefault(null to null)
            AppGraph.account.setProfile(name, photo)
            Toast.makeText(context, if (name != null) "Signed in as $name" else "Signed in", Toast.LENGTH_SHORT).show()
        }
        onDone()
    }

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .statusBarsPadding().padding(bottom = bottomPadding)
    ) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onDone) { Icon(Icons.Rounded.Close, "Close") }
            Column(Modifier.weight(1f)) {
                Text("Sign in to YouTube Music", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Your login stays on this phone only",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (loading) {
            LinearProgressIndicator(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.primary)
        }
        AndroidView(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            factory = { ctx ->
                WebView(ctx).apply {
                    val cookies = CookieManager.getInstance()
                    cookies.setAcceptCookie(true)
                    cookies.setAcceptThirdPartyCookies(this, true)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    // Google refuses sign-in from "embedded" browsers; present as regular Chrome.
                    settings.userAgentString = settings.userAgentString.replace("; wv", "")
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            loading = false
                            if (url != null && url.startsWith("https://music.youtube.com")) {
                                val cookie = cookies.getCookie("https://music.youtube.com")
                                if (cookie != null && "SAPISID" in cookie) onSignedIn(cookie)
                            }
                        }
                    }
                    loadUrl(LOGIN_URL)
                }
            },
            onRelease = { it.destroy() },
        )
    }
}
