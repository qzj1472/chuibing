package id.tntwindow.editor.ui.patch

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import id.tntwindow.editor.TntViewModel
import id.tntwindow.editor.domain.FreeformConfig
import id.tntwindow.editor.domain.InstalledApp
import id.tntwindow.editor.ui.components.AppIcon
import id.tntwindow.editor.ui.components.Panel
import id.tntwindow.editor.ui.components.PrimaryButton
import id.tntwindow.editor.ui.components.SettingsGroup
import id.tntwindow.editor.ui.components.SettingsInfoRow
import id.tntwindow.editor.ui.components.SettingsNavRow
import id.tntwindow.editor.ui.components.SettingsSwitchRow
import id.tntwindow.editor.ui.components.SmallTitle
import id.tntwindow.editor.ui.settings.SettingsPage
import id.tntwindow.editor.ui.theme.Mono
import java.util.LinkedHashSet

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FreeformScreen(
    vm: TntViewModel,
    onBack: () -> Unit,
    onSettings: () -> Unit,
    onAction: (String) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val state by vm.state.collectAsStateWithLifecycle()
    val cfg = state.freeform
    var pickApp by remember { mutableStateOf(false) }
    Scaffold(
        containerColor = scheme.background,
        topBar = {
            TopAppBar(
                title = { Text("小窗") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    TextButton(onClick = { vm.resetFreeform() }) { Text("重置") }
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Outlined.Settings, contentDescription = "设置")
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
        ) {
            SettingsGroup {
                SettingsSwitchRow(
                    title = "启用侧边栏",
                    checked = cfg.sidebar,
                    onChange = { vm.setFreeformSidebar(it) },
                    divider = false,
                    subtitle = "默认显示小白条。触控优先于全面屏手势。",
                )
            }
            SmallTitle("圆点")
            SettingsGroup {
                SettingsNavRow("左圆点", cfg.left.summary(), { onAction("left") })
                SettingsNavRow("右圆点", cfg.right.summary(), { onAction("right") }, divider = false)
            }
            SmallTitle("侧边栏应用")
            SettingsGroup {
                if (cfg.apps.isEmpty()) {
                    SettingsInfoRow("还没添加应用", "点下方按钮加入，侧边栏里点图标开小窗", divider = false)
                } else {
                    cfg.apps.forEachIndexed { i, pkg ->
                        val app = state.installed.firstOrNull { it.packageName == pkg }
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AppIcon(pkg, 36)
                            Column(Modifier.weight(1f).padding(start = 10.dp, end = 8.dp)) {
                                Text(app?.label ?: pkg)
                                Text(pkg, style = Mono, color = scheme.onSurfaceVariant)
                            }
                            TextButton(onClick = { vm.removeFreeformApp(pkg) }) { Text("移除") }
                        }
                        if (i != cfg.apps.lastIndex) {
                            Spacer(Modifier.height(0.dp))
                        }
                    }
                }
            }
            PrimaryButton("添加应用", onClick = { pickApp = true }, modifier = Modifier.fillMaxWidth())
        }
    }
    if (pickApp) {
        FreeformAppPickDialog(
            installed = state.installed,
            selectedPkgs = cfg.apps.toSet(),
            onDismiss = { pickApp = false },
            onConfirm = { pkgs ->
                vm.addFreeformApps(pkgs)
                pickApp = false
            },
        )
    }
}

@Composable
fun FreeformSettingsScreen(vm: TntViewModel, onBack: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val cfg = state.freeform
    var width by remember(cfg.widthPct) { mutableFloatStateOf(cfg.widthPct.toFloat()) }
    var height by remember(cfg.heightPct) { mutableFloatStateOf(cfg.heightPct.toFloat()) }
    var maxWin by remember(cfg.maxWindows) { mutableFloatStateOf(cfg.maxWindows.toFloat()) }
    var top by remember(cfg.sidebarTopPct) { mutableFloatStateOf(cfg.sidebarTopPct.toFloat()) }
    SettingsPage("小窗设置", onBack, onReset = { vm.resetFreeform() }) {
        SmallTitle("窗口")
        Panel {
            Text("窗口宽度  " + width.toInt() + " %")
            Slider(
                value = width,
                onValueChange = { width = it },
                valueRange = 40f..95f,
                onValueChangeFinished = { vm.setFreeformSize(widthPct = width.toInt()) },
            )
            Spacer(Modifier.height(8.dp))
            Text("窗口高度  " + height.toInt() + " %")
            Slider(
                value = height,
                onValueChange = { height = it },
                valueRange = 35f..90f,
                onValueChangeFinished = { vm.setFreeformSize(heightPct = height.toInt()) },
            )
            Spacer(Modifier.height(8.dp))
            Text("小窗数量上限  " + maxWin.toInt())
            Slider(
                value = maxWin,
                onValueChange = { maxWin = it },
                valueRange = 1f..8f,
                steps = 6,
                onValueChangeFinished = { vm.setFreeformSize(maxWindows = maxWin.toInt()) },
            )
        }
        SmallTitle("侧边栏")
        SettingsGroup {
            SettingsInfoRow(
                title = "左侧",
                subtitle = "小白条在左边缘",
                onClick = { vm.setFreeformSide(FreeformConfig.SIDE_LEFT) },
                trailing = if (cfg.sidebarSide == FreeformConfig.SIDE_LEFT) "✓" else null,
            )
            SettingsInfoRow(
                title = "右侧",
                subtitle = "小白条在右边缘",
                onClick = { vm.setFreeformSide(FreeformConfig.SIDE_RIGHT) },
                trailing = if (cfg.sidebarSide == FreeformConfig.SIDE_RIGHT) "✓" else null,
            )
            SettingsSwitchRow(
                title = "锁屏时可用",
                checked = cfg.sidebarLockscreen,
                onChange = { vm.setFreeformSidebarLockscreen(it) },
                divider = false,
                subtitle = "默认关闭。打开后锁屏界面也能用侧边栏。",
            )
        }
        Panel {
            Text("高度位置  " + top.toInt() + " %")
            Slider(
                value = top,
                onValueChange = { top = it },
                valueRange = 0f..90f,
                onValueChangeFinished = { vm.setFreeformTopPct(top.toInt()) },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FreeformAppPickDialog(
    installed: List<InstalledApp>,
    selectedPkgs: Set<String>,
    onDismiss: () -> Unit,
    onConfirm: (List<String>) -> Unit,
) {
    var tab by remember { mutableStateOf(0) }
    var query by remember { mutableStateOf("") }
    val selected = remember { mutableStateOf(LinkedHashSet(selectedPkgs)) }
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
                    Text("添加应用", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("关闭") }
                }
                TabRow(selectedTabIndex = tab) {
                    Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("用户") })
                    Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("系统") })
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("搜索") })
                Spacer(Modifier.height(8.dp))
                if (installed.isEmpty()) {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text("正在读取")
                    }
                } else {
                    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(filtered, key = { it.packageName }) { app ->
                            val checked = selected.value.contains(app.packageName)
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        val n = selected.value.toMutableSet()
                                        if (checked) n.remove(app.packageName) else n.add(app.packageName)
                                        selected.value = LinkedHashSet(n)
                                    }
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(checked = checked, onCheckedChange = null)
                                AppIcon(app.packageName, 36)
                                Column(Modifier.padding(start = 10.dp).weight(1f)) {
                                    Text(app.label)
                                    Text(app.packageName, style = Mono, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
                Text("已选 " + selected.value.size + " 个")
                Spacer(Modifier.height(8.dp))
                PrimaryButton(
                    "添加",
                    onClick = { onConfirm(selected.value.toList()) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = selected.value.any { it !in selectedPkgs },
                )
            }
        }
    }
}
