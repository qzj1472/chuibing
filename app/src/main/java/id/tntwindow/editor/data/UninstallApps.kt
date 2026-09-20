package id.tntwindow.editor.data

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import id.tntwindow.editor.domain.UninstallHandler

object UninstallApps {
    fun list(context: Context): List<UninstallHandler> {
        val pm = context.packageManager
        val out = LinkedHashMap<String, UninstallHandler>()
        fun add(pkg: String, cls: String) {
            val p = pkg.trim()
            var c = cls.trim()
            if (p.isBlank() || c.isBlank()) return
            if (c.startsWith(".")) c = p + c
            val key = p + "/" + c
            if (out.containsKey(key)) return
            if (!present(pm, p, c)) return
            val appLabel = labelOf(pm, p)
            val actLabel = activityLabel(pm, p, c, appLabel)
            out[key] = UninstallHandler(p, c, appLabel, actLabel)
        }
        queryIntents(pm, ::add)
        scanActivities(pm, ::add)
        known(::add)
        if (RootAccess.available()) parseRoot(::add)
        return out.values.sortedWith(
            compareBy<UninstallHandler> { it.pkg != "com.android.packageinstaller" && it.pkg != "com.google.android.packageinstaller" }
                .thenBy { it.appLabel }
                .thenBy { it.activityLabel }
                .thenBy { it.cls }
        )
    }

    private fun queryIntents(pm: PackageManager, add: (String, String) -> Unit) {
        val actions = listOf(Intent.ACTION_DELETE, Intent.ACTION_UNINSTALL_PACKAGE)
        val datas = listOf(null, Uri.fromParts("package", "android", null), Uri.parse("package:android"))
        val flags = listOf(0, PackageManager.MATCH_DEFAULT_ONLY, PackageManager.MATCH_ALL, PackageManager.GET_META_DATA)
        for (action in actions) {
            for (data in datas) {
                for (flag in flags) {
                    val intent = Intent(action)
                    if (data != null) intent.data = data
                    collect(pm, intent, flag, add)
                    collect(pm, Intent(intent).addCategory(Intent.CATEGORY_DEFAULT), flag, add)
                }
            }
        }
    }

    private fun collect(pm: PackageManager, intent: Intent, flag: Int, add: (String, String) -> Unit) {
        val list = try {
            pm.queryIntentActivities(intent, flag)
        } catch (_: Exception) {
            emptyList()
        }
        for (ri in list) {
            val info = ri.activityInfo ?: continue
            add(info.packageName ?: continue, info.name ?: continue)
        }
    }

    private fun scanActivities(pm: PackageManager, add: (String, String) -> Unit) {
        val flags = PackageManager.GET_ACTIVITIES or PackageManager.MATCH_DISABLED_COMPONENTS
        val pkgs = try {
            pm.getInstalledPackages(flags)
        } catch (_: Exception) {
            try {
                pm.getInstalledPackages(PackageManager.GET_ACTIVITIES)
            } catch (_: Exception) {
                emptyList()
            }
        }
        for (pkg in pkgs) {
            val acts = pkg.activities ?: continue
            for (a in acts) {
                val n = a.name ?: continue
                val low = n.lowercase()
                if (
                    low.contains("uninstall") ||
                    low.contains("uninstaller") ||
                    low.contains("deletepackage") ||
                    low.contains("packagedelete") ||
                    low.contains("appdelete")
                ) {
                    add(a.packageName ?: pkg.packageName, n)
                }
            }
        }
    }

    private fun known(add: (String, String) -> Unit) {
        add("com.android.packageinstaller", "com.android.packageinstaller.UninstallerActivity")
        add("com.google.android.packageinstaller", "com.android.packageinstaller.UninstallerActivity")
        add("com.byyoung.setting", "com.byyoung.setting.Applications.activitys.PackageUninstallActivity")
    }

    private fun parseRoot(add: (String, String) -> Unit) {
        val cmds = listOf(
            "cmd package query-activities --brief -a android.intent.action.DELETE -d package:android",
            "cmd package query-activities --brief -a android.intent.action.UNINSTALL_PACKAGE",
            "cmd package query-activities --brief -a android.intent.action.DELETE",
        )
        val regex = Regex("([A-Za-z0-9._]+)/(\\.?[A-Za-z0-9._]+)")
        for (cmd in cmds) {
            val r = try {
                RootAccess.su(cmd, 8)
            } catch (_: Exception) {
                continue
            }
            val text = r.out + "\n" + r.err
            for (m in regex.findAll(text)) add(m.groupValues[1], m.groupValues[2])
        }
    }

    private fun present(pm: PackageManager, pkg: String, cls: String): Boolean {
        return try {
            pm.getActivityInfo(ComponentName(pkg, cls), 0)
            true
        } catch (_: Exception) {
            try {
                pm.getActivityInfo(ComponentName(pkg, cls), PackageManager.MATCH_DISABLED_COMPONENTS)
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    private fun labelOf(pm: PackageManager, pkg: String): String {
        return try {
            val info = pm.getApplicationInfo(pkg, 0)
            pm.getApplicationLabel(info).toString()
        } catch (_: Exception) {
            pkg
        }
    }

    private fun activityLabel(pm: PackageManager, pkg: String, cls: String, fallback: String): String {
        return try {
            val info = pm.getActivityInfo(ComponentName(pkg, cls), 0)
            val v = info.loadLabel(pm).toString()
            if (v.isBlank()) fallback else v
        } catch (_: Exception) {
            cls.substringAfterLast('.')
        }
    }
}
