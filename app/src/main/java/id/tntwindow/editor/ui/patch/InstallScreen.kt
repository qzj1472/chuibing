package id.tntwindow.editor.ui.patch

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.tntwindow.editor.TntViewModel
import id.tntwindow.editor.ui.components.SettingsGroup
import id.tntwindow.editor.ui.components.SettingsInfoRow
import id.tntwindow.editor.ui.components.SettingsNavSwitchRow
import id.tntwindow.editor.ui.components.SettingsSwitchRow
import id.tntwindow.editor.ui.components.SmallTitle
import id.tntwindow.editor.ui.settings.SettingsPage

@Composable
fun InstallScreen(
    vm: TntViewModel,
    onBack: () -> Unit,
    onPickInstall: () -> Unit,
    onPickUninstall: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val state by vm.state.collectAsStateWithLifecycle()
    val cfg = state.install
    val uCfg = state.uninstall
    LaunchedEffect(Unit) {
        vm.refreshInstallHandlers()
        vm.refreshUninstallHandlers()
    }
    SettingsPage("安装卸载接管", onBack, onReset = { vm.resetInstall() }) {
        Text(
            "总开关打开后，点 APK、文件管理安装、系统安装页都会进你选的程序。点卡片进入选择页。",
            color = scheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
        SettingsGroup {
            SettingsNavSwitchRow(
                title = "安装接管",
                subtitle = handlerLabel(cfg.pkg, cfg.cls, state.installHandlers.map { Triple(it.pkg, it.cls, it.appLabel to it.activityLabel) }),
                checked = cfg.enabled,
                onChange = { vm.setInstallEnabled(it) },
                onClick = onPickInstall,
                divider = false,
            )
        }
        SettingsGroup {
            SettingsSwitchRow(
                title = "安装与卸载使用同一个接管",
                checked = cfg.shareUninstall,
                onChange = { vm.setShareUninstall(it) },
                subtitle = "关闭后可单独选卸载程序",
                divider = false,
            )
        }
        if (!cfg.shareUninstall) {
            Text(
                "总开关打开后，设置里卸载、长按卸载都会进你选的程序。点卡片进入选择页。",
                color = scheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
            SettingsGroup {
                SettingsNavSwitchRow(
                    title = "卸载接管",
                    subtitle = handlerLabel(uCfg.pkg, uCfg.cls, state.uninstallHandlers.map { Triple(it.pkg, it.cls, it.appLabel to it.activityLabel) }),
                    checked = uCfg.enabled,
                    onChange = { vm.setUninstallEnabled(it) },
                    onClick = onPickUninstall,
                    divider = false,
                )
            }
        }
    }
}

@Composable
fun InstallPickScreen(vm: TntViewModel, onBack: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val state by vm.state.collectAsStateWithLifecycle()
    val cfg = state.install
    val handlers = state.installHandlers
    LaunchedEffect(Unit) { vm.refreshInstallHandlers() }
    SettingsPage("安装程序", onBack, onReset = { vm.setInstallHandler("", "") }) {
        Text(
            "同一个软件有多个安装页时，必须点到具体那一项。这里只负责选程序，总开关在上一页。",
            color = scheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
        HandlerPickGroups(
            emptyText = "没有找到可接管的安装程序，正在扫描或当前没有这类软件。",
            handlers = handlers.map { HandlerItem(it.pkg, it.cls, it.appLabel, it.activityLabel) },
            selectedPkg = cfg.pkg,
            selectedCls = cfg.cls,
            onPick = { pkg, cls -> vm.setInstallHandler(pkg, cls) },
        )
    }
}

@Composable
fun UninstallPickScreen(vm: TntViewModel, onBack: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val state by vm.state.collectAsStateWithLifecycle()
    val cfg = state.uninstall
    val handlers = state.uninstallHandlers
    LaunchedEffect(Unit) { vm.refreshUninstallHandlers() }
    SettingsPage("卸载程序", onBack, onReset = { vm.setUninstallHandler("", "") }) {
        Text(
            "同一个软件有多个卸载页时，必须点到具体那一项。这里只负责选程序，总开关在上一页。",
            color = scheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
        HandlerPickGroups(
            emptyText = "没有找到可接管的卸载程序，正在扫描或当前没有这类软件。",
            handlers = handlers.map { HandlerItem(it.pkg, it.cls, it.appLabel, it.activityLabel) },
            selectedPkg = cfg.pkg,
            selectedCls = cfg.cls,
            onPick = { pkg, cls -> vm.setUninstallHandler(pkg, cls) },
        )
    }
}

private data class HandlerItem(
    val pkg: String,
    val cls: String,
    val appLabel: String,
    val activityLabel: String,
)

@Composable
private fun HandlerPickGroups(
    emptyText: String,
    handlers: List<HandlerItem>,
    selectedPkg: String,
    selectedCls: String,
    onPick: (String, String) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    if (handlers.isEmpty()) {
        SettingsGroup {
            Text(emptyText, color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        }
        return
    }
    val grouped = handlers.groupBy { it.pkg }
    grouped.forEach { (_, items) ->
        val appTitle = items.first().appLabel
        if (items.size > 1) SmallTitle(appTitle)
        SettingsGroup {
            items.forEachIndexed { i, app ->
                val on = selectedPkg == app.pkg && selectedCls == app.cls
                val title = if (items.size > 1) app.activityLabel.ifBlank { app.appLabel } else app.appLabel
                val sub = if (items.size > 1) {
                    app.pkg + "/" + app.cls
                } else if (app.activityLabel.isNotBlank() && app.activityLabel != app.appLabel) {
                    app.activityLabel + "\n" + app.pkg + "/" + app.cls
                } else {
                    app.pkg + "/" + app.cls
                }
                SettingsInfoRow(
                    title = title,
                    subtitle = sub,
                    onClick = { onPick(app.pkg, app.cls) },
                    trailing = if (on) "✓" else "",
                    iconPkg = app.pkg,
                    divider = i != items.lastIndex,
                )
            }
        }
    }
}

private fun handlerLabel(
    pkg: String,
    cls: String,
    items: List<Triple<String, String, Pair<String, String>>>,
): String {
    if (pkg.isBlank()) return "未选择"
    val hit = items.firstOrNull { it.first == pkg && it.second == cls } ?: items.firstOrNull { it.first == pkg }
    if (hit == null) return pkg
    val app = hit.third.first
    val act = hit.third.second
    return if (act.isNotBlank() && act != app) app + " · " + act else app.ifBlank { pkg }
}
