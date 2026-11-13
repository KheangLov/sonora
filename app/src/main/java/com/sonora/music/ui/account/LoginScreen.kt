package com.sonora.music.ui.account

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Message
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
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

private const val MUSIC_HOME = "https://music.youtube.com/"

/** Desktop Chrome: Google and YouTube never try to hand off to the YouTube app from a desktop page. */
private const val DESKTOP_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

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
    // Desktop mode is the default: it's the most reliable way to keep the YouTube app from hijacking sign-in.
    var desktopMode by remember { mutableStateOf(true) }
    var webView by remember { mutableStateOf<WebView?>(null) }

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

    /** Done as soon as any YouTube page has the session cookie, wherever Google sent us. */
    fun checkSignedIn(url: String?): Boolean {
        val host = url?.let { runCatching { Uri.parse(it).host }.getOrNull() } ?: return false
        if (!host.endsWith("youtube.com")) return false
        val cookie = CookieManager.getInstance().getCookie(MUSIC_HOME) ?: return false
        if ("SAPISID" !in cookie) return false
        onSignedIn(cookie)
        return true
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
            TextButton(onClick = {
                desktopMode = !desktopMode
                webView?.let { wv ->
                    wv.settings.userAgentString = userAgentFor(wv, desktopMode)
                    wv.loadUrl(LOGIN_URL)
                }
            }) { Text(if (desktopMode) "Mobile view" else "Desktop view") }
        }
        if (loading) {
            LinearProgressIndicator(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.primary)
        }
        AndroidView(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            factory = { ctx ->
                WebView(ctx).apply {
                    webView = this
                    val cookies = CookieManager.getInstance()
                    cookies.setAcceptCookie(true)
                    cookies.setAcceptThirdPartyCookies(this, true)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    // Pop-ups / "open in new window" would otherwise escape to another app.
                    settings.setSupportMultipleWindows(false)
                    settings.javaScriptCanOpenWindowsAutomatically = false
                    settings.useWideViewPort = true
                    settings.loadWithOverviewMode = true
                    settings.builtInZoomControls = true
                    settings.displayZoomControls = false
                    // Google refuses sign-in from "embedded" browsers; present as a regular browser.
                    settings.userAgentString = userAgentFor(this, desktopMode)

                    webChromeClient = object : WebChromeClient() {
                        override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?) = false
                    }
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                            handleUrl(view, request.url)

                        @Deprecated("Deprecated in Java")
                        override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
                            handleUrl(view, Uri.parse(url))

                        private fun handleUrl(view: WebView, uri: Uri): Boolean {
                            when (uri.scheme?.lowercase()) {
                                "http", "https" -> {
                                    // Never let youtube.com / m.youtube.com take over; finish on music.youtube.com instead.
                                    val host = uri.host.orEmpty()
                                    if (checkSignedIn(uri.toString())) return true
                                    if (host == "m.youtube.com" || host == "youtu.be") {
                                        view.loadUrl(MUSIC_HOME)
                                        return true
                                    }
                                    return false
                                }
                                "intent" -> {
                                    // intent://…#Intent;package=com.google.android.youtube;… → stay here.
                                    val fallback = runCatching {
                                        Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME)
                                            .getStringExtra("browser_fallback_url")
                                    }.getOrNull()
                                    view.loadUrl(fallback?.takeIf { it.startsWith("https://") } ?: MUSIC_HOME)
                                    return true
                                }
                                // vnd.youtube:, youtube:, market:, android-app: … → block, stay in Sonora.
                                else -> {
                                    if (!checkSignedIn(view.url)) view.loadUrl(MUSIC_HOME)
                                    return true
                                }
                            }
                        }

                        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                            loading = true
                            checkSignedIn(url)
                        }

                        override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                            checkSignedIn(url)
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            loading = false
                            if (checkSignedIn(url)) return
                            // Signed in on youtube.com but not yet on music.youtube.com: hop over.
                            val host = url?.let { Uri.parse(it).host }.orEmpty()
                            if (host.endsWith("youtube.com") && host != "music.youtube.com" && "/signin" !in url.orEmpty()) {
                                view?.loadUrl(MUSIC_HOME)
                            }
                        }
                    }
                    loadUrl(LOGIN_URL)
                }
            },
            onRelease = {
                webView = null
                it.stopLoading()
                it.destroy()
            },
        )
    }
}

private fun userAgentFor(view: WebView, desktop: Boolean): String =
    if (desktop) DESKTOP_UA
    else android.webkit.WebSettings.getDefaultUserAgent(view.context).replace("; wv", "")
