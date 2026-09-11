package id.tntwindow.editor.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import id.tntwindow.editor.TntViewModel
import id.tntwindow.editor.domain.VideoSpecial
import id.tntwindow.editor.ui.collection.AddAppsDialog
import id.tntwindow.editor.ui.components.AppIcon
import id.tntwindow.editor.ui.components.Panel
import id.tntwindow.editor.ui.components.PrimaryButton
import id.tntwindow.editor.ui.theme.Mono

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoSpecialScreen(vm: TntViewModel, initial: List<VideoSpecial>, onBack: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    var list by remember { mutableStateOf(initial) }
    var picking by remember { mutableStateOf(false) }
    val installed = vm.state.value.installed
    Scaffold(
        containerColor = scheme.background,
        topBar = {
            TopAppBar(
                title = { Text("视频特例") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = scheme.background),
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("这张表不是窗口大小。type 1 默认特例，type 2 会发全局 Configuration。")
            PrimaryButton("选择应用", onClick = { picking = true }, modifier = Modifier.fillMaxWidth())
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(list, key = { it.packageName + it.type + it.comment }) { item ->
                    Panel {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            AppIcon(item.packageName, 36)
                            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                                Text(vm.apps.label(item.packageName))
                                Text(item.packageName, style = Mono, color = scheme.onSurfaceVariant)
                            }
                            FilterChip(selected = item.type == 1, onClick = {
                                list = list.map { if (it === item) it.copy(type = 1) else it }
                            }, label = { Text("1") })
                            FilterChip(selected = item.type == 2, onClick = {
                                list = list.map { if (it === item) it.copy(type = 2) else it }
                            }, label = { Text("2") })
                            IconButton(onClick = { list = list.filter { it !== item } }) {
                                Icon(Icons.Outlined.Delete, contentDescription = "删除")
                            }
                        }
                    }
                }
            }
            PrimaryButton("应用并保存", onClick = { vm.applyVideos(list) }, modifier = Modifier.fillMaxWidth())
        }
    }
    if (picking) {
        AddAppsDialog(
            installed = installed,
            vm = vm,
            currentId = "",
            onDismiss = { picking = false },
            onConfirm = { pkgs ->
                val exist = list.map { it.packageName }.toSet()
                list = list + pkgs.filter { it !in exist }.map { VideoSpecial(it, 1, vm.apps.label(it)) }
                picking = false
            },
        )
    }
}