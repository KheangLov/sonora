package com.sonora.music.ui

import android.Manifest
import android.net.Uri
import com.sonora.music.data.youtube.YtCollection
import com.sonora.music.ui.account.LoginScreen
import com.sonora.music.ui.library.RemoteCollectionScreen
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.sonora.music.AppGraph
import com.sonora.music.ui.home.HomeScreen
import com.sonora.music.ui.library.CollectionScreen
import com.sonora.music.ui.library.LibraryScreen
import com.sonora.music.ui.player.MiniPlayer
import com.sonora.music.ui.player.NowPlayingScreen
import com.sonora.music.ui.search.SearchScreen

private data class Tab(val route: String, val label: String, val selected: ImageVector, val unselected: ImageVector)

private val Tabs = listOf(
    Tab("home", "Home", Icons.Rounded.Home, Icons.Outlined.Home),
    Tab("search", "Search", Icons.Rounded.Search, Icons.Outlined.Search),
    Tab("library", "Library", Icons.Rounded.LibraryMusic, Icons.Outlined.LibraryMusic),
)

@Composable
fun SonoraRoot(openPlayerRequest: Boolean, onPlayerRequestHandled: () -> Unit) {
    val context = LocalContext.current
    val nav = rememberNavController()
    val conn = AppGraph.player
    val state by conn.state.collectAsStateWithLifecycle()
    var expanded by rememberSaveable { mutableStateOf(false) }
    val bg = MaterialTheme.colorScheme.background

    // Android 13+: media notification needs permission
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    LaunchedEffect(Unit) {
        conn.messages.collect { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
    }
    LaunchedEffect(openPlayerRequest) {
        if (openPlayerRequest) {
            expanded = true
            onPlayerRequestHandled()
        }
    }
    BackHandler(enabled = expanded) { expanded = false }

    Box(Modifier.fillMaxSize().background(bg)) {
        Scaffold(
            containerColor = Color.Transparent,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            bottomBar = {
                // Floating dock: mini player stacked on a pill-shaped nav bar
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Transparent, bg.copy(alpha = 0.85f), bg)))
                        .padding(top = 20.dp)
                        .navigationBarsPadding()
                        .padding(start = 12.dp, end = 12.dp, bottom = 10.dp)
                ) {
                    AnimatedVisibility(
                        visible = state.current != null,
                        enter = slideInVertically { it } + fadeIn(),
                        exit = slideOutVertically { it } + fadeOut(),
                    ) {
                        MiniPlayer(state, onExpand = { expanded = true }, modifier = Modifier.padding(bottom = 8.dp))
                    }
                    BottomNav(nav)
                }
            },
        ) { padding ->
            val bottom = padding.calculateBottomPadding()
            NavHost(
                navController = nav,
                startDestination = "home",
                enterTransition = { fadeIn(tween(220)) },
                exitTransition = { fadeOut(tween(160)) },
                popEnterTransition = { fadeIn(tween(220)) },
                popExitTransition = { fadeOut(tween(160)) },
            ) {
                composable("home") {
                    HomeScreen(
                        bottom,
                        onOpenCollection = { nav.navigate("collection/$it") },
                        onOpenRemote = { nav.openRemote(it) },
                    )
                }
                composable("search") { SearchScreen(bottom) }
                composable("library") {
                    LibraryScreen(
                        bottom,
                        onOpenCollection = { nav.navigate("collection/$it") },
                        onOpenPlaylist = { nav.navigate("collection/playlist?id=$it") },
                        onSignIn = { nav.navigate("login") },
                        onOpenRemote = { nav.openRemote(it) },
                    )
                }
                composable("login") {
                    LoginScreen(bottom, onDone = { nav.popBackStack() })
                }
                composable(
                    "remote?id={id}&q={q}&t={t}&s={s}&th={th}",
                    arguments = listOf("id", "q", "t", "s", "th").map { name ->
                        navArgument(name) { type = NavType.StringType; defaultValue = "" }
                    },
                ) { entry ->
                    val a = entry.arguments
                    val collection = YtCollection(
                        id = a?.getString("id").orEmpty(),
                        isQueue = a?.getString("q") == "1",
                        title = a?.getString("t").orEmpty(),
                        subtitle = a?.getString("s").orEmpty(),
                        thumbnail = a?.getString("th").orEmpty(),
                    )
                    RemoteCollectionScreen(collection, bottom, onBack = { nav.popBackStack() })
                }
                composable(
                    "collection/{kind}?id={id}",
                    arguments = listOf(
                        navArgument("kind") { type = NavType.StringType },
                        navArgument("id") { type = NavType.LongType; defaultValue = -1L },
                    ),
                ) { entry ->
                    CollectionScreen(
                        kind = entry.arguments?.getString("kind") ?: "liked",
                        playlistId = entry.arguments?.getLong("id") ?: -1L,
                        bottomPadding = bottom,
                        onBack = { nav.popBackStack() },
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = expanded && state.current != null,
            enter = slideInVertically(tween(340, easing = FastOutSlowInEasing)) { it } + fadeIn(tween(200)),
            exit = slideOutVertically(tween(280, easing = FastOutSlowInEasing)) { it } + fadeOut(tween(200)),
        ) {
            NowPlayingScreen(onCollapse = { expanded = false })
        }
    }
}

private fun NavHostController.openRemote(c: YtCollection) {
    fun e(v: String) = Uri.encode(v)
    navigate("remote?id=${e(c.id)}&q=${if (c.isQueue) "1" else "0"}&t=${e(c.title)}&s=${e(c.subtitle)}&th=${e(c.thumbnail)}")
}

@Composable
private fun BottomNav(nav: NavHostController) {
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val haptics = LocalHapticFeedback.current
    Row(
        Modifier
            .fillMaxWidth()
            .height(64.dp)
            .shadow(16.dp, CircleShape)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.97f))
            .border(1.dp, Color.White.copy(alpha = 0.07f), CircleShape)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Tabs.forEach { tab ->
            val selected = route == tab.route || (tab.route == "library" && route?.startsWith("collection") == true)
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {
                        if (!selected) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        nav.navigate(tab.route) {
                            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                NavPill(tab, selected)
            }
        }
    }
}

@Composable
private fun NavPill(tab: Tab, selected: Boolean) {
    val container by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
        tween(250), label = "navBg",
    )
    val content by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
        tween(250), label = "navFg",
    )
    Row(
        Modifier
            .clip(CircleShape)
            .background(container)
            .animateContentSize(spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (selected) tab.selected else tab.unselected, tab.label, tint = content, modifier = Modifier.size(24.dp))
        if (selected) {
            Spacer(Modifier.width(8.dp))
            Text(tab.label, style = MaterialTheme.typography.labelLarge, color = content, maxLines = 1)
        }
    }
}
