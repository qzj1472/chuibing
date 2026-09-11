package id.tntwindow.editor.ui.collection

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import id.tntwindow.editor.TntViewModel
import id.tntwindow.editor.domain.AppCollection
import id.tntwindow.editor.domain.CollectionType
import id.tntwindow.editor.domain.Presets
import id.tntwindow.editor.domain.WindowSpec
import id.tntwindow.editor.ui.components.Panel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollectionsScreen(
    collections: List<AppCollection>,
    editingAllowed: Boolean,
    vm: TntViewModel,
    videoCount: Int,
    onOpen: (String) -> Unit,
    onCreate: (String, WindowSpec) -> Unit,
    onSave: () -> Unit,
    onRestart: () -> Unit,
    onVideos: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    var creating by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val showUninstalled = vm.state.value.showUninstalled
    val showNoLauncher = vm.state.value.showNoLauncher
    val filtered = remember(collections, query, showUninstalled, showNoLauncher) {
        if (query.isBlank()) collections
        else collections.filter { col ->
            col.name.contains(query, true) ||
                vm.visibleMembers(col.members).any { m ->
                    m.packageName.contains(query, true) ||
                        vm.state.value.installed.any { it.packageName == m.packageName && it.label.contains(query, true) } ||
                        vm.apps.label(m.packageName).contains(query, true)
                }
        }
    }
    Scaffold(
        containerColor = scheme.background,
        topBar = {
            TopAppBar(
                title = { Text("窗口") },
                actions = {
                    IconButton(onClick = onSave, enabled = editingAllowed) {
                        Icon(Icons.Outlined.Save, contentDescription = "保存")
                    }
                    IconButton(onClick = onRestart) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "热切换 TNT")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = scheme.background),
            )
        },
        floatingActionButton = {
            if (editingAllowed) {
                FloatingActionButton(onClick = { creating = true }, containerColor = scheme.primary, contentColor = scheme.onPrimary) {
                    Icon(Icons.Outlined.Add, contentDescription = "新建规格合集")
                }
            }
        },
    ) { pad ->
        LazyColumn(
            Modifier.padding(pad).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("搜索合集、软件名或包名") },
                )
                Spacer(Modifier.height(10.dp))
                Panel(modifier = Modifier.clickable { onVideos() }) {
                    Text("视频特例", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(videoCount.toString() + " 条", color = scheme.primary)
                }
            }
            items(filtered, key = { it.id }) { col ->
                Panel(accent = col.type == CollectionType.SPEC, modifier = Modifier.clickable { onOpen(col.id) }) {
                    Row(Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(1f)) {
                            Text(col.name, style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                if (col.type == CollectionType.CUSTOM) "每个软件单独配置"
                                else col.spec?.summary() ?: "未设规格",
                                color = scheme.onSurfaceVariant,
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(vm.visibleMembers(col.members).size.toString() + " 个应用", color = scheme.primary)
                }
            }
        }
    }
    if (creating) {
        var name by remember { mutableStateOf("新规格合集") }
        AlertDialog(
            onDismissRequest = { creating = false },
            title = { Text("新建规格合集") },
            text = {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("名称") }, singleLine = true)
            },
            confirmButton = {
                TextButton(onClick = {
                    onCreate(name.ifBlank { "新规格合集" }, Presets.LAND_900)
                    creating = false
                }) { Text("创建") }
            },
            dismissButton = { TextButton(onClick = { creating = false }) { Text("取消") } },
        )
    }
}
