package id.tntwindow.editor.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import id.tntwindow.editor.BuildConfig
import id.tntwindow.editor.EditorState
import id.tntwindow.editor.TntViewModel
import id.tntwindow.editor.domain.BackupItem
import id.tntwindow.editor.domain.Paths
import id.tntwindow.editor.ui.components.AppIcon
import id.tntwindow.editor.ui.components.Panel
import id.tntwindow.editor.ui.components.PathText
import id.tntwindow.editor.ui.components.PrimaryButton
import id.tntwindow.editor.ui.components.IssuesDialog
import id.tntwindow.editor.ui.components.RowSwitch
import id.tntwindow.editor.ui.components.SettingsGroup
import id.tntwindow.editor.ui.components.SettingsInfoRow
import id.tntwindow.editor.ui.components.SmallTitle
import id.tntwindow.editor.ui.components.SpecEditorDialog
import id.tntwindow.editor.ui.theme.Danger
import id.tntwindow.editor.ui.theme.Mono
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsPage(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Scaffold(
        containerColor = scheme.background,
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = scheme.background),
            )
        },
    ) { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}


@Composable
fun DefaultAddSettingsScreen(state: EditorState, vm: TntViewModel, onBack: () -> Unit) {
    var autoAdd by remember { mutableStateOf(vm.prefs.autoAddEnabled) }
    var defaultCol by remember { mutableStateOf(vm.prefs.defaultCollectionId) }
    var editingDefaultSpec by remember { mutableStateOf(false) }
    SettingsPage("默认添加", onBack) {
        Panel {
            RowSwitch("未配置的应用自动进合集", autoAdd) {
                autoAdd = it
                vm.prefs.autoAddEnabled = it
            }
            Spacer(Modifier.height(8.dp))
            PrimaryButton("编辑默认窗口规格", onClick = { editingDefaultSpec = true }, modifier = Modifier.fillMaxWidth())
        }
        SmallTitle("默认合集")
        SettingsGroup {
            state.collections.forEachIndexed { i, col ->
                SettingsInfoRow(
                    title = col.name,
                    subtitle = if (defaultCol == col.id) "已选" else "",
                    onClick = {
                        defaultCol = col.id
                        vm.prefs.defaultCollectionId = col.id
                    },
                    trailing = if (defaultCol == col.id) "✓" else null,
                    divider = i != state.collections.lastIndex,
                )
            }
        }
    }
    if (editingDefaultSpec) {
        SpecEditorDialog(
            title = "默认窗口规格",
            spec = vm.defaultSpec(),
            onDismiss = { editingDefaultSpec = false },
            onConfirm = {
                vm.setDefaultSpec(it)
                editingDefaultSpec = false
            },
        )
    }
}

@Composable
fun BackupSettingsScreen(
    state: EditorState,
    vm: TntViewModel,
    onExport: () -> Unit,
    onImport: () -> Unit,
    onBack: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    var backupEvery by remember { mutableStateOf(vm.prefs.backupEveryWrite) }
    var restore by remember { mutableStateOf<BackupItem?>(null) }
    var deleteItem by remember { mutableStateOf<BackupItem?>(null) }
    SettingsPage("备份与恢复", onBack) {
        Panel {
            Text("原件备份不可删除。相同内容不会重复存。完整备份包含窗口 XML、合集、语音、WebView 和应用设置。")
            RowSwitch("每次修改都备份", backupEvery) {
                backupEvery = it
                vm.prefs.backupEveryWrite = it
            }
            Spacer(Modifier.height(8.dp))
            PrimaryButton("立即手动备份 XML", onClick = { vm.manualBackup() }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            PrimaryButton("完整备份", onClick = { vm.fullBackup() }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            PrimaryButton("导出当前 XML", onClick = onExport, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            PrimaryButton("导入 XML", onClick = onImport, modifier = Modifier.fillMaxWidth())
        }
        Panel {
            Text("备份列表", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            if (state.backups.isEmpty()) {
                Text("还没有备份", color = scheme.onSurfaceVariant)
            }
            state.backups.forEach { item ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f).clickable { restore = item }) {
                        Text(item.name)
                        Text(
                            item.kind + " · " + SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(item.createdAt)),
                            color = scheme.onSurfaceVariant,
                        )
                    }
                    if (item.kind != "ORIGINAL") {
                        IconButton(onClick = { deleteItem = item }) {
                            Icon(Icons.Outlined.Delete, contentDescription = "删除备份", tint = Danger)
                        }
                    }
                }
            }
        }
    }
    val item = restore
    if (item != null) {
        AlertDialog(
            onDismissRequest = { restore = null },
            title = { Text("恢复备份") },
            text = { Text(if (item.kind == "FULL") "将恢复窗口 XML、合集、语音、WebView 和应用设置：「" + item.name + "」。" else "将把系统 XML 恢复为「" + item.name + "」。") },
            confirmButton = {
                TextButton(onClick = {
                    vm.restore(item)
                    restore = null
                }) { Text("恢复") }
            },
            dismissButton = { TextButton(onClick = { restore = null }) { Text("取消") } },
        )
    }
    val del = deleteItem
    if (del != null) {
        AlertDialog(
            onDismissRequest = { deleteItem = null },
            title = { Text("删除备份") },
            text = { Text("删除「" + del.name + "」。原件备份不能删。") },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteBackup(del)
                    deleteItem = null
                }) { Text("删除", color = Danger) }
            },
            dismissButton = { TextButton(onClick = { deleteItem = null }) { Text("取消") } },
        )
    }
}

@Composable
fun TntSettingsScreen(state: EditorState, vm: TntViewModel, onBack: () -> Unit) {
    var cmd by remember { mutableStateOf(vm.prefs.restartCommand) }
    var ask by remember { mutableStateOf(vm.prefs.askRestartAfterWrite) }
    var lock by remember(state.homeLockEnabled) { mutableStateOf(state.homeLockEnabled) }
    var overlay by remember(state.overlayEnabled) { mutableStateOf(state.overlayEnabled) }
    SettingsPage("TNT", onBack) {
        Panel {
            RowSwitch("锁桌面", lock) {
                lock = it
                vm.setHomeLockEnabled(it)
            }
        }
        Panel {
            RowSwitch("开启 overlay", overlay) {
                overlay = it
                vm.setOverlayEnabled(it)
            }
        }
        Panel {
            OutlinedTextField(
                value = cmd,
                onValueChange = {
                    cmd = it
                    vm.prefs.restartCommand = it
                },
                label = { Text("额外重启命令（可选）") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
            )
            Spacer(Modifier.height(8.dp))
            RowSwitch("写盘成功后询问是否退出 TNT", ask) {
                ask = it
                vm.prefs.askRestartAfterWrite = it
            }
            Spacer(Modifier.height(8.dp))
            PrimaryButton("热切换 TNT", onClick = { vm.restartTnt() }, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
fun EditSettingsScreen(state: EditorState, vm: TntViewModel, onBack: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    var issuesOpen by remember { mutableStateOf(false) }
    SettingsPage("编辑", onBack) {
        Panel {
            RowSwitch("显示无桌面图标的应用", state.showNoLauncher) {
                vm.setShowNoLauncher(it)
            }
            RowSwitch("显示未安装但仍在 XML 的配置", state.showUninstalled) {
                vm.setShowUninstalled(it)
            }
        }
        val report = state.report
        if (report != null) {
            Panel(modifier = Modifier.clickable { issuesOpen = true }) {
                Text("解析报告", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text("应用节点 " + report.apps.size)
                Text("重复包名 " + report.duplicatePackages.size)
                Text("脏属性 " + report.dirtyAttrPackages.size)
                Text("视频特例 " + report.videos.size)
                if (report.nestedWarning) Text("嵌套未闭合 " + report.nestedIssues.size + " 处", color = Danger)
            }
        }
    }
    if (issuesOpen) {
        IssuesDialog(
            issues = state.status.issues,
            onDismiss = { issuesOpen = false },
            onRetry = { vm.boot() },
            showRetry = !state.status.editingAllowed,
        )
    }
}

@Composable
fun AboutSettingsScreen(state: EditorState, onBack: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    SettingsPage("关于", onBack) {
        Panel(accent = true) {
            Text("锤子补丁  " + BuildConfig.VERSION_NAME, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            PathText(Paths.SYSTEM)
            Spacer(Modifier.height(8.dp))
            Text("锤子系统补丁工具。改窗口 XML、语音、WebView。原件备份在本应用私有目录，卸载即消失。", color = scheme.onSurfaceVariant)
        }
        if (state.moduleActive) {
            Panel {
                Text("模块已在本应用进程生效", color = id.tntwindow.editor.ui.theme.Ok)
            }
        }
    }
}


