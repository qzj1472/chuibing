package id.tntwindow.editor.data

import id.tntwindow.editor.domain.Paths
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

data class ShellResult(
    val code: Int,
    val out: String,
    val err: String,
) {
    val ok: Boolean get() = code == 0
}

object RootAccess {
    fun available(): Boolean {
        val r = su("id")
        return r.ok && r.out.contains("uid=0")
    }

    fun su(command: String, timeoutSec: Long = 30): ShellResult {
        return run(listOf("su", "-c", command), timeoutSec)
    }

    fun suMount(command: String, timeoutSec: Long = 30): ShellResult {
        val mm = run(listOf("su", "-mm", "-c", command), timeoutSec)
        if (mm.ok) return mm
        val master = run(listOf("su", "--mount-master", "-c", command), timeoutSec)
        if (master.ok) return master
        return su(command, timeoutSec)
    }

    private fun run(args: List<String>, timeoutSec: Long): ShellResult {
        return try {
            val process = ProcessBuilder(args)
                .redirectErrorStream(false)
                .start()
            val out = process.inputStream.readBytes().toString(StandardCharsets.UTF_8)
            val err = process.errorStream.readBytes().toString(StandardCharsets.UTF_8)
            val finished = process.waitFor(timeoutSec, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                ShellResult(124, out, err + "timeout")
            } else {
                ShellResult(process.exitValue(), out, err)
            }
        } catch (e: Exception) {
            ShellResult(-1, "", e.message ?: "su failed")
        }
    }

    fun pullTo(local: File): ShellResult {
        local.parentFile?.mkdirs()
        return try {
            val process = ProcessBuilder("su", "-c", "cat '" + Paths.SYSTEM + "'")
                .redirectErrorStream(false)
                .start()
            val bytes = process.inputStream.readBytes()
            val err = process.errorStream.readBytes().toString(StandardCharsets.UTF_8)
            val finished = process.waitFor(30, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                return ShellResult(124, "", err + "timeout")
            }
            val code = process.exitValue()
            if (code != 0 || bytes.isEmpty()) {
                return ShellResult(code, "", err.ifBlank { "empty pull" })
            }
            local.writeBytes(bytes)
            ShellResult(0, "ok", "")
        } catch (e: Exception) {
            ShellResult(-1, "", e.message ?: "pull failed")
        }
    }

    fun pushFrom(local: File): ShellResult {
        val src = local.absolutePath
        val staged = "/data/local/tmp/revone_window_config.xml"
        val copy = su("cat '" + src + "' > '" + staged + "' && chmod 644 '" + staged + "'")
        if (!copy.ok) return copy
        val install = su(
            "cp '" + staged + "' '" + Paths.SYSTEM + "' && chown system:system '" + Paths.SYSTEM + "' && chmod 600 '" + Paths.SYSTEM + "'"
        )
        if (!install.ok) return install
        val context = su("restorecon '" + Paths.SYSTEM + "'")
        if (!context.ok) {
            su("chcon u:object_r:system_data_file:s0 '" + Paths.SYSTEM + "'")
        }
        su("rm -f '" + staged + "'")
        return ShellResult(0, "written", "")
    }

    fun systemFileExists(): Boolean {
        val r = su("test -f '" + Paths.SYSTEM + "' && echo yes")
        return r.ok && r.out.contains("yes")
    }

    fun restartTnt(command: String): ShellResult {
        val lines = command.split('\n', ';')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        var last = ShellResult(0, "", "")
        for (line in lines) {
            last = su(line)
            if (!last.ok) return last
        }
        return last
    }
}
