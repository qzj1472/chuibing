package id.tntwindow.editor

import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import id.tntwindow.editor.ui.collection.CollectionDetailScreen
import id.tntwindow.editor.ui.collection.CollectionsScreen
import id.tntwindow.editor.ui.home.HomeScreen
import id.tntwindow.editor.ui.patch.CorePatchScreen
import id.tntwindow.editor.ui.patch.KeyActionScreen
import id.tntwindow.editor.ui.patch.KeyMapScreen
import id.tntwindow.editor.ui.patch.KeyMapSettingsScreen
import id.tntwindow.editor.domain.GestureLabels
import id.tntwindow.editor.ui.patch.GestureScreen
import id.tntwindow.editor.ui.patch.GestureSettingsScreen
import id.tntwindow.editor.ui.patch.FreeformScreen
import id.tntwindow.editor.ui.patch.InstallScreen
import id.tntwindow.editor.ui.patch.InstallPickScreen
import id.tntwindow.editor.ui.patch.UninstallPickScreen
import id.tntwindow.editor.ui.patch.RotationScreen
import id.tntwindow.editor.ui.patch.FreeformSettingsScreen
import id.tntwindow.editor.ui.patch.PatchScreen
import id.tntwindow.editor.ui.search.SearchScreen
import id.tntwindow.editor.ui.settings.AboutSettingsScreen
import id.tntwindow.editor.ui.settings.BackupSettingsScreen
import id.tntwindow.editor.ui.settings.DefaultAddSettingsScreen
import id.tntwindow.editor.ui.settings.EditSettingsScreen
import id.tntwindow.editor.ui.settings.SettingsScreen
import id.tntwindow.editor.ui.settings.TntSettingsScreen
import id.tntwindow.editor.ui.settings.WebViewScreen
import id.tntwindow.editor.ui.settings.VideoSpecialScreen
import id.tntwindow.editor.ui.theme.TntTheme
import id.tntwindow.editor.ui.voice.VoiceLaunchScreen

class MainActivity : ComponentActivity() {
    private val vm: TntViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vm.boot()
        setContent {
            val state by vm.state.collectAsStateWithLifecycle()
            TntTheme(state.themeMode) {
                val scheme = MaterialTheme.colorScheme
                val barLuma = 0.299f * scheme.background.red + 0.587f * scheme.background.green + 0.114f * scheme.background.blue
                val barIcon = if (barLuma >= 0.55f) Color(0xFF1A1A1A) else Color(0xFFE6E6E6)
                val nav = rememberNavController()

                val snack = remember { SnackbarHostState() }
                val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/xml")) { uri: Uri? ->
                    if (uri != null) {
                        val bytes = vm.exportBytes()
                        if (bytes != null) contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                    }
                }
                val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
                    if (uri != null) {
                        val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        if (bytes != null) vm.importBytes(bytes)
                    }
                }
                LaunchedEffect(state.message) {
                    val msg = state.message
                    if (msg != null && !state.askRestart) {
                        snack.showSnackbar(msg)
                        vm.clearMessage()
                    }
                }
                val back = nav.currentBackStackEntryAsState().value
                val route = back?.destination?.route ?: "home"
                val showBar = route == "home" || route == "patch" || route == "settings"
                DisposableEffect(route) {
                    val onGesture = route == "patch/gesture" || route == "patch/gesture/settings" || route.startsWith("patch/gesture/")
                    val onFreeform = route.startsWith("patch/freeform")
                    val owner = this@MainActivity
                    val obs = LifecycleEventObserver { _, e ->
                        when (e) {
                            Lifecycle.Event.ON_RESUME -> {
                                vm.setGestureHighlight(onGesture)
                                vm.setFreeformHighlight(onFreeform)
                            }
                            Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> {
                                vm.setGestureHighlight(false)
                                vm.setFreeformHighlight(false)
                            }
                            else -> {}
                        }
                    }
                    owner.lifecycle.addObserver(obs)
                    val resumed = owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
                    vm.setGestureHighlight(onGesture && resumed)
                    vm.setFreeformHighlight(onFreeform && resumed)
                    onDispose {
                        owner.lifecycle.removeObserver(obs)
                        vm.setGestureHighlight(false)
                        vm.setFreeformHighlight(false)
                    }
                }
                Scaffold(
                    containerColor = scheme.background,
                    snackbarHost = { SnackbarHost(snack) },
                    bottomBar = {
                        if (showBar) {
                            NavigationBar(
                                containerColor = Color.Transparent,
                                contentColor = barIcon,
                                tonalElevation = 0.dp,
                            ) {
                                listOf(
                                    Triple("home", "首页", Icons.Outlined.Home),
                                    Triple("patch", "补丁", Icons.Outlined.Build),
                                    Triple("settings", "设置", Icons.Outlined.Settings),
                                ).forEach { (r, label, icon) ->
                                    NavigationBarItem(
                                        selected = route == r,
                                        onClick = {
                                            nav.navigate(r) {
                                                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                                launchSingleTop = true
                                                restoreState = true
                                            }
                                        },
                                        icon = { Icon(icon, contentDescription = label, tint = barIcon) },
                                        label = { Text(label) },
                                        colors = NavigationBarItemDefaults.colors(
                                            selectedIconColor = barIcon,
                                            unselectedIconColor = barIcon.copy(alpha = 0.55f),
                                            selectedTextColor = barIcon,
                                            unselectedTextColor = barIcon.copy(alpha = 0.55f),
                                            indicatorColor = Color.Transparent,
                                        ),
                                    )
                                }
                            }
                        }
                    },
                ) { pad ->
                    NavHost(
                        navController = nav,
                        startDestination = "home",
                        modifier = Modifier.padding(pad),
                        enterTransition = {
                            val from = initialState.destination.route
                            val to = targetState.destination.route
                            val tabs = setOf("home", "patch", "settings")
                            if (from in tabs && to in tabs) fadeIn(animationSpec = tween(140))
                            else fadeIn(animationSpec = tween(160)) + slideInHorizontally(animationSpec = tween(180)) { it / 14 }
                        },
                        exitTransition = {
                            val from = initialState.destination.route
                            val to = targetState.destination.route
                            val tabs = setOf("home", "patch", "settings")
                            if (from in tabs && to in tabs) fadeOut(animationSpec = tween(120))
                            else fadeOut(animationSpec = tween(140))
                        },
                        popEnterTransition = { fadeIn(animationSpec = tween(160)) },
                        popExitTransition = {
                            fadeOut(animationSpec = tween(140)) + slideOutHorizontally(animationSpec = tween(180)) { it / 14 }
                        },
                    ) {
                        composable("home") {
                            HomeScreen(
                                state,
                                onRetry = { vm.boot() },
                            )
                        }
                        composable("collections") {
                            CollectionsScreen(
                                collections = state.collections,
                                editingAllowed = state.status.editingAllowed,
                                vm = vm,
                                videoCount = state.videos.size,
                                onOpen = { nav.navigate("collection/" + it) },
                                onCreate = { name, spec ->
                                    val id = vm.createSpecCollection(name, spec)
                                    nav.navigate("collection/" + id)
                                },
                                onSave = { vm.applyAllCollections() },
                                onRestart = { vm.restartTnt() },
                                onVideos = { nav.navigate("videos") },
                                onBack = { nav.popBackStack() },
                            )
                        }
                        composable("collection/{id}") { entry ->
                            val id = entry.arguments?.getString("id") ?: return@composable
                            val col = vm.collection(id)
                            if (col != null) {
                                CollectionDetailScreen(
                                    col = col,
                                    vm = vm,
                                    installed = state.installed,
                                    allCollections = state.collections,
                                    onBack = { nav.popBackStack() },
                                )
                            }
                        }
                        composable("patch") {
                            PatchScreen(
                                state = state,
                                vm = vm,
                                onOpen = { nav.navigate(it) },
                            )
                        }

                        composable("settings") {
                            SettingsScreen(
                                state = state,
                                vm = vm,
                                onOpen = { nav.navigate(it) },
                            )
                        }

                        composable("settings/defaults") {
                            DefaultAddSettingsScreen(state, vm, onBack = { nav.popBackStack() })
                        }
                        composable("settings/backup") {
                            BackupSettingsScreen(
                                state = state,
                                vm = vm,
                                onExport = { export.launch("revone_window_config.xml") },
                                onImport = { importer.launch(arrayOf("text/*", "application/xml")) },
                                onBack = { nav.popBackStack() },
                            )
                        }
                        composable("settings/tnt") {
                            TntSettingsScreen(state, vm, onBack = { nav.popBackStack() })
                        }
                        composable("settings/edit") {
                            EditSettingsScreen(state, vm, onBack = { nav.popBackStack() })
                        }
                        composable("settings/about") {
                            AboutSettingsScreen(state, onBack = { nav.popBackStack() })
                        }
                        composable("settings/webview") {
                            WebViewScreen(vm, onBack = { nav.popBackStack() })
                        }
                        composable("videos") {
                            VideoSpecialScreen(vm, state.videos, onBack = { nav.popBackStack() })
                        }
                        composable("search") {
                            SearchScreen(
                                vm = vm,
                                onBack = { nav.popBackStack() },
                                onOpenCollection = { nav.navigate("collection/" + it) },
                            )
                        }
                        composable("patch/corepatch") {
                            CorePatchScreen(vm, onBack = { nav.popBackStack() })
                        }
                        composable("voice") {
                            VoiceLaunchScreen(vm, onBack = { nav.popBackStack() })
                        }
                        composable("patch/keys") {
                            KeyMapScreen(
                                vm = vm,
                                onBack = { nav.popBackStack() },
                                onSettings = { nav.navigate("patch/keys/settings") },
                                onAction = { slot, id -> nav.navigate("patch/keys/action/" + slot + "/" + id) },
                            )
                        }
                        composable("patch/keys/settings") {
                            KeyMapSettingsScreen(vm, onBack = { nav.popBackStack() })
                        }
                        composable("patch/keys/action/{slot}/{id}") { entry ->
                            val slot = entry.arguments?.getString("slot") ?: return@composable
                            val id = entry.arguments?.getString("id") ?: return@composable
                            KeyActionScreen(vm, slot, id, onBack = { nav.popBackStack() })
                        }
                        composable("patch/gesture") {
                            GestureScreen(
                                vm = vm,
                                onBack = { nav.popBackStack() },
                                onSettings = { nav.navigate("patch/gesture/settings") },
                                onAction = { edge, slot -> nav.navigate("patch/gesture/action/" + edge + "/" + slot) },
                            )
                        }
                        composable("patch/gesture/settings") {
                            GestureSettingsScreen(vm, onBack = { nav.popBackStack() })
                        }
                        composable("patch/freeform") {
                            FreeformScreen(
                                vm = vm,
                                onBack = { nav.popBackStack() },
                                onSettings = { nav.navigate("patch/freeform/settings") },
                                onAction = { id -> nav.navigate("patch/freeform/action/" + id) },
                            )
                        }
                        composable("patch/freeform/settings") {
                            FreeformSettingsScreen(vm, onBack = { nav.popBackStack() })
                        }
                        composable("patch/freeform/action/{id}") { entry ->
                            val id = entry.arguments?.getString("id") ?: return@composable
                            KeyActionScreen(vm, "dot", "ff:" + id, onBack = { nav.popBackStack() })
                        }
                        composable("patch/uninstall") {
                            InstallScreen(
                                vm,
                                onBack = { nav.popBackStack() },
                                onPickInstall = { nav.navigate("patch/install/handler") },
                                onPickUninstall = { nav.navigate("patch/uninstall/handler") },
                            )
                        }
                        composable("patch/install") {
                            InstallScreen(
                                vm,
                                onBack = { nav.popBackStack() },
                                onPickInstall = { nav.navigate("patch/install/handler") },
                                onPickUninstall = { nav.navigate("patch/uninstall/handler") },
                            )
                        }
                        composable("patch/install/handler") {
                            InstallPickScreen(vm, onBack = { nav.popBackStack() })
                        }
                        composable("patch/uninstall/handler") {
                            UninstallPickScreen(vm, onBack = { nav.popBackStack() })
                        }
                        composable("patch/rotation") {
                            RotationScreen(vm, onBack = { nav.popBackStack() })
                        }
                        composable("patch/gesture/action/{edge}/{slot}") { entry ->
                            val edge = entry.arguments?.getString("edge") ?: return@composable
                            val slot = entry.arguments?.getString("slot") ?: return@composable
                            val hover = GestureLabels.hoverSlot(slot)
                            KeyActionScreen(
                                vm,
                                slot,
                                "g:" + edge,
                                onBack = { nav.popBackStack() },
                                onHover = if (hover != null) {
                                    { nav.navigate("patch/gesture/action/" + edge + "/" + hover) }
                                } else {
                                    null
                                },
                            )
                        }
                    }
                }
                if (state.busy) {
                    Box(
                        Modifier.fillMaxSize().background(scheme.background.copy(alpha = 0.45f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(color = scheme.primary)
                    }
                }
                if (state.showDisclaimer) {
                    AlertDialog(
                        onDismissRequest = { },
                        title = { Text("改系统文件") },
                        text = { Text("Root 下改系统配置。首次会备份原件。") },
                        confirmButton = { TextButton(onClick = { vm.acceptDisclaimer() }) { Text("知道了") } },
                    )
                }
                if (state.askRestart && state.message != null) {
                    AlertDialog(
                        onDismissRequest = { vm.clearMessage() },
                        title = { Text("已保存") },
                        text = { Text("热切换后窗口才会生效。") },
                        confirmButton = {
                            TextButton(onClick = {
                                vm.restartTnt()
                                vm.clearMessage()
                            }) { Text("热切换 TNT") }
                        },
                        dismissButton = { TextButton(onClick = { vm.clearMessage() }) { Text("稍后") } },
                    )
                }
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (vm.state.value.keyCapturing) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                vm.noteCapturedKey(event.keyCode)
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }
}
