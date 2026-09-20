package id.tntwindow.editor.ui.patch

import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.os.Process
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import id.tntwindow.editor.TntViewModel
import id.tntwindow.editor.domain.InstalledApp
import id.tntwindow.editor.domain.KeyAction
import id.tntwindow.editor.domain.GestureLabels
import id.tntwindow.editor.domain.KeyActionCatalog
import id.tntwindow.editor.domain.modeTitle
import id.tntwindow.editor.ui.components.AppIcon
import id.tntwindow.editor.ui.components.Panel
import id.tntwindow.editor.ui.components.PrimaryButton
import id.tntwindow.editor.ui.components.RowSwitch
import id.tntwindow.editor.ui.components.SettingsGroup
import id.tntwindow.editor.ui.components.SettingsInfoRow
import id.tntwindow.editor.ui.components.SmallTitle
import id.tntwindow.editor.ui.settings.SettingsPage
import id.tntwindow.editor.ui.theme.Mono

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun KeyActionScreen(vm: TntViewModel, slot: String, id: String, onBack: () -> Unit, onHover: (() -> Unit)? = null) {
    val state by vm.state.collectAsStateWithLifecycle()
    val cfg = state.keyMap
    val gesture = state.gesture
    val freeform = state.freeform
    val gestureId = id.removePrefix("g:")
    val action = remember(cfg, gesture, freeform, slot, id) {
        val hit = when {
            id.startsWith("g:") -> gesture.edge(gestureId)?.action(slot)
            id.startsWith("ff:") -> when (id.removePrefix("ff:")) {
                "left" -> freeform.left
                "right" -> freeform.right
                else -> null
            }
            else -> when (slot) {
                "click" -> cfg.key(id)?.click
                "double" -> cfg.key(id)?.doubleClick
                "long" -> cfg.key(id)?.longPress
                "triple" -> cfg.key(id)?.tripleClick
                "combo" -> cfg.combo(id)?.action
                else -> null
            }
        }
        hit ?: KeyAction.none()
    }
    val title = when {
        id == "ff:left" -> "左圆点"
        id == "ff:right" -> "右圆点"
        slot == "click" -> "单击"
        slot == "double" -> "双击"
        slot == "long" -> "长按"
        slot == "triple" -> "三击"
        slot == "swipe_in" || slot == "swipe_a" || slot == "swipe_b" || slot == "hover_in" || slot == "hover_a" || slot == "hover_b" -> GestureLabels.slotTitle(gestureId, slot)
        else -> "组合键动作"
    }
    var pickApp by remember { mutableStateOf(false) }
    var pickAppType by remember { mutableStateOf(KeyAction.TYPE_APP) }
    var pickActivity by remember { mutableStateOf(false) }
    var pickShortcut by remember { mutableStateOf(false) }
    var modePick by remember { mutableStateOf<String?>(null) }
    var shellDlg by remember { mutableStateOf(false) }
    var shell by remember(action.cmd, action.type) { mutableStateOf(action.cmd) }
    var shellRoot by remember(action.root, action.type) { mutableStateOf(action.root) }
    var shellSystem by remember(action.system, action.type) { mutableStateOf(action.system) }
    var input by remember(action.text, action.type) { mutableStateOf(action.text) }
    fun apply(next: KeyAction) {
        when {
            id.startsWith("g:") -> vm.setGestureAction(gestureId, slot, next)
            id.startsWith("ff:") -> vm.setFreeformDot(id.removePrefix("ff:"), next)
            else -> vm.setKeyAction(slot, id, next)
        }
    }
    SettingsPage(title, onBack) {
        if (onHover != null) {
            val hs = GestureLabels.hoverSlot(slot)
            val hoverAct = if (hs != null) gesture.edge(gestureId)?.action(hs) else null
            SmallTitle("附加")
            SettingsGroup {
                SettingsInfoRow(
                    title = "悬停",
                    subtitle = hoverAct?.summary() ?: "",
                    onClick = onHover,
                    divider = false,
                )
            }
        }
        KeyActionCatalog.groups.forEach { group ->
            SmallTitle(group.title)
            SettingsGroup {
                group.types.forEachIndexed { i, type ->
                    SettingsInfoRow(
                        title = KeyActionCatalog.title(type),
                        subtitle = if (action.type == type) action.summary() else "",
                        onClick = {
                            when (type) {
                                KeyAction.TYPE_SHELL -> {
                                    shell = if (action.type == KeyAction.TYPE_SHELL) action.cmd else shell
                                    shellRoot = if (action.type == KeyAction.TYPE_SHELL) action.root else false
                                    shellSystem = if (action.type == KeyAction.TYPE_SHELL) action.system else false
                                    shellDlg = true
                                }
                                KeyAction.TYPE_WIFI, KeyAction.TYPE_DATA, KeyAction.TYPE_BT, KeyAction.TYPE_LOCATION, KeyAction.TYPE_FLASHLIGHT, KeyAction.TYPE_MUTE, KeyAction.TYPE_WAKELOCK -> {
                                    modePick = type
                                }
                                KeyAction.TYPE_APP, KeyAction.TYPE_APP_FREEFORM, KeyAction.TYPE_APP_TNT -> {
                                    apply(action.copy(type = type))
                                    pickAppType = type
                                    pickApp = true
                                }
                                KeyAction.TYPE_ACTIVITY -> {
                                    apply(action.copy(type = type))
                                    pickActivity = true
                                }
                                KeyAction.TYPE_SHORTCUT -> {
                                    apply(action.copy(type = type))
                                    pickShortcut = true
                                }
                                KeyAction.TYPE_INPUT -> apply(action.copy(type = type, text = input))
                                else -> apply(KeyAction(type = type))
                            }
                        },
                        trailing = if (action.type == type) "✓" else null,
                        divider = i != group.types.lastIndex,
                    )
                }
            }
        }
        if (action.type == KeyAction.TYPE_APP || action.type == KeyAction.TYPE_APP_FREEFORM || action.type == KeyAction.TYPE_APP_TNT) {
            Panel {
                Text(if (action.pkg.isBlank()) "还没选应用" else action.pkg)
                Spacer(Modifier.height(8.dp))
                PrimaryButton("选择应用", onClick = { pickAppType = action.type; pickApp = true }, modifier = Modifier.fillMaxWidth())
            }
        }
        if (action.type == KeyAction.TYPE_ACTIVITY) {
            Panel {
                Text(if (action.cls.isBlank()) "还没选活动" else action.pkg + " / " + action.cls)
                Spacer(Modifier.height(8.dp))
                PrimaryButton("选择活动", onClick = { pickActivity = true }, modifier = Modifier.fillMaxWidth())
            }
        }
        if (action.type == KeyAction.TYPE_SHORTCUT) {
            Panel {
                Text(if (action.intent.isBlank() && action.shortcutId.isBlank()) "还没选快捷方式" else action.shortcutId.ifBlank { action.intent })
                Spacer(Modifier.height(8.dp))
                PrimaryButton("选择快捷方式", onClick = { pickShortcut = true }, modifier = Modifier.fillMaxWidth())
            }
        }
        if (action.type == KeyAction.TYPE_INPUT) {
            Panel {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    label = { Text("文字") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                )
                Spacer(Modifier.height(8.dp))
                PrimaryButton("保存", onClick = { apply(action.copy(text = input)) }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
    val picking = modePick
    if (picking != null) {
        AlertDialog(
            onDismissRequest = { modePick = null },
            title = { Text(KeyActionCatalog.title(picking)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(KeyAction.MODE_TOGGLE, KeyAction.MODE_ON, KeyAction.MODE_OFF).forEach { mode ->
                        PrimaryButton(modeTitle(mode), onClick = {
                            apply(KeyAction(type = picking, mode = mode))
                            modePick = null
                        }, modifier = Modifier.fillMaxWidth())
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { modePick = null }) { Text("取消") } },
        )
    }
    if (shellDlg) {
        AlertDialog(
            onDismissRequest = { shellDlg = false },
            title = { Text("Shell 指令") },
            text = {
                Column {
                    OutlinedTextField(
                        value = shell,
                        onValueChange = { shell = it },
                        label = { Text("指令") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                    )
                    RowSwitch("以 Root 运行", shellRoot) {
                        shellRoot = it
                        if (it) shellSystem = false
                    }
                    RowSwitch("以系统运行", shellSystem) {
                        shellSystem = it
                        if (it) shellRoot = false
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    apply(KeyAction(type = KeyAction.TYPE_SHELL, cmd = shell, root = shellRoot, system = shellSystem))
                    shellDlg = false
                }) { Text("保存") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { vm.testShell(shell, shellRoot, shellSystem) }, enabled = shell.isNotBlank()) { Text("运行") }
                    TextButton(onClick = { shellDlg = false }) { Text("取消") }
                }
            },
        )
    }
    if (pickApp) {
        AppPickDialog(
            installed = state.installed,
            onDismiss = { pickApp = false },
            onPick = { app ->
                apply(action.copy(type = pickAppType, pkg = app.packageName))
                pickApp = false
            },
        )
    }
    if (pickActivity) {
        ActivityPickDialog(
            installed = state.installed,
            onDismiss = { pickActivity = false },
            onPick = { pkg, cls ->
                apply(action.copy(type = KeyAction.TYPE_ACTIVITY, pkg = pkg, cls = cls))
                pickActivity = false
            },
        )
    }
    if (pickShortcut) {
        ShortcutPickDialog(
            installed = state.installed,
            onDismiss = { pickShortcut = false },
            onPick = { pkg, shortcutId, intent ->
                apply(action.copy(type = KeyAction.TYPE_SHORTCUT, pkg = pkg, shortcutId = shortcutId, intent = intent))
                pickShortcut = false
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppPickDialog(installed: List<InstalledApp>, onDismiss: () -> Unit, onPick: (InstalledApp) -> Unit) {
    var tab by remember { mutableStateOf(0) }
    var query by remember { mutableStateOf("") }
    val filtered = remember(installed, tab, query) {
        installed
            .filter { app -> if (tab == 0) !app.system else app.system }
            .filter { app ->
                query.isBlank() || app.label.contains(query, true) || app.packageName.contains(query, true)
            }
            .sortedBy { it.label }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(color = MaterialTheme.colorScheme.background, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth(0.94f).fillMaxHeight(0.92f)) {
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("选择应用", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("关闭") }
                }
                TabRow(selectedTabIndex = tab) {
                    Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("用户") })
                    Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("系统") })
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("搜索") })
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(filtered, key = { it.packageName }) { app ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onPick(app) }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AppIcon(app.packageName, 36)
                            Column(Modifier.padding(start = 10.dp)) {
                                Text(app.label)
                                Text(app.packageName, style = Mono, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}

private data class ActItem(val pkg: String, val cls: String, val label: String)
private data class ShotItem(val pkg: String, val id: String, val label: String, val intent: String)

@Composable
private fun ActivityPickDialog(
    installed: List<InstalledApp>,
    onDismiss: () -> Unit,
    onPick: (String, String) -> Unit,
) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var customPkg by remember { mutableStateOf("") }
    var customCls by remember { mutableStateOf("") }
    var openPkg by remember { mutableStateOf<String?>(null) }
    var acts by remember { mutableStateOf<List<ActItem>>(emptyList()) }
    val apps = remember(installed, query) {
        installed
            .filter { app -> query.isBlank() || app.label.contains(query, true) || app.packageName.contains(query, true) }
            .sortedBy { it.label }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(color = MaterialTheme.colorScheme.background, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth(0.94f).fillMaxHeight(0.92f)) {
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("选择活动", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("关闭") }
                }
                Panel {
                    Text("自定义")
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = customPkg, onValueChange = { customPkg = it }, label = { Text("包名") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = customCls, onValueChange = { customCls = it }, label = { Text("Activity") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    PrimaryButton("使用自定义", onClick = { onPick(customPkg.trim(), customCls.trim()) }, modifier = Modifier.fillMaxWidth(), enabled = customPkg.isNotBlank() && customCls.isNotBlank())
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("搜索软件") })
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(apps, key = { it.packageName }) { app ->
                        Column(Modifier.fillMaxWidth()) {
                            Row(
                                Modifier.fillMaxWidth().clickable {
                                    if (openPkg == app.packageName) {
                                        openPkg = null
                                    } else {
                                        openPkg = app.packageName
                                        acts = loadActivities(context.packageManager, app.packageName)
                                    }
                                }.padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                AppIcon(app.packageName, 36)
                                Column(Modifier.padding(start = 10.dp).weight(1f)) {
                                    Text(app.label)
                                    Text(app.packageName, style = Mono, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Text(if (openPkg == app.packageName) "▾" else "›", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (openPkg == app.packageName) {
                                acts.forEach { act ->
                                    Column(
                                        Modifier.fillMaxWidth().clickable { onPick(act.pkg, act.cls) }.padding(start = 46.dp, top = 6.dp, bottom = 6.dp, end = 8.dp),
                                    ) {
                                        Text(act.label)
                                        Text(act.cls, style = Mono, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                if (acts.isEmpty()) {
                                    Text("没有活动", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 46.dp, bottom = 8.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ShortcutPickDialog(
    installed: List<InstalledApp>,
    onDismiss: () -> Unit,
    onPick: (String, String, String) -> Unit,
) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var custom by remember { mutableStateOf("") }
    var openPkg by remember { mutableStateOf<String?>(null) }
    var shots by remember { mutableStateOf<List<ShotItem>>(emptyList()) }
    val apps = remember(installed, query) {
        installed
            .filter { it.hasLauncher }
            .filter { app -> query.isBlank() || app.label.contains(query, true) || app.packageName.contains(query, true) }
            .sortedBy { it.label }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(color = MaterialTheme.colorScheme.background, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth(0.94f).fillMaxHeight(0.92f)) {
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("选择快捷方式", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("关闭") }
                }
                Panel {
                    OutlinedTextField(value = custom, onValueChange = { custom = it }, label = { Text("自定义 Intent URI") }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    PrimaryButton("使用自定义", onClick = { onPick("", "", custom.trim()) }, modifier = Modifier.fillMaxWidth(), enabled = custom.isNotBlank())
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("搜索软件") })
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(apps, key = { it.packageName }) { app ->
                        Column(Modifier.fillMaxWidth()) {
                            Row(
                                Modifier.fillMaxWidth().clickable {
                                    if (openPkg == app.packageName) {
                                        openPkg = null
                                    } else {
                                        openPkg = app.packageName
                                        shots = loadShortcuts(context, app.packageName)
                                    }
                                }.padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                AppIcon(app.packageName, 36)
                                Column(Modifier.padding(start = 10.dp).weight(1f)) {
                                    Text(app.label)
                                    Text(app.packageName, style = Mono, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Text(if (openPkg == app.packageName) "▾" else "›", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (openPkg == app.packageName) {
                                shots.forEach { shot ->
                                    Column(
                                        Modifier.fillMaxWidth().clickable { onPick(shot.pkg, shot.id, shot.intent) }.padding(start = 46.dp, top = 6.dp, bottom = 6.dp, end = 8.dp),
                                    ) {
                                        Text(shot.label)
                                        Text(shot.id.ifBlank { shot.intent }, style = Mono, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                if (shots.isEmpty()) {
                                    Text("没有快捷方式", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 46.dp, bottom = 8.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun loadActivities(pm: PackageManager, pkg: String): List<ActItem> {
    return try {
        val info = pm.getPackageInfo(pkg, PackageManager.GET_ACTIVITIES)
        info.activities.orEmpty().map { a ->
            val label = try { a.loadLabel(pm).toString() } catch (_: Exception) { a.name }
            ActItem(pkg, a.name, label.ifBlank { a.name.substringAfterLast('.') })
        }
    } catch (_: Exception) {
        emptyList()
    }
}

private fun loadShortcuts(context: android.content.Context, pkg: String): List<ShotItem> {
    val out = mutableListOf<ShotItem>()
    val pm = context.packageManager
    try {
        val launch = pm.getLaunchIntentForPackage(pkg)
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            out += ShotItem(pkg, "launch", "打开应用", launch.toUri(Intent.URI_INTENT_SCHEME))
        }
    } catch (_: Exception) {
    }
    try {
        val la = context.getSystemService(LauncherApps::class.java)
        val q = android.content.pm.LauncherApps.ShortcutQuery()
            .setPackage(pkg)
            .setQueryFlags(
                android.content.pm.LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or
                    android.content.pm.LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST or
                    android.content.pm.LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED
            )
        la.getShortcuts(q, Process.myUserHandle()).orEmpty().forEach { sc ->
            val intent = sc.intent?.toUri(Intent.URI_INTENT_SCHEME) ?: ""
            out += ShotItem(pkg, sc.id.orEmpty(), sc.shortLabel?.toString() ?: sc.id.orEmpty(), intent)
        }
    } catch (_: Exception) {
    }
    try {
        val intent = Intent(Intent.ACTION_CREATE_SHORTCUT).setPackage(pkg)
        pm.queryIntentActivities(intent, 0).forEach { ri ->
            val cls = ri.activityInfo.name
            val label = ri.loadLabel(pm).toString()
            val i = Intent().setClassName(pkg, cls)
            out += ShotItem(pkg, cls, label, i.toUri(Intent.URI_INTENT_SCHEME))
        }
    } catch (_: Exception) {
    }
    return out.distinctBy { it.id + it.intent }
}


