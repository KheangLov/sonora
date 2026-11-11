package com.sonora.music.ui

import android.Manifest
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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
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
                Column(
                    Modifier.background(Brush.verticalGradient(listOf(Color.Transparent, bg.copy(alpha = 0.94f), bg)))
                ) {
                    AnimatedVisibility(
                        visible = state.current != null,
                        enter = slideInVertically { it } + fadeIn(),
                        exit = slideOutVertically { it } + fadeOut(),
                    ) {
                        MiniPlayer(state, onExpand = { expanded = true })
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
                    HomeScreen(bottom, onOpenCollection = { nav.navigate("collection/$it") })
                }
                composable("search") { SearchScreen(bottom) }
                composable("library") {
                    LibraryScreen(
                        bottom,
                        onOpenCollection = { nav.navigate("collection/$it") },
                        onOpenPlaylist = { nav.navigate("collection/playlist?id=$it") },
                    )
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

@Composable
private fun BottomNav(nav: NavHostController) {
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    NavigationBar(containerColor = Color.Transparent, tonalElevation = 0.dp) {
        Tabs.forEach { tab ->
            val selected = route == tab.route || (tab.route == "library" && route?.startsWith("collection") == true)
            NavigationBarItem(
                selected = selected,
                onClick = {
                    nav.navigate(tab.route) {
                        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                icon = { Icon(if (selected) tab.selected else tab.unselected, tab.label) },
                label = { Text(tab.label) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.onBackground,
                    selectedTextColor = MaterialTheme.colorScheme.onBackground,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    indicatorColor = Color.Transparent,
                ),
            )
        }
    }
}
