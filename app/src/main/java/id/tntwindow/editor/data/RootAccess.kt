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
    private val liveLock = Any()
    private var liveProcess: Process? = null
    private var liveIn: java.io.BufferedWriter? = null
    private var liveOut: java.io.BufferedReader? = null
    private var liveSeq = 0
    fun available(): Boolean {
        val r = su("id")
        return r.ok && r.out.contains("uid=0")
    }

    fun su(command: String, timeoutSec: Long = 30): ShellResult {
        return run(listOf("su", "-c", command), timeoutSec)
    }

    fun suStdin(command: String, input: ByteArray, timeoutSec: Long = 30): ShellResult {
        return stdin(listOf("su", "-c", command), input, timeoutSec)
    }

    fun suMountStdin(command: String, input: ByteArray, timeoutSec: Long = 30): ShellResult {
        val mm = stdin(listOf("su", "-mm", "-c", command), input, timeoutSec)
        if (mm.ok) return mm
        val master = stdin(listOf("su", "--mount-master", "-c", command), input, timeoutSec)
        if (master.ok) return master
        return suStdin(command, input, timeoutSec)
    }

    private fun stdin(args: List<String>, input: ByteArray, timeoutSec: Long): ShellResult {
        return try {
            val process = ProcessBuilder(args)
                .redirectErrorStream(false)
                .start()
            try {
                process.outputStream.use { it.write(input) }
            } catch (_: Exception) {
            }
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

    fun suUser(uid: Int, command: String, timeoutSec: Long = 30): ShellResult {
        return run(listOf("su", uid.toString(), "-c", command), timeoutSec)
    }

    fun sh(command: String, timeoutSec: Long = 30): ShellResult {
        return run(listOf("sh", "-c", command), timeoutSec)
    }

    fun suMount(command: String, timeoutSec: Long = 30): ShellResult {
        val mm = run(listOf("su", "-mm", "-c", command), timeoutSec)
        if (mm.ok) return mm
        val master = run(listOf("su", "--mount-master", "-c", command), timeoutSec)
        if (master.ok) return master
        return su(command, timeoutSec)
    }

    fun writeSystemJson(local: File, dest: String, staged: String): ShellResult {
        if (!local.exists() || local.length() == 0L) return ShellResult(-1, "", "empty")
        val src = local.absolutePath
        val want = local.length()
        val script =
            "cat '" + src + "' > '" + staged + "'" +
                "; chmod 666 '" + staged + "'" +
                "; cat '" + staged + "' > '" + dest + "'" +
                "; chown system:system '" + dest + "'" +
                "; chmod 644 '" + dest + "'" +
                "; restorecon '" + dest + "' >/dev/null 2>&1 || true" +
                "; wc -c '" + dest + "'" +
                "; wc -c '" + staged + "'"
        val liveR = live(script, 10000)
        if (parseSize(liveR.out) == want) return ShellResult(0, "ok", "")
        suMount(script)
        val suR = su(script)
        if (parseSize(suR.out) == want) return ShellResult(0, "ok", "")
        if (pullLen(dest) == want || pullLen(staged) == want) return ShellResult(0, "ok", "")
        return ShellResult(-1, suR.out, suR.err.ifBlank { liveR.err.ifBlank { liveR.out.ifBlank { "stage missing" } } })
    }

    private fun pullLen(path: String): Long {
        return try {
            val process = ProcessBuilder("su", "-c", "cat '" + path + "'")
                .redirectErrorStream(false)
                .start()
            val n = process.inputStream.readBytes().size.toLong()
            val finished = process.waitFor(10, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                -1L
            } else if (n > 0L) {
                n
            } else {
                -1L
            }
        } catch (_: Exception) {
            -1L
        }
    }
    private fun remoteSize(path: String): Long {
        val r = su("wc -c '" + path + "'")
        val n = parseSize(r.out)
        if (n >= 0L) return n
        return parseSize(live("wc -c '" + path + "'").out)
    }

    private fun parseSize(text: String): Long {
        val line = text.trim().lines().map { it.trim() }.lastOrNull { it.isNotEmpty() } ?: return -1L
        val first = line.split(Regex("\\s+")).firstOrNull() ?: return -1L
        return first.toLongOrNull() ?: -1L
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


    fun live(command: String, timeoutMs: Long = 4000): ShellResult {
        val cmd = command.trim()
        if (cmd.isEmpty()) return ShellResult(-1, "", "empty")
        synchronized(liveLock) {
            try {
                ensureLiveLocked()
                val writer = liveIn
                val reader = liveOut
                if (writer == null || reader == null) return su(cmd)
                liveSeq += 1
                val marker = "__CB_LIVE_" + liveSeq + "__"
                writer.write(cmd)
                writer.write("\necho ")
                writer.write(marker)
                writer.write(" \$?\n")
                writer.flush()
                val out = StringBuilder()
                val deadline = System.nanoTime() + timeoutMs * 1_000_000L
                while (System.nanoTime() < deadline) {
                    if (!reader.ready()) {
                        Thread.sleep(2)
                        continue
                    }
                    val line = reader.readLine() ?: break
                    if (line.startsWith(marker)) {
                        val code = line.substring(marker.length).trim().toIntOrNull() ?: 0
                        return ShellResult(code, out.toString().trimEnd(), "")
                    }
                    if (out.isNotEmpty()) out.append('\n')
                    out.append(line)
                }
                dropLiveLocked()
                return su(cmd)
            } catch (_: Exception) {
                dropLiveLocked()
                return su(cmd)
            }
        }
    }

    private fun ensureLiveLocked() {
        val existing = liveProcess
        if (existing != null && existing.isAlive && liveIn != null && liveOut != null) return
        dropLiveLocked()
        val process = ProcessBuilder("su")
            .redirectErrorStream(true)
            .start()
        val writer = java.io.BufferedWriter(java.io.OutputStreamWriter(process.outputStream, StandardCharsets.UTF_8))
        val reader = java.io.BufferedReader(java.io.InputStreamReader(process.inputStream, StandardCharsets.UTF_8))
        writer.write("echo __CB_LIVE_READY__\n")
        writer.flush()
        val deadline = System.nanoTime() + 3_000_000_000L
        var ready = false
        while (System.nanoTime() < deadline) {
            if (!reader.ready()) {
                Thread.sleep(5)
                continue
            }
            val line = reader.readLine() ?: break
            if (line.contains("__CB_LIVE_READY__")) {
                ready = true
                break
            }
        }
        if (!ready || !process.isAlive) {
            process.destroyForcibly()
            throw IllegalStateException("su live")
        }
        liveProcess = process
        liveIn = writer
        liveOut = reader
    }

    private fun dropLiveLocked() {
        try { liveIn?.close() } catch (_: Exception) {}
        try { liveOut?.close() } catch (_: Exception) {}
        try { liveProcess?.destroyForcibly() } catch (_: Exception) {}
        liveIn = null
        liveOut = null
        liveProcess = null
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
