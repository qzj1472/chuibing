package id.tntwindow.editor.ui.components

import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.graphics.drawable.toBitmap
import id.tntwindow.editor.domain.Issue
import id.tntwindow.editor.domain.WindowModes
import id.tntwindow.editor.domain.WindowSpec
import id.tntwindow.editor.domain.composeResize
import id.tntwindow.editor.domain.stretchKind
import id.tntwindow.editor.ui.theme.Danger
import id.tntwindow.editor.ui.theme.Mono

private val CardShape = RoundedCornerShape(16.dp)
private val ControlShape = RoundedCornerShape(12.dp)

@Composable
fun Panel(modifier: Modifier = Modifier, accent: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier
            .clip(CardShape)
            .background(if (accent) scheme.primaryContainer.copy(alpha = 0.35f) else scheme.surface)
            .padding(16.dp)
            .fillMaxWidth(),
        content = content,
    )
}

@Composable
fun StatTile(label: String, value: String, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier
            .clip(ControlShape)
            .background(scheme.surfaceVariant)
            .padding(12.dp)
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        Text(value, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun PathText(text: String) {
    Text(text, style = Mono, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(48.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ),
        shape = ControlShape,
    ) { Text(text) }
}

@Composable
fun AppIcon(packageName: String, size: Int = 40) {
    val context = LocalContext.current
    val painter = remember(packageName) {
        try {
            val d: Drawable = context.packageManager.getApplicationIcon(packageName)
            BitmapPainter(d.toBitmap(96, 96).asImageBitmap())
        } catch (_: Exception) {
            null
        }
    }
    if (painter != null) {
        Image(painter, null, Modifier.size(size.dp).clip(ControlShape))
    } else {
        Box(
            Modifier
                .size(size.dp)
                .clip(ControlShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Text(packageName.take(1).uppercase(), color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
fun SmallTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(start = 12.dp, top = 4.dp, bottom = 8.dp),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SpecEditor(spec: WindowSpec, onChange: (WindowSpec) -> Unit) {
    Text("显示状态", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(6.dp))
    ModeRow(WindowModes.ROW1, spec, onChange)
    Spacer(Modifier.height(6.dp))
    ModeRow(WindowModes.ROW2, spec, onChange)
    Spacer(Modifier.height(6.dp))
    ModeRow(WindowModes.ROW3, spec, onChange)
    Spacer(Modifier.height(6.dp))
    ModeRow(WindowModes.ROW4, spec, onChange)
    Spacer(Modifier.height(12.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NumField("宽", spec.width, { onChange(spec.copy(width = it)) }, Modifier.weight(1f))
        NumField("高", spec.height, { onChange(spec.copy(height = it)) }, Modifier.weight(1f))
    }
    Spacer(Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NumField("最小宽", spec.minWidth, { onChange(spec.copy(minWidth = it)) }, Modifier.weight(1f))
        NumField("最小高", spec.minHeight, { onChange(spec.copy(minHeight = it)) }, Modifier.weight(1f))
    }
    Spacer(Modifier.height(12.dp))
    Text("拉伸（互斥）", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(6.dp))
    val stretch = stretchKind(spec.resizeMode)
    val fullscreen = (spec.resizeMode ?: 0) and 4 != 0
    val stretchOptions = listOf(0 to "不能拉伸", 1 to "任意拉伸", 2 to "仅纵向", 8 to "按比例")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        stretchOptions.forEach { (v, label) ->
            FilterChip(
                selected = stretch == v,
                onClick = { onChange(spec.copy(resizeMode = composeResize(v, fullscreen))) },
                label = { Text(label) },
            )
        }
    }
    Spacer(Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("支持全屏", modifier = Modifier.weight(1f))
        Switch(
            checked = fullscreen,
            onCheckedChange = { onChange(spec.copy(resizeMode = composeResize(stretch, it))) },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModeRow(items: List<Pair<Int, String>>, spec: WindowSpec, onChange: (WindowSpec) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items.forEach { (v, label) ->
            FilterChip(
                selected = (spec.windowMode ?: -1) == v,
                onClick = { onChange(spec.copy(windowMode = v)) },
                label = { Text(label) },
            )
        }
    }
}

@Composable
fun NumField(label: String, value: Int?, onChange: (Int?) -> Unit, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = value?.toString().orEmpty(),
        onValueChange = { t ->
            if (t.isBlank()) onChange(null)
            else t.toIntOrNull()?.let(onChange)
        },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
        shape = ControlShape,
    )
}

@Composable
fun SpecEditorDialog(
    title: String,
    spec: WindowSpec,
    onDismiss: () -> Unit,
    onConfirm: (WindowSpec) -> Unit,
) {
    var local by remember(spec) { mutableStateOf(spec) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = CardShape,
            color = MaterialTheme.colorScheme.background,
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .fillMaxHeight(0.92f),
        ) {
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(12.dp))
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                ) {
                    SpecEditor(local) { local = it }
                }
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("取消") }
                    PrimaryButton("确定", onClick = { onConfirm(local) })
                }
            }
        }
    }
}

@Composable
fun QuietButton(text: String, onClick: () -> Unit) {
    TextButton(onClick = onClick) { Text(text) }
}

@Composable
fun RowSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, modifier = Modifier.weight(1f).padding(end = 12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
fun SettingsGroup(content: @Composable ColumnScope.() -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .clip(CardShape)
            .background(scheme.surface)
            .fillMaxWidth(),
        content = content,
    )
}

@Composable
fun SettingsInfoRow(
    title: String,
    subtitle: String,
    onClick: (() -> Unit)? = null,
    trailing: String? = null,
    divider: Boolean = true,
    subtitleMaxLines: Int = 2,
) {
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                if (subtitle.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        subtitle,
                        color = scheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = subtitleMaxLines,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (trailing != null) {
                Text(trailing, color = scheme.onSurfaceVariant, style = MaterialTheme.typography.titleLarge)
            }
        }
        if (divider) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .padding(start = 16.dp)
                    .background(scheme.outline.copy(alpha = 0.35f)),
            )
        }
    }
}

@Composable
fun SettingsSwitchRow(
    title: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    divider: Boolean = true,
) {
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).padding(end = 12.dp))
            Switch(checked = checked, onCheckedChange = onChange)
        }
        if (divider) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .padding(start = 16.dp)
                    .background(scheme.outline.copy(alpha = 0.35f)),
            )
        }
    }
}

@Composable
fun SettingsNavRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    divider: Boolean = true,
) {
    SettingsInfoRow(title, subtitle, onClick = onClick, trailing = "›", divider = divider)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IssuesDialog(issues: List<Issue>, onDismiss: () -> Unit, onRetry: () -> Unit, showRetry: Boolean) {
    val groups = listOf("权限", "文件", "XML", "备份")
    val present = groups.filter { g -> issues.any { it.group == g } }.ifEmpty { listOf("全部") }
    var tab by remember { mutableIntStateOf(0) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            color = MaterialTheme.colorScheme.background,
            modifier = Modifier.fillMaxWidth(0.94f).fillMaxHeight(0.82f),
            shape = CardShape,
        ) {
            Column(Modifier.padding(16.dp).fillMaxSize()) {
                Text("需要处理", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                TabRow(selectedTabIndex = tab) {
                    present.forEachIndexed { i, g ->
                        Tab(selected = tab == i, onClick = { tab = i }, text = { Text(g) })
                    }
                }
                val current = if (present[tab] == "全部") issues else issues.filter { it.group == present[tab] }
                Column(
                    Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(top = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    current.forEach { issue ->
                        Panel {
                            Text(issue.title, style = MaterialTheme.typography.titleSmall)
                            Spacer(Modifier.height(4.dp))
                            SelectionContainer {
                                Text(issue.detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    if (showRetry) TextButton(onClick = onRetry) { Text("重试") }
                    TextButton(onClick = onDismiss) { Text("关闭") }
                }
            }
        }
    }
}
