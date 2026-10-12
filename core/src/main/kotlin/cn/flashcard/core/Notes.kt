package cn.flashcard.core

import java.security.MessageDigest
import java.text.Normalizer
import kotlinx.serialization.Serializable
import org.commonmark.node.*
import org.commonmark.parser.IncludeSourceSpans
import org.commonmark.parser.Parser
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings

@Serializable
data class NoteDraft(
    val title: String,
    val subject: String,
    val deck: String,
    val tags: List<String>,
    val front: String,
    val back: String,
) {
    val clozeIndices
        get() = Cloze.indices(front)

    val cardCount
        get() = maxOf(1, clozeIndices.size)

    val type
        get() = if (clozeIndices.isEmpty()) "basic" else "cloze"

    fun signature(): String =
        sha256(
            listOf(
                    normalize(subject),
                    deck.split('/').joinToString("/") { normalize(it) },
                    type,
                    normalize(front),
                    normalize(back),
                )
                .joinToString("\u0000")
        )
}

fun normalize(s: String) = Normalizer.normalize(s.trim().replace("\r\n", "\n"), Normalizer.Form.NFC)

fun sha256(s: String) =
    MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") {
        "%02x".format(it)
    }

object Cloze {
    private val pattern = Regex("\\{\\{c([1-9]\\d*)::([^{}]+?)\\}\\}")

    fun indices(text: String): List<Int> =
        pattern
            .findAll(text)
            .map { it.groupValues[1].toIntOrNull() ?: error("挖空编号过大") }
            .distinct()
            .sorted()
            .toList()

    fun valid(text: String): Boolean = !pattern.replace(text, "").contains("{{c")

    fun render(text: String, index: Int, answer: Boolean) =
        pattern.replace(text) { m ->
            val parts = m.groupValues[2].split("::", limit = 2)
            if (m.groupValues[1].toInt() == index && !answer) "【${parts.getOrElse(1){"…"}}】"
            else parts[0]
        }
}

data class ImportIssue(val line: Int, val message: String)

data class ImportCandidate(val line: Int, val note: NoteDraft)

data class ImportPreview(
    val candidates: List<ImportCandidate>,
    val issues: List<ImportIssue>,
    val duplicates: Int,
) {
    val cardCount
        get() = candidates.sumOf { it.note.cardCount }
}

object MarkdownImport {
    fun parse(source: String, existing: Set<String> = emptySet()): ImportPreview {
        val lines = source.replace("\r\n", "\n").split('\n')
        val errors = mutableListOf<ImportIssue>()
        val candidates = mutableListOf<ImportCandidate>()
        val seen = existing.toMutableSet()
        var duplicates = 0
        var start = 0
        var subject = "未分类"
        var deck = "收件箱"
        var tags = emptyList<String>()
        if (lines.firstOrNull()?.trim() == "---") {
            val end = (1 until lines.size).firstOrNull { lines[it].trim() == "---" }
            if (end == null)
                return ImportPreview(emptyList(), listOf(ImportIssue(1, "YAML 元数据缺少结束 ---")), 0)
            try {
                val settings =
                    LoadSettings.builder()
                        .setAllowDuplicateKeys(false)
                        .setMaxAliasesForCollections(0)
                        .setCodePointLimit(100000)
                        .build()
                val map =
                    Load(settings).loadFromString(lines.subList(1, end).joinToString("\n"))
                        as? Map<*, *> ?: error("元数据必须为映射")
                subject = (map["subject"] as? String)?.trim() ?: error("subject 必须为字符串")
                deck = (map["deck"] as? String)?.trim() ?: error("deck 必须为字符串")
                subject = normalize(subject)
                require(!subject.contains('/')) { "subject 只能填写一个科目，子牌组路径请填写在 deck 中" }
                deck = deck.split('/').joinToString("/") { normalize(it) }
                require(deck.split('/').size < 64) { "牌组层级最多 64 层" }
                require(subject.isNotBlank() && deck.split('/').all { it.isNotBlank() }) {
                    "科目或牌组路径不能为空"
                }
                tags =
                    (map["tags"] as? List<*>)?.map { (it as? String)?.trim() ?: error("标签必须为字符串") }
                        ?: if (map["tags"] == null) emptyList() else error("tags 必须为列表")
                require(tags.all { it.isNotEmpty() }) { "标签不能为空" }
            } catch (e: Exception) {
                return ImportPreview(
                    emptyList(),
                    listOf(ImportIssue(2, "元数据错误：${e.message?.take(200)}")),
                    0,
                )
            }
            start = end + 1
        }
        // CommonMark AST recognizes headings while excluding fenced and indented code blocks.
        val document =
            Parser.builder()
                .includeSourceSpans(IncludeSourceSpans.BLOCKS)
                .build()
                .parse(lines.drop(start).joinToString("\n"))
        val headings = mutableListOf<Pair<Int, Heading>>()
        var node = document.firstChild
        while (node != null) {
            if (node is Heading) headings += (node.sourceSpans.first().lineIndex + start) to node
            node = node.next
        }
        val notes = headings.filter { it.second.level == 2 }
        if (notes.isEmpty()) errors += ImportIssue(start + 1, "未找到 ## 笔记标题")
        fun text(h: Heading): String {
            val out = StringBuilder()
            h.accept(
                object : AbstractVisitor() {
                    override fun visit(t: Text) {
                        out.append(t.literal)
                    }

                    override fun visit(c: Code) {
                        out.append(c.literal)
                    }
                }
            )
            return out.toString().trim()
        }
        for ((position, entry) in notes.withIndex()) {
            val from = entry.first
            val until = notes.getOrNull(position + 1)?.first ?: lines.size
            val parts =
                headings.filter { it.first > from && it.first < until && it.second.level == 3 }
            val fronts = parts.filter { text(it.second) == "正面" }
            val backs = parts.filter { text(it.second) == "背面" }
            if (fronts.size != 1 || backs.size != 1 || fronts[0].first >= backs[0].first) {
                errors += ImportIssue(from + 1, "每条笔记需要依次包含唯一的 ### 正面 和 ### 背面")
                continue
            }
            val front = lines.subList(fronts[0].first + 1, backs[0].first).joinToString("\n").trim()
            val back = lines.subList(backs[0].first + 1, until).joinToString("\n").trim()
            if (front.isBlank() || (!front.contains("{{c") && back.isBlank())) {
                errors += ImportIssue(from + 1, "正面和普通卡背面不能为空")
                continue
            }
            if (!Cloze.valid(front)) {
                errors += ImportIssue(fronts[0].first + 1, "挖空格式错误，使用 {{c1::答案}} 或 {{c1::答案::提示}}")
                continue
            }
            try {
                val draft =
                    NoteDraft(text(entry.second), subject, deck, tags.distinct(), front, back)
                require(draft.title.isNotBlank())
                if (seen.add(draft.signature())) candidates += ImportCandidate(from + 1, draft)
                else duplicates++
            } catch (e: Exception) {
                errors += ImportIssue(from + 1, "笔记格式错误：${e.message}")
            }
        }
        return ImportPreview(candidates, errors, duplicates)
    }
}
