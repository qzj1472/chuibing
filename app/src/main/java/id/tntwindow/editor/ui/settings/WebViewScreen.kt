package id.tntwindow.editor.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import id.tntwindow.editor.TntViewModel
import id.tntwindow.editor.data.WebViewControl
import id.tntwindow.editor.domain.WebViewPackage
import id.tntwindow.editor.ui.components.AppIcon
import id.tntwindow.editor.ui.components.Panel
import id.tntwindow.editor.ui.components.PathText
import id.tntwindow.editor.ui.components.PrimaryButton
import id.tntwindow.editor.ui.theme.Mono
import id.tntwindow.editor.ui.theme.Danger

@Composable
fun WebViewScreen(vm: TntViewModel, onBack: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val state by vm.state.collectAsStateWithLifecycle()
    val snap = state.webView
    val context = LocalContext.current
    var selected by remember(snap.currentPackage) { mutableStateOf(snap.currentPackage) }
    var askReboot by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { vm.refreshWebView() }
    val picked = snap.providers.firstOrNull { it.packageName == selected }
    val selectedValid = picked?.valid == true
    val canSwitch = selected.isNotBlank() && (picked?.abiOk != false)
    SettingsPage("系统 WebView", onBack) {
        Panel(accent = true) {
            Text("当前实现", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            val cur = snap.providers.firstOrNull { it.current }
            Text(if (cur != null) cur.label else if (snap.currentPackage.isNotBlank()) snap.currentPackage else "未读到")
            if (snap.currentVersion.isNotBlank()) {
                Text(snap.currentVersion, color = scheme.onSurfaceVariant)
            }
            if (snap.currentPackage.isNotBlank()) {
                PathText(snap.currentPackage)
            }
            if (cur != null && !cur.abiOk) {
                Spacer(Modifier.height(8.dp))
                Text("当前包没有 64 位库，APatch 等会闪退。", color = Danger)
            }
            Spacer(Modifier.height(8.dp))
            PrimaryButton("打开系统菜单", onClick = {
                val ok = WebViewControl.openSystemPicker(context)
                vm.note(if (ok) "已打开系统菜单" else "打不开系统菜单")
            }, modifier = Modifier.fillMaxWidth())
        }
        Panel {
            Text("切换实现", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            if (snap.providers.isEmpty()) {
                Text("还没有扫到 WebView 包。", color = scheme.onSurfaceVariant)
            } else {
                snap.providers.forEach { item ->
                    ProviderRow(item, selected = selected == item.packageName) { selected = item.packageName }
                }
            }
            Spacer(Modifier.height(8.dp))
            PrimaryButton(
                if (picked?.abiOk == false) "架构不兼容" else if (selectedValid) "应用并切换" else "注入并重启",
                onClick = {
                    if (selectedValid) vm.setWebViewImplementation(selected)
                    else askReboot = true
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = canSwitch,
            )
        }
    }
    if (askReboot) {
        AlertDialog(
            onDismissRequest = { askReboot = false },
            title = { Text("注入并重启") },
            text = { Text("写入名单后会立刻重启。") },
            confirmButton = {
                TextButton(onClick = {
                    askReboot = false
                    vm.setWebViewImplementation(selected, rebootIfNeeded = true)
                }) { Text("重启") }
            },
            dismissButton = { TextButton(onClick = { askReboot = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun ProviderRow(item: WebViewPackage, selected: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        AppIcon(item.packageName, 36)
        Column(Modifier.weight(1f)) {
            Text(item.label)
            Text(item.packageName, style = Mono, color = scheme.onSurfaceVariant)
            val bits = ArrayList<String>()
            if (item.versionName.isNotBlank()) bits += item.versionName
            val has64 = item.abis.any { it.contains("64") }
            val has32 = item.abis.any { it.contains("armeabi") }
            if (has64 && has32) bits += "32+64位"
            else if (has64) bits += "64位"
            else if (has32) bits += "仅32位"
            if (item.current) bits += "当前"
            if (!item.abiOk) bits += "不兼容"
            if (item.valid) bits += "系统有效"
            else if (item.candidate) bits += "不在名单"
            Text(bits.joinToString(" · "), color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
        FilterChip(selected = selected, onClick = onClick, label = { Text(if (selected) "选中" else "选择") })
    }
}
