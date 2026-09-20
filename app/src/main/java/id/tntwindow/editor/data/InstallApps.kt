package id.tntwindow.editor.data

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import id.tntwindow.editor.domain.InstallHandler

object InstallApps {
    fun list(context: Context): List<InstallHandler> {
        val pm = context.packageManager
        val out = LinkedHashMap<String, InstallHandler>()
        fun add(pkg: String, cls: String) {
            val p = pkg.trim()
            var c = cls.trim()
            if (p.isBlank() || c.isBlank()) return
            if (c.startsWith(".")) c = p + c
            val key = p + "/" + c
            if (out.containsKey(key)) return
            if (isUninstallName(c)) return
            if (!present(pm, p, c)) return
            val appLabel = labelOf(pm, p)
            val actLabel = activityLabel(pm, p, c, appLabel)
            out[key] = InstallHandler(p, c, appLabel, actLabel)
        }
        queryIntents(pm, ::add)
        scanActivities(pm, ::add)
        known(::add)
        if (RootAccess.available()) parseRoot(::add)
        return out.values.sortedWith(
            compareBy<InstallHandler> { it.pkg != "com.android.packageinstaller" && it.pkg != "com.google.android.packageinstaller" }
                .thenBy { it.appLabel }
                .thenBy { it.activityLabel }
                .thenBy { it.cls }
        )
    }

    private fun queryIntents(pm: PackageManager, add: (String, String) -> Unit) {
        val apk = "application/vnd.android.package-archive"
        val datas = listOf(
            Uri.parse("file:///sdcard/tmp.apk"),
            Uri.parse("content://media/external/file/1.apk"),
        )
        val flags = listOf(0, PackageManager.MATCH_DEFAULT_ONLY, PackageManager.MATCH_ALL, PackageManager.GET_META_DATA)
        fun run(intent: Intent, flag: Int) {
            collect(pm, intent, flag, add)
            collect(pm, Intent(intent).addCategory(Intent.CATEGORY_DEFAULT), flag, add)
        }
        for (flag in flags) {
            run(Intent(Intent.ACTION_VIEW).setType(apk), flag)
            run(Intent(Intent.ACTION_INSTALL_PACKAGE), flag)
            run(Intent("android.intent.action.PACKAGE_INSTALL"), flag)
            run(Intent("android.content.pm.action.CONFIRM_INSTALL"), flag)
            for (data in datas) {
                run(Intent(Intent.ACTION_VIEW).setDataAndType(data, apk), flag)
                run(Intent(Intent.ACTION_VIEW).setDataAndType(data, "application/octet-stream"), flag)
                run(Intent(Intent.ACTION_INSTALL_PACKAGE).setData(data), flag)
                run(Intent(Intent.ACTION_INSTALL_PACKAGE).setDataAndType(data, apk), flag)
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
                if (isInstallName(n)) add(a.packageName ?: pkg.packageName, n)
            }
        }
    }

    private fun known(add: (String, String) -> Unit) {
        add("com.android.packageinstaller", "com.android.packageinstaller.PackageInstallerActivity")
        add("com.android.packageinstaller", "com.android.packageinstaller.InstallStart")
        add("com.google.android.packageinstaller", "com.android.packageinstaller.PackageInstallerActivity")
        add("com.google.android.packageinstaller", "com.android.packageinstaller.InstallStart")
        add("com.byyoung.setting", "com.byyoung.setting.Applications.activitys.PackageInstallerActivity")
    }

    private fun parseRoot(add: (String, String) -> Unit) {
        val cmds = listOf(
            "cmd package query-activities --brief -a android.intent.action.VIEW -t application/vnd.android.package-archive",
            "cmd package query-activities --brief -a android.intent.action.INSTALL_PACKAGE",
            "cmd package query-activities --brief -a android.intent.action.VIEW -d file:///tmp.apk",
            "cmd package query-activities --brief -a android.content.pm.action.CONFIRM_INSTALL",
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

    private fun isUninstallName(name: String): Boolean {
        val low = name.lowercase()
        return low.contains("uninstall") || low.contains("uninstaller") || low.contains("deletepackage") || low.contains("packagedelete")
    }

    private fun isInstallName(name: String): Boolean {
        if (isUninstallName(name)) return false
        val low = name.lowercase()
        return low.contains("packageinstalleractivity") ||
            low.contains("installstart") ||
            low.contains("installstaging") ||
            low.contains("installpackage") ||
            low.contains("apkinstall") ||
            low.contains("appinstall") ||
            (low.contains("package") && low.contains("installer"))
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
