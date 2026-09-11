package id.tntwindow.editor

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Dashboard
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import id.tntwindow.editor.ui.collection.CollectionDetailScreen
import id.tntwindow.editor.ui.collection.CollectionsScreen
import id.tntwindow.editor.ui.home.HomeScreen
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
                val showBar = route == "home" || route == "collections" || route == "patch" || route == "settings"
                Scaffold(
                    containerColor = scheme.background,
                    snackbarHost = { SnackbarHost(snack) },
                    bottomBar = {
                        if (showBar) {
                            NavigationBar(containerColor = scheme.surface) {
                                listOf(
                                    Triple("home", "首页", Icons.Outlined.Home),
                                    Triple("collections", "窗口", Icons.Outlined.Dashboard),
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
                                        icon = { Icon(icon, contentDescription = label) },
                                        label = { Text(label) },
                                        colors = NavigationBarItemDefaults.colors(
                                            indicatorColor = scheme.primary.copy(alpha = 0.22f),
                                        ),
                                    )
                                }
                            }
                        }
                    },
                ) { pad ->
                    NavHost(nav, startDestination = "home", modifier = Modifier.padding(pad)) {
                        composable("home") {
                            HomeScreen(
                                state,
                                onSave = { vm.applyAllCollections() },
                                onRestart = { vm.restartTnt() },
                                onRetry = { vm.boot() },
                                onSetMode = { vm.setTntMode(it) },
                                onTouchpad = { vm.openTouchpad() },
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
                        composable("voice") {
                            VoiceLaunchScreen(vm, onBack = { nav.popBackStack() })
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
}
