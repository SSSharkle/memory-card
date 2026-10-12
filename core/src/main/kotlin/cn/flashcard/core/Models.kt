package cn.flashcard.core

import java.io.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction
import java.util.zip.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable data class DeckData(val id: Long, val name: String, val parentId: Long? = null)

@Serializable
data class NoteData(
    val id: Long,
    val deckId: Long,
    val title: String,
    val front: String,
    val back: String,
    val tags: List<String>,
    val signature: String,
    val createdAt: Long,
)

@Serializable
data class CardData(
    val id: Long,
    val noteId: Long,
    val cloze: Int,
    val schedule: Schedule,
    val buriedUntil: Long = 0,
    val revision: Int = 0,
)

@Serializable
data class ReviewData(
    val id: String,
    val cardId: Long,
    val rating: Int,
    val timestamp: Long,
    val wasNew: Boolean,
    val previous: Schedule,
    val next: Schedule,
)

@Serializable data class TagData(val id: Long, val name: String)

@Serializable data class NoteTagData(val noteId: Long, val tagId: Long)

@Serializable
data class Settings(
    val dailyNew: Int = 20,
    val retention: Double = .9,
    val theme: String = "system",
    val typed: Boolean = false,
)

@Serializable
data class Backup(
    @kotlinx.serialization.Required val format: String = "exam-flashcards",
    @kotlinx.serialization.Required val version: Int = 1,
    val createdAt: Long,
    val decks: List<DeckData>,
    val notes: List<NoteData>,
    val cards: List<CardData>,
    val reviews: List<ReviewData>,
    val settings: Settings,
    val tags: List<TagData> = emptyList(),
    val noteTags: List<NoteTagData> = emptyList(),
)

object BackupCodec {
    val json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = false
        isLenient = false
        allowSpecialFloatingPointValues = false
    }

    fun validate(b: Backup) {
        require(b.format == "exam-flashcards" && b.version == 1) { "不支持的备份版本" }
        require(
            b.settings.dailyNew in 0..999 &&
                b.settings.retention in 0.7..0.97 &&
                b.settings.theme in listOf("system", "light", "dark")
        ) {
            "设置不合法"
        }
        require(
            b.createdAt >= 0 &&
                b.decks.size <= 10000 &&
                b.tags.size <= 100000 &&
                b.notes.size <= 100000 &&
                b.cards.size <= 500000 &&
                b.reviews.size <= 2000000
        ) {
            "备份超出数量上限或时间错误"
        }
        fun unique(ids: List<Long>) {
            require(ids.all { it > 0 } && ids.toSet().size == ids.size) { "记录 ID 无效或重复" }
        }
        unique(b.decks.map { it.id })
        unique(b.notes.map { it.id })
        unique(b.cards.map { it.id })
        unique(b.tags.map { it.id })
        require(
            b.tags.all { it.name.isNotBlank() } &&
                b.tags.map { it.name }.distinct().size == b.tags.size
        )
        val decks = b.decks.associateBy { it.id }
        val notes = b.notes.associateBy { it.id }
        val cards = b.cards.associateBy { it.id }
        require(b.decks.map { it.parentId to normalize(it.name) }.distinct().size == b.decks.size) {
            "同级牌组重复"
        }
        b.decks.forEach { d ->
            require(d.name.isNotBlank() && !d.name.contains('/'))
            var p: Long? = d.id
            val chain = mutableSetOf<Long>()
            while (p != null) {
                require(chain.size < 64 && chain.add(p) && decks.containsKey(p)) {
                    "牌组层级循环、过深或引用缺失"
                }
                p = decks[p]?.parentId
            }
        }
        b.notes.forEach { n ->
            require(
                decks.containsKey(n.deckId) &&
                    n.title.isNotBlank() &&
                    n.front.isNotBlank() &&
                    Cloze.valid(n.front) &&
                    n.createdAt >= 0 &&
                    n.tags.distinct().size == n.tags.size &&
                    n.tags.all { t -> t.isNotBlank() } &&
                    (Cloze.indices(n.front).isNotEmpty() || n.back.isNotBlank())
            ) {
                "笔记数据无效"
            }
            val path = mutableListOf<String>()
            var deck: DeckData? = decks[n.deckId]
            while (deck != null) {
                path.add(0, deck.name)
                deck = deck.parentId?.let { decks[it] }
            }
            require(
                n.signature ==
                    NoteDraft(
                            n.title,
                            path.first(),
                            path.drop(1).joinToString("/"),
                            n.tags,
                            n.front,
                            n.back,
                        )
                        .signature()
            ) {
                "笔记签名与内容不一致"
            }
        }
        require(b.notes.map { it.signature }.distinct().size == b.notes.size) { "笔记签名重复" }
        val tags = b.tags.associateBy { it.id }
        require(b.noteTags.distinct().size == b.noteTags.size)
        b.noteTags.forEach {
            require(notes.containsKey(it.noteId) && tags.containsKey(it.tagId)) { "标签关联缺失" }
        }
        val relations = b.noteTags.groupBy { it.noteId }
        b.notes.forEach { n ->
            require(
                n.tags.sorted() ==
                    relations[n.id].orEmpty().map { tags.getValue(it.tagId).name }.sorted()
            ) {
                "标签关联与笔记不一致"
            }
        }
        fun schedule(s: Schedule) {
            require(s.due >= 0 && (s.lastReview == null || s.lastReview >= 0))
            require(s.stability == null || s.stability.isFinite() && s.stability >= .001)
            require(s.difficulty == null || s.difficulty.isFinite() && s.difficulty in 1.0..10.0)
            require(
                (s.stability == null) == (s.difficulty == null) &&
                    (s.stability == null) == (s.lastReview == null)
            )
            require(
                when (s.state) {
                    CardState.REVIEW -> s.step == null && s.stability != null
                    CardState.LEARNING -> s.step in 0..1
                    CardState.RELEARNING -> s.step == 0 && s.stability != null
                }
            )
        }
        b.cards.forEach { c ->
            require(notes.containsKey(c.noteId) && c.revision >= 0 && c.buriedUntil >= 0)
            val indices = Cloze.indices(notes.getValue(c.noteId).front)
            require(if (indices.isEmpty()) c.cloze == 0 else c.cloze in indices)
            schedule(c.schedule)
        }
        val groupedCards = b.cards.groupBy { it.noteId }
        b.notes.forEach { n ->
            val indices = Cloze.indices(n.front).ifEmpty { listOf(0) }
            val actual = groupedCards[n.id].orEmpty().map { it.cloze }
            require(actual.sorted() == indices.sorted()) { "笔记卡片数量不一致" }
        }
        require(b.reviews.map { it.id }.toSet().size == b.reviews.size)
        b.reviews.forEach {
            require(
                it.id.isNotBlank() &&
                    cards.containsKey(it.cardId) &&
                    it.rating in 1..4 &&
                    it.timestamp >= 0 &&
                    it.next.lastReview == it.timestamp &&
                    it.next.due > it.timestamp &&
                    it.wasNew == (it.previous.lastReview == null) &&
                    (it.previous.lastReview == null || it.previous.lastReview <= it.timestamp)
            )
            schedule(it.previous)
            schedule(it.next)
        }
    }

    fun write(b: Backup, out: OutputStream) {
        validate(b)
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("backup.json"))
            zip.write(json.encodeToString(b).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
    }

    fun read(input: InputStream): Backup {
        val archive = ByteArrayOutputStream()
        val compressedBuffer = ByteArray(8192)
        var compressedCount = input.read(compressedBuffer)
        while (compressedCount != -1) {
            require(archive.size() + compressedCount <= 68 * 1024 * 1024) { "ZIP 备份过大" }
            archive.write(compressedBuffer, 0, compressedCount)
            compressedCount = input.read(compressedBuffer)
        }
        val raw = archive.toByteArray()
        require(raw.size >= 22) { "备份 ZIP 已损坏" }
        val footer =
            (raw.size - 22 downTo maxOf(0, raw.size - 65557)).firstOrNull {
                raw[it] == 0x50.toByte() &&
                    raw[it + 1] == 0x4b.toByte() &&
                    raw[it + 2] == 5.toByte() &&
                    raw[it + 3] == 6.toByte()
            } ?: error("备份 ZIP 缺少完整目录")
        val end = ByteBuffer.wrap(raw, footer, raw.size - footer).order(ByteOrder.LITTLE_ENDIAN)
        require(
            end.getShort(footer + 4).toInt() == 0 &&
                end.getShort(footer + 6).toInt() == 0 &&
                end.getShort(footer + 8).toInt() == 1 &&
                end.getShort(footer + 10).toInt() == 1 &&
                footer + 22 + (end.getShort(footer + 20).toInt() and 65535) == raw.size
        ) {
            "ZIP 目录不合法"
        }
        val directorySize = end.getInt(footer + 12)
        val directoryOffset = end.getInt(footer + 16)
        require(
            directoryOffset >= 0 &&
                directorySize >= 46 &&
                directoryOffset.toLong() + directorySize == footer.toLong()
        ) {
            "ZIP 目录损坏"
        }
        val bytes = ByteArrayOutputStream()
        var found = false
        ZipInputStream(ByteArrayInputStream(raw)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                require(entry.name == "backup.json" && !entry.isDirectory && !found) {
                    "备份 ZIP 包含未知或重复条目"
                }
                found = true
                val buffer = ByteArray(8192)
                var count = zip.read(buffer)
                while (count != -1) {
                    require(bytes.size() + count <= 64 * 1024 * 1024) { "备份过大" }
                    bytes.write(buffer, 0, count)
                    count = zip.read(buffer)
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        require(found) { "备份缺少 backup.json" }
        val decoder =
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
        return json
            .decodeFromString<Backup>(
                decoder.decode(ByteBuffer.wrap(bytes.toByteArray())).toString()
            )
            .also { validate(it) }
    }
}
