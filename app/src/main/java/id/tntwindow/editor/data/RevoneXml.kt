package id.tntwindow.editor.data

import id.tntwindow.editor.domain.AppNode
import id.tntwindow.editor.domain.NestedIssue
import id.tntwindow.editor.domain.ParseReport
import id.tntwindow.editor.domain.VideoSpecial
import id.tntwindow.editor.domain.WindowSpec

object RevoneXml {
    private val pkgRe = Regex("""package\s*=\s*"([^"]+)"""")
    private val videoRe = Regex("""<special-video\b[^>]*/\s*>""")

    fun parse(text: String): ParseReport {
        val apps = mutableListOf<AppNode>()
        val seen = mutableMapOf<String, Int>()
        val dirty = mutableListOf<String>()
        val missingMode = mutableListOf<String>()
        val nestedIssues = mutableListOf<NestedIssue>()
        val lowerResize = mutableListOf<String>()
        var cursor = 0
        while (true) {
            val found = nextApplication(text, cursor) ?: break
            val range = found.range
            val raw = text.substring(range.first, range.last)
            if (found.issue != null) nestedIssues += found.issue
            val headEnd = raw.indexOf('>')
            val head = if (headEnd >= 0) raw.substring(0, headEnd + 1) else raw
            val pkg = pkgRe.find(head)?.groupValues?.get(1) ?: ""
            val dirtyAttr = head.contains("package =")
            if (dirtyAttr && pkg.isNotEmpty()) dirty += pkg
            else if (dirtyAttr) dirty += "(空包名 @${range.first})"
            val body = if (headEnd >= 0) raw.substring(headEnd + 1) else ""
            val stripped = stripSpecialActivity(body)
            if (Regex("<resizemode>").containsMatchIn(stripped) &&
                !Regex("<resizeMode>").containsMatchIn(stripped) &&
                pkg.isNotEmpty()
            ) {
                lowerResize += pkg
            }
            val spec = readSpec(stripped)
            if (spec.windowMode == null && pkg.isNotEmpty()) missingMode += pkg
            val index = seen[pkg] ?: 0
            if (pkg.isNotEmpty()) seen[pkg] = index + 1
            apps += AppNode(
                packageName = pkg,
                spec = spec,
                start = range.first,
                end = range.last,
                hasSpecialActivity = body.contains("<special-activity"),
                dirtyAttr = dirtyAttr,
                duplicateIndex = index,
            )
            cursor = range.last
        }
        val videos = mutableListOf<VideoSpecial>()
        videoRe.findAll(text).forEach { m ->
            val tag = m.value
            val p = Regex("""packageName\s*=\s*"([^"]+)"""").find(tag)?.groupValues?.get(1) ?: ""
            val t = Regex("""type\s*=\s*"([^"]+)"""").find(tag)?.groupValues?.get(1)?.toIntOrNull() ?: 1
            val after = text.substring(m.range.last + 1, minOf(text.length, m.range.last + 80))
            val comment = Regex("""<!--\s*(.*?)\s*-->""").find(after)?.groupValues?.get(1)?.trim().orEmpty()
            videos += VideoSpecial(p, t, comment)
        }
        val dups = seen.filter { it.value > 1 }
        return ParseReport(
            apps = apps,
            videos = videos,
            duplicatePackages = dups.keys.toList(),
            duplicateCounts = dups,
            dirtyAttrPackages = dirty.distinct(),
            nestedIssues = nestedIssues,
            lowercaseResizePackages = lowerResize.distinct(),
            missingWindowMode = missingMode.distinct(),
        )
    }

    fun verify(report: ParseReport, plan: Map<String, WindowSpec>): String? {
        val by = report.apps.filter { it.duplicateIndex == 0 }.associateBy { it.packageName }
        val bad = mutableListOf<String>()
        for ((pkg, spec) in plan) {
            val actual = by[pkg]?.spec
            if (actual == null) {
                bad += "$pkg 写盘后找不到节点"
            } else if (actual != spec) {
                bad += "$pkg 期望 ${spec.fingerprint()} 实际 ${actual.fingerprint()}"
            }
        }
        return if (bad.isEmpty()) null else "写回校验失败：\n" + bad.joinToString("\n")
    }

    fun patchApplications(text: String, plan: Map<String, WindowSpec>): String {
        if (plan.isEmpty()) return text
        val report = parse(text)
        val used = mutableSetOf<String>()
        val parts = StringBuilder()
        var cursor = 0
        for (app in report.apps) {
            if (app.duplicateIndex != 0) continue
            val spec = plan[app.packageName] ?: continue
            used += app.packageName
            parts.append(text, cursor, app.start)
            val raw = text.substring(app.start, app.end)
            parts.append(patchBlock(raw, spec))
            cursor = app.end
        }
        parts.append(text, cursor, text.length)
        var out = parts.toString()
        val missing = plan.keys.filter { it !in used }
        if (missing.isNotEmpty()) {
            val insertAt = out.lastIndexOf("</Configuration>")
            if (insertAt >= 0) {
                val sb = StringBuilder(out.substring(0, insertAt))
                for (pkg in missing) {
                    sb.append(newBlock(pkg, plan.getValue(pkg)))
                }
                if (!sb.endsWith("\n")) sb.append('\n')
                sb.append(out.substring(insertAt))
                out = sb.toString()
            }
        }
        return out
    }

    fun deletePackages(text: String, packages: Set<String>): String {
        if (packages.isEmpty()) return text
        val report = parse(text)
        val parts = StringBuilder()
        var cursor = 0
        for (app in report.apps) {
            if (app.packageName in packages && app.duplicateIndex == 0) {
                parts.append(text, cursor, app.start)
                cursor = skipFollowingNewlines(text, app.end)
            }
        }
        parts.append(text, cursor, text.length)
        return parts.toString()
    }

    fun patchVideos(text: String, videos: List<VideoSpecial>): String {
        var out = text
        val existing = videoRe.findAll(out).toList()
        for (m in existing.asReversed()) {
            val start = m.range.first
            var end = m.range.last + 1
            val rest = out.substring(end, minOf(out.length, end + 120))
            val c = Regex("""^[ \t]*<!--.*?-->""").find(rest)
            if (c != null) end += c.value.length
            out = out.removeRange(start, end)
        }
        val firstApp = out.indexOf("<application")
        val anchor = if (firstApp >= 0) firstApp else out.lastIndexOf("</Configuration>").let { if (it < 0) out.length else it }
        val sb = StringBuilder()
        for (v in videos) {
            val comment = if (v.comment.isBlank()) "" else " <!-- " + v.comment + " -->"
            sb.append("    <special-video packageName=\"").append(v.packageName)
                .append("\" type=\"").append(v.type).append("\" />").append(comment).append('\n')
        }
        sb.append('\n')
        return out.substring(0, anchor) + sb.toString() + out.substring(anchor)
    }

    private data class FoundApp(val range: IntRange, val issue: NestedIssue?)

    private fun nextApplication(text: String, from: Int): FoundApp? {
        val start = text.indexOf("<application", from)
        if (start < 0) return null
        val headEnd = text.indexOf('>', start)
        val head = if (headEnd >= 0) text.substring(start, headEnd + 1) else text.substring(start, minOf(text.length, start + 80))
        val pkg = pkgRe.find(head)?.groupValues?.get(1) ?: ""
        var depth = 1
        var pos = start + 12
        var nestedAt = -1
        var nestedPkg = ""
        while (pos < text.length) {
            val nextOpen = text.indexOf("<application", pos)
            val nextClose = text.indexOf("</application>", pos)
            if (nextClose < 0) {
                val end = text.length
                return FoundApp(
                    IntRange(start, end),
                    NestedIssue(
                        packageName = pkg.ifBlank { "(未知)" },
                        reason = "找不到 </application> 闭合标签",
                        start = start,
                        end = end,
                        snippet = snippet(text, start, end),
                    ),
                )
            }
            if (nextOpen >= 0 && nextOpen < nextClose) {
                depth += 1
                if (nestedAt < 0) {
                    nestedAt = nextOpen
                    val innerHeadEnd = text.indexOf('>', nextOpen)
                    val innerHead = if (innerHeadEnd >= 0) text.substring(nextOpen, innerHeadEnd + 1) else ""
                    nestedPkg = pkgRe.find(innerHead)?.groupValues?.get(1) ?: ""
                }
                pos = nextOpen + 12
            } else {
                depth -= 1
                val end = nextClose + 14
                if (depth == 0) {
                    val issue = if (nestedAt >= 0) {
                        NestedIssue(
                            packageName = pkg.ifBlank { "(未知)" },
                            reason = "节点内部又出现 <application" + if (nestedPkg.isNotEmpty()) "（内层 $nestedPkg）" else "",
                            start = start,
                            end = end,
                            snippet = snippet(text, nestedAt, end),
                        )
                    } else null
                    return FoundApp(IntRange(start, end), issue)
                }
                pos = end
            }
        }
        return FoundApp(
            IntRange(start, text.length),
            NestedIssue(
                packageName = pkg.ifBlank { "(未知)" },
                reason = "扫描到文件末尾仍未闭合",
                start = start,
                end = text.length,
                snippet = snippet(text, start, text.length),
            ),
        )
    }

    private fun snippet(text: String, start: Int, end: Int): String {
        val a = start.coerceAtLeast(0)
        val b = end.coerceAtMost(text.length).coerceAtMost(a + 220)
        return text.substring(a, b).replace("\r", "").replace("\n", "\\n").trim()
    }

    private fun stripSpecialActivity(body: String): String {
        return body.replace(Regex("""<special-activity[\s\S]*?</special-activity>"""), "")
    }

    private fun specialRanges(block: String): List<IntRange> {
        return Regex("""<special-activity[\s\S]*?</special-activity>""")
            .findAll(block)
            .map { IntRange(it.range.first, it.range.last + 1) }
            .toList()
    }

    private fun readSpec(body: String): WindowSpec {
        fun v(tag: String): Int? {
            val m = Regex("<" + tag + ">\\s*([^<]*)</" + tag + ">", RegexOption.IGNORE_CASE).find(body)
            return m?.groupValues?.get(1)?.trim()?.toIntOrNull()
        }
        return WindowSpec(
            windowMode = v("windowMode"),
            width = v("width"),
            height = v("height"),
            minWidth = v("minWidth"),
            minHeight = v("minHeight"),
            resizeMode = v("resizeMode"),
        )
    }

    private fun patchBlock(raw: String, spec: WindowSpec): String {
        var block = raw
        block = upsert(block, "windowMode", spec.windowMode)
        block = upsert(block, "width", spec.width)
        block = upsert(block, "height", spec.height)
        block = upsert(block, "minWidth", spec.minWidth)
        block = upsert(block, "minHeight", spec.minHeight)
        block = upsert(block, "resizeMode", spec.resizeMode)
        val headEnd = block.indexOf('>')
        if (headEnd > 0) {
            val head = block.substring(0, headEnd + 1)
            val fixed = head.replace(Regex("""package\s*=\s*""""), "package=\"")
            block = fixed + block.substring(headEnd + 1)
        }
        return block
    }

    private fun upsert(block: String, tag: String, value: Int?): String {
        val ranges = specialRanges(block)
        fun inside(i: Int) = ranges.any { i >= it.first && i < it.last }
        val re = Regex("<" + tag + ">\\s*[^<]*</" + tag + ">", RegexOption.IGNORE_CASE)
        val match = re.findAll(block).firstOrNull { !inside(it.range.first) }
        if (value == null) {
            if (match == null) return block
            var start = match.range.first
            var end = match.range.last + 1
            if (start > 0 && block[start - 1] == '\n') start -= 1
            return block.removeRange(start, end)
        }
        if (match != null) {
            val original = match.value
            val openEnd = original.indexOf('>')
            val closeStart = original.lastIndexOf("</")
            val rebuilt = original.substring(0, openEnd + 1) + value + original.substring(closeStart)
            return block.replaceRange(match.range.first, match.range.last + 1, rebuilt)
        }
        val headEnd = block.indexOf('>')
        if (headEnd < 0) return block
        val indent = Regex("\\n([ \\t]+)<").find(block)?.groupValues?.get(1) ?: "        "
        val line = "\n" + indent + "<" + tag + ">" + value + "</" + tag + ">"
        return block.substring(0, headEnd + 1) + line + block.substring(headEnd + 1)
    }

    private fun newBlock(pkg: String, spec: WindowSpec): String {
        val sb = StringBuilder()
        sb.append("    <application package=\"").append(pkg).append("\">\n")
        fun line(tag: String, v: Int?) {
            if (v != null) sb.append("        <").append(tag).append(">").append(v).append("</").append(tag).append(">\n")
        }
        line("windowMode", spec.windowMode)
        line("width", spec.width)
        line("height", spec.height)
        line("minWidth", spec.minWidth)
        line("minHeight", spec.minHeight)
        line("resizeMode", spec.resizeMode)
        sb.append("    </application>\n")
        return sb.toString()
    }

    private fun skipFollowingNewlines(text: String, from: Int): Int {
        var i = from
        while (i < text.length && (text[i] == '\n' || text[i] == '\r')) i++
        return i
    }
}