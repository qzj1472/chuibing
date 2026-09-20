package id.tntwindow.editor.ui.patch

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import id.tntwindow.editor.TntViewModel
import id.tntwindow.editor.domain.GestureEdge
import id.tntwindow.editor.domain.GestureLabels
import id.tntwindow.editor.ui.components.Panel
import id.tntwindow.editor.ui.components.SettingsGroup
import id.tntwindow.editor.ui.components.SettingsNavRow
import id.tntwindow.editor.ui.components.SettingsSwitchRow
import id.tntwindow.editor.ui.components.SmallTitle
import id.tntwindow.editor.ui.settings.SettingsPage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GestureScreen(
    vm: TntViewModel,
    onBack: () -> Unit,
    onSettings: () -> Unit,
    onAction: (String, String) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val state by vm.state.collectAsStateWithLifecycle()
    val cfg = state.gesture
    var expand by remember { mutableStateOf<String?>(null) }
    Scaffold(
        containerColor = scheme.background,
        topBar = {
            TopAppBar(
                title = { Text("全面屏手势") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    TextButton(onClick = { vm.resetGesture() }) { Text("重置") }
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
                    title = "启用全面屏手势",
                    checked = cfg.enabled,
                    onChange = { vm.setGestureEnabled(it) },
                    divider = false,
                    subtitle = "三分块优先，整边更低。滑动可再加悬停。改完立刻生效，需 LSPosed 对 android 生效并重启一次。",
                )
            }
            cfg.uiGroups().forEach { (title, ids) ->
                SmallTitle(title)
                ids.forEach { id ->
                    val edge = cfg.edge(id) ?: return@forEach
                    GestureEdgeCard(
                        edge = edge,
                        expanded = expand == edge.id,
                        onToggle = { expand = if (expand == edge.id) null else edge.id },
                        onEnabled = { vm.setGestureEdgeEnabled(edge.id, it) },
                        onAction = onAction,
                    )
                }
            }
        }
    }
}

@Composable
private fun GestureEdgeCard(
    edge: GestureEdge,
    expanded: Boolean,
    onToggle: () -> Unit,
    onEnabled: (Boolean) -> Unit,
    onAction: (String, String) -> Unit,
) {
    val slots = edge.slots()
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).clickable(onClick = onToggle)) {
                Text(GestureLabels.edgeTitle(edge.id), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(2.dp))
                Text(
                    if (!edge.enabled) "关闭" else slots.joinToString(" · ") { GestureLabels.slotTitle(edge.id, it) + " " + edge.action(it).summary() },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Switch(checked = edge.enabled, onCheckedChange = onEnabled)
        }
        if (expanded) {
            Spacer(Modifier.height(8.dp))
            SettingsGroup {
                slots.forEachIndexed { i, slot ->
                    SettingsNavRow(
                        GestureLabels.slotTitle(edge.id, slot),
                        edge.action(slot).summary(),
                        { onAction(edge.id, slot) },
                        divider = i != slots.lastIndex,
                    )
                }
            }
        }
    }
}

@Composable
fun GestureSettingsScreen(vm: TntViewModel, onBack: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val cfg = state.gesture
    var thick by remember(cfg.thickness) { mutableFloatStateOf(cfg.thickness.toFloat()) }
    var longMs by remember(cfg.longMs) { mutableFloatStateOf(cfg.longMs.toFloat()) }
    var doubleMs by remember(cfg.doubleMs) { mutableFloatStateOf(cfg.doubleMs.toFloat()) }
    var hoverMs by remember(cfg.hoverMs) { mutableFloatStateOf(cfg.hoverMs.toFloat()) }
    var hoverPx by remember(cfg.hoverPx) { mutableFloatStateOf(cfg.hoverPx.toFloat()) }
    SettingsPage("手势设置", onBack, onReset = { vm.resetGesture() }) {
        SettingsGroup {
            SettingsSwitchRow("手势震动", cfg.vibrate, { vm.setGestureVibrate(it) })
            SettingsSwitchRow("锁屏时可用", cfg.lockscreen, { vm.setGestureLockscreen(it) }, divider = false, subtitle = "默认关闭。打开后锁屏界面也能用手势。")
        }
        Panel {
            Text("热区厚度  " + thick.toInt() + " px")
            Slider(
                value = thick,
                onValueChange = { thick = it },
                valueRange = 12f..80f,
                onValueChangeFinished = { vm.setGestureTiming(thickness = thick.toInt()) },
            )
            Spacer(Modifier.height(8.dp))
            Text("长按时间  " + longMs.toInt() + " ms")
            Slider(
                value = longMs,
                onValueChange = { longMs = it },
                valueRange = 180f..1500f,
                onValueChangeFinished = { vm.setGestureTiming(longMs = longMs.toInt()) },
            )
            Spacer(Modifier.height(8.dp))
            Text("双击间隔  " + doubleMs.toInt() + " ms")
            Slider(
                value = doubleMs,
                onValueChange = { doubleMs = it },
                valueRange = 120f..800f,
                onValueChangeFinished = { vm.setGestureTiming(doubleMs = doubleMs.toInt()) },
            )
            Spacer(Modifier.height(8.dp))
            Text("悬停时间  " + hoverMs.toInt() + " ms")
            Slider(
                value = hoverMs,
                onValueChange = { hoverMs = it },
                valueRange = 120f..1500f,
                onValueChangeFinished = { vm.setGestureTiming(hoverMs = hoverMs.toInt()) },
            )
            Spacer(Modifier.height(8.dp))
            Text("悬停距离  " + hoverPx.toInt() + " px")
            Slider(
                value = hoverPx,
                onValueChange = { hoverPx = it },
                valueRange = 8f..80f,
                onValueChangeFinished = { vm.setGestureTiming(hoverPx = hoverPx.toInt()) },
            )
        }
    }
}