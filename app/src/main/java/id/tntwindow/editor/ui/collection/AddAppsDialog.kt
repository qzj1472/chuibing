package id.tntwindow.editor.ui.collection

import java.util.LinkedHashSet
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.unit.dp
import id.tntwindow.editor.TntViewModel
import id.tntwindow.editor.domain.InstalledApp
import id.tntwindow.editor.ui.components.AppIcon
import id.tntwindow.editor.ui.components.PrimaryButton
import id.tntwindow.editor.ui.theme.Mono

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddAppsDialog(
    installed: List<InstalledApp>,
    vm: TntViewModel,
    currentId: String,
    onDismiss: () -> Unit,
    onConfirm: (List<String>) -> Unit,
) {
    var tab by remember { mutableStateOf(0) }
    var query by remember { mutableStateOf("") }
    val selected = remember { mutableStateOf(linkedSetOf<String>()) }
    val seen = remember { vm.prefs.seenPackages() }
    val showNoLauncher = vm.prefs.showNoLauncher
    val filtered = remember(installed, tab, query, showNoLauncher) {
        installed
            .filter { app -> if (tab == 0) !app.system else app.system }
            .filter { app -> showNoLauncher || app.hasLauncher }
            .filter { app ->
                query.isBlank() ||
                    app.label.contains(query, true) ||
                    app.packageName.contains(query, true)
            }
            .sortedWith(
                compareBy<InstalledApp> { seen.contains(it.packageName) }
                    .thenBy { vm.ownerOf(it.packageName) != null }
                    .thenByDescending { it.lastUpdateTime }
                    .thenBy { it.label },
            )
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(color = MaterialTheme.colorScheme.background, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth(0.94f).fillMaxHeight(0.92f)) {
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("添加软件", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("关闭") }
                }
                TabRow(selectedTabIndex = tab) {
                    Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("用户") })
                    Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("系统") })
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("搜索名称或包名") },
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(filtered, key = { it.packageName }) { app ->
                        val owner = vm.ownerOf(app.packageName)
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
                                if (owner != null) {
                                    Text(
                                        "已在「" + owner.name + "」",
                                        color = MaterialTheme.colorScheme.primary,
                                        style = MaterialTheme.typography.labelSmall,
                                    )
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
                    enabled = selected.value.isNotEmpty(),
                )
            }
        }
    }
}
