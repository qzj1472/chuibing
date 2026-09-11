package id.tntwindow.editor.ui.collection

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.SwapHoriz
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
import id.tntwindow.editor.TntViewModel
import id.tntwindow.editor.domain.AppCollection
import id.tntwindow.editor.domain.CollectionType
import id.tntwindow.editor.domain.InstalledApp
import id.tntwindow.editor.domain.Membership
import id.tntwindow.editor.domain.Presets
import id.tntwindow.editor.ui.components.AppIcon
import id.tntwindow.editor.ui.components.Panel
import id.tntwindow.editor.ui.components.PrimaryButton
import id.tntwindow.editor.ui.components.SpecEditorDialog
import id.tntwindow.editor.ui.theme.Danger
import id.tntwindow.editor.ui.theme.Mono

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollectionDetailScreen(
    col: AppCollection,
    vm: TntViewModel,
    installed: List<InstalledApp>,
    allCollections: List<AppCollection>,
    onBack: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    var adding by remember { mutableStateOf(false) }
    var editingPkg by remember { mutableStateOf<String?>(null) }
    var movingPkg by remember { mutableStateOf<String?>(null) }
    var confirmRemove by remember { mutableStateOf<String?>(null) }
    var pendingAdd by remember { mutableStateOf<List<String>?>(null) }
    var conflictText by remember { mutableStateOf<String?>(null) }
    var editingSpec by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val showUninstalled = vm.state.value.showUninstalled
    val showNoLauncher = vm.state.value.showNoLauncher
    val sorted = remember(col.members, installed, query, showUninstalled, showNoLauncher) {
        val base = vm.visibleMembers(col.members)
        if (query.isBlank()) base
        else base.filter { m ->
            val label = installed.firstOrNull { it.packageName == m.packageName }?.label ?: vm.apps.label(m.packageName)
            label.contains(query, true) || m.packageName.contains(query, true)
        }
    }
    Scaffold(
        containerColor = scheme.background,
        topBar = {
            TopAppBar(
                title = { Text(col.name) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = scheme.background),
            )
        },
    ) { pad ->
        BoxWithConstraints(Modifier.padding(pad).fillMaxSize()) {
            val columns = if (maxWidth > 520.dp) 2 else 1
            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                item(span = { GridItemSpan(columns) }) {
                    Panel(accent = true) {
                        if (col.type == CollectionType.SPEC) {
                            Text(col.spec?.summary() ?: "未设规格", style = MaterialTheme.typography.titleSmall)
                            Spacer(Modifier.height(8.dp))
                            Text("合集内统一规格，不能给单个软件开例外。例外请移到自定义。", color = scheme.onSurfaceVariant)
                            Spacer(Modifier.height(12.dp))
                            PrimaryButton("编辑规格", onClick = { editingSpec = true }, modifier = Modifier.fillMaxWidth())
                        } else {
                            Text("每个软件单独配置，不许套统一规格。")
                        }
                        Spacer(Modifier.height(8.dp))
                        PrimaryButton("应用并保存", onClick = { vm.applyAllCollections() }, modifier = Modifier.fillMaxWidth())
                    }
                }
                item(span = { GridItemSpan(columns) }) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("搜索这个合集") },
                    )
                }
                item(span = { GridItemSpan(columns) }) {
                    Panel(modifier = Modifier.fillMaxWidth().clickable { adding = true }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Add, contentDescription = null, tint = scheme.primary)
                            Text("  添加软件", style = MaterialTheme.typography.titleSmall)
                        }
                    }
                }
                items(sorted, key = { it.packageName }) { member ->
                    val info = installed.firstOrNull { it.packageName == member.packageName }
                    MemberCard(
                        member = member,
                        label = info?.label ?: vm.apps.label(member.packageName),
                        installed = info != null,
                        system = info?.system == true,
                        custom = col.type == CollectionType.CUSTOM,
                        onOpen = { if (col.type == CollectionType.CUSTOM) editingPkg = member.packageName },
                        onDelete = { confirmRemove = member.packageName },
                        onMove = { movingPkg = member.packageName },
                    )
                }
            }
        }
    }
    if (adding) {
        AddAppsDialog(
            installed = installed,
            vm = vm,
            currentId = col.id,
            onDismiss = { adding = false },
            onConfirm = { pkgs ->
                val conflicts = pkgs.mapNotNull { pkg ->
                    val owner = vm.ownerOf(pkg)
                    if (owner != null && owner.id != col.id) Triple(pkg, vm.apps.label(pkg), owner.name) else null
                }
                if (conflicts.isEmpty()) {
                    vm.addApps(col.id, pkgs)
                    adding = false
                } else {
                    pendingAdd = pkgs
                    conflictText = conflicts.joinToString("\n") { it.second + " 已在「" + it.third + "」" }
                }
            },
        )
    }
    val editPkg = editingPkg
    if (editPkg != null) {
        val current = col.members.firstOrNull { it.packageName == editPkg }?.customSpec ?: vm.defaultSpec()
        SpecEditorDialog(
            title = vm.apps.label(editPkg),
            spec = current,
            onDismiss = { editingPkg = null },
            onConfirm = {
                vm.setCustomSpec(editPkg, it)
                editingPkg = null
            },
        )
    }
    if (editingSpec && col.spec != null) {
        SpecEditorDialog(
            title = col.name + " 规格",
            spec = col.spec ?: Presets.DEFAULT_NEW,
            onDismiss = { editingSpec = false },
            onConfirm = {
                vm.setSpec(col.id, it)
                editingSpec = false
            },
        )
    }
    val movePkg = movingPkg
    if (movePkg != null) {
        AlertDialog(
            onDismissRequest = { movingPkg = null },
            title = { Text("移动到合集") },
            text = {
                Column {
                    allCollections.filter { it.id != col.id }.forEach { dest ->
                        TextButton(onClick = {
                            vm.moveApp(movePkg, dest.id)
                            movingPkg = null
                        }) { Text(dest.name) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { movingPkg = null }) { Text("取消") } },
        )
    }
    val pending = pendingAdd
    if (pending != null && conflictText != null) {
        AlertDialog(
            onDismissRequest = { pendingAdd = null; conflictText = null },
            title = { Text("该软件已在其他合集") },
            text = { Text(conflictText + "\n\n是否移动到当前合集？") },
            confirmButton = {
                TextButton(onClick = {
                    pending.forEach { vm.moveApp(it, col.id) }
                    pendingAdd = null
                    conflictText = null
                    adding = false
                }) { Text("移动") }
            },
            dismissButton = {
                TextButton(onClick = {
                    pendingAdd = null
                    conflictText = null
                }) { Text("取消") }
            },
        )
    }
    val del = confirmRemove
    if (del != null) {
        AlertDialog(
            onDismissRequest = { confirmRemove = null },
            title = { Text("移出合集") },
            text = { Text("只是从合集拿掉，不会立刻改 XML。下次自动添加也不会再放进来。") },
            confirmButton = {
                TextButton(onClick = {
                    vm.removeMember(del)
                    confirmRemove = null
                }) { Text("移出", color = Danger) }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun MemberCard(
    member: Membership,
    label: String,
    installed: Boolean,
    system: Boolean,
    custom: Boolean,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
    onMove: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Panel(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            AppIcon(member.packageName)
            Column(Modifier.weight(1f).padding(start = 10.dp).clickable(onClick = onOpen)) {
                Text(label, style = MaterialTheme.typography.titleSmall)
                Text(member.packageName, style = Mono, color = scheme.onSurfaceVariant)
                val mark = buildString {
                    append(if (installed) "已安装" else "未安装")
                    append(" · ")
                    append(if (system) "系统" else "用户")
                }
                Text(mark, color = scheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                if (custom && member.customSpec != null) {
                    Text(member.customSpec.summary(), color = scheme.onSurfaceVariant)
                }
            }
            IconButton(onClick = onMove) { Icon(Icons.Outlined.SwapHoriz, contentDescription = "移动") }
            IconButton(onClick = onDelete) { Icon(Icons.Outlined.Delete, contentDescription = "移出合集") }
        }
    }
}

