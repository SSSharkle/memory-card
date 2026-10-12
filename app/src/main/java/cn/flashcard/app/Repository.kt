package cn.flashcard.app

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.*
import cn.flashcard.core.*
import java.io.File
import java.time.*
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private val Context.settingsDataStore by preferencesDataStore("settings")

class Repository(private val context: Context, val db: FlashDatabase) {
    val dao = db.dao()
    private val mutation = Mutex()
    val generation = MutableStateFlow(0L)

    private suspend fun <T> mutate(block: suspend () -> T): T =
        mutation.withLock { db.withTransaction { block() } }

    suspend fun initialize() {
        db.withTransaction {
            val firstRun = dao.preference() == null
            if (dao.preference() == null) dao.savePreference(Preference())
            if (firstRun && dao.decks().isEmpty()) {
                val cs = dao.addDeck(Deck(name = "408"))
                listOf("数据结构", "计算机组成原理", "操作系统", "计算机网络").forEach {
                    dao.addDeck(Deck(name = it, parentId = cs))
                }
                val politics = dao.addDeck(Deck(name = "政治"))
                listOf("马原", "毛中特", "习思想", "史纲", "思修法治", "形势与政策").forEach {
                    dao.addDeck(Deck(name = it, parentId = politics))
                }
                val now = System.currentTimeMillis()
                val samples =
                    listOf(
                        NoteDraft(
                            "进程与线程 · 示例",
                            "408",
                            "操作系统",
                            listOf("示例", "进程"),
                            "进程和线程在资源分配与调度上的区别？",
                            "进程是资源分配的基本单位；线程是 CPU 调度的基本单位。同一进程中的线程共享进程的资源。",
                        ),
                        NoteDraft(
                            "栈与队列 · 挖空示例",
                            "408",
                            "数据结构",
                            listOf("示例", "线性结构"),
                            "栈遵循 {{c1::后进先出::顺序}}，队列遵循 {{c2::先进先出::顺序}}。",
                            "栈在栈顶插入和删除；队列在队尾入队，在队头出队。",
                        ),
                        NoteDraft(
                            "实践与认识 · 示例",
                            "政治",
                            "马原",
                            listOf("示例", "认识论"),
                            "检验认识真理性的标准是什么？",
                            "实践是检验认识真理性的唯一标准。",
                        ),
                    )
                samples.forEach { insert(it, resolveDeck(it), now) }
            }
        }
        mirrorSettings(dao.preference()!!.value)
    }

    private suspend fun mirrorSettings(settings: Settings) {
        context.settingsDataStore.updateData { current ->
            current.toMutablePreferences().apply {
                this[PreferenceKeys.SNAPSHOT] = BackupCodec.json.encodeToString(settings)
            }
        }
    }

    suspend fun saveSettings(settings: Settings) {
        require(settings.dailyNew in 0..999 && settings.retention in 0.7..0.97)
        mutate {
            dao.savePreference(Preference(value = settings))
            mirrorSettings(settings)
        }
    }

    fun dayBounds(now: Long, zone: ZoneId = ZoneId.systemDefault()): Pair<Long, Long> {
        val date = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return date.atStartOfDay(zone).toInstant().toEpochMilli() to
            date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    }

    fun queue(cards: List<Card>, reviews: List<Review>, settings: Settings, now: Long): List<Card> {
        val (start, end) = dayBounds(now)
        val used =
            reviews
                .filter { it.wasNew && it.timestamp in start until end }
                .map { it.cardId }
                .distinct()
                .size
        val eligible = cards.filter { it.buriedUntil <= now }
        return eligible
            .filter { it.schedule.lastReview != null && it.due <= now }
            .sortedWith(compareBy<Card> { it.due }.thenBy { it.id }) +
            eligible
                .filter { it.schedule.lastReview == null }
                .sortedBy { it.id }
                .take((settings.dailyNew - used).coerceAtLeast(0))
    }

    suspend fun rate(
        id: Long,
        revision: Int,
        rating: Rating,
        now: Long,
        expectedGeneration: Long = generation.value,
    ): Boolean = mutate {
        if (expectedGeneration != generation.value) return@mutate false
        val card = dao.card(id) ?: return@mutate false
        if (card.revision != revision) return@mutate false
        val settings = dao.preference()!!.value
        if (queue(dao.cards(), dao.reviews(), settings, now).none { it.id == id })
            return@mutate false
        val next = Fsrs(settings.retention).review(card.schedule, rating, now)
        dao.updateCard(card.copy(schedule = next, due = next.due, revision = card.revision + 1))
        dao.addReview(
            Review(
                UUID.randomUUID().toString(),
                id,
                rating.value,
                now,
                card.schedule.lastReview == null,
                card.schedule,
                next,
            )
        )
        val tomorrow = dayBounds(now).second
        dao.cards()
            .filter { it.noteId == card.noteId && it.id != id }
            .forEach { dao.updateCard(it.copy(buriedUntil = tomorrow)) }
        true
    }

    suspend fun createDeck(name: String, parentId: Long?) {
        require(name.trim().isNotBlank() && !name.contains('/'))
        mutate {
            val decks = dao.decks()
            require(parentId == null || path(parentId, decks).size < 64) { "牌组层级最多 64 层" }
            require(decks.none { it.parentId == parentId && it.name == normalize(name) }) {
                "同级牌组已存在"
            }
            dao.addDeck(Deck(name = normalize(name), parentId = parentId))
        }
    }

    suspend fun renameDeck(deck: Deck, name: String) {
        require(name.trim().isNotBlank() && !name.contains('/'))
        mutate {
            require(
                dao.decks().none {
                    it.id != deck.id && it.parentId == deck.parentId && it.name == normalize(name)
                }
            )
            dao.updateDeck(deck.copy(name = normalize(name)))
            val decks = dao.decks()
            dao.notes().forEach { n ->
                val p = path(n.deckId, decks)
                val signature =
                    NoteDraft(
                            n.title,
                            p.first(),
                            p.drop(1).joinToString("/"),
                            n.tags,
                            n.front,
                            n.back,
                        )
                        .signature()
                if (n.signature != signature) dao.updateNote(n.copy(signature = signature))
            }
        }
    }

    suspend fun deleteDeck(deck: Deck) {
        mutate {
            require(
                dao.notes().none { it.deckId == deck.id } &&
                    dao.decks().none { it.parentId == deck.id }
            ) {
                "请先移走笔记并删除子牌组"
            }
            dao.deleteDeck(deck.id)
        }
    }

    fun path(deckId: Long, decks: List<Deck>): List<String> {
        val map = decks.associateBy { it.id }
        val result = mutableListOf<String>()
        var current = map[deckId]
        while (current != null) {
            result.add(0, current.name)
            current = current.parentId?.let { map[it] }
        }
        return result
    }

    private suspend fun resolveDeck(
        draft: NoteDraft,
        cache: MutableMap<Pair<Long?, String>, Long>? = null,
    ): Long {
        require(!draft.subject.contains('/')) { "科目名称不能包含 /" }
        val names = (listOf(draft.subject) + draft.deck.split('/')).map { normalize(it) }
        require(names.size <= 64 && names.all { it.isNotBlank() }) { "牌组路径不能为空且最多 64 层" }
        val decks =
            cache ?: dao.decks().associate { (it.parentId to it.name) to it.id }.toMutableMap()
        var parent: Long? = null
        for (name in names) {
            val key = parent to name
            parent =
                decks[key]
                    ?: dao.addDeck(Deck(name = name, parentId = parent)).also { decks[key] = it }
        }
        return parent!!
    }

    private suspend fun syncTags(
        noteId: Long,
        tags: List<String>,
        cache: MutableMap<String, Long>? = null,
    ) {
        dao.clearNoteTags(noteId)
        val existing = cache ?: dao.tags().associate { it.name to it.id }.toMutableMap()
        tags.distinct().forEach { name ->
            val id = existing[name] ?: dao.addTag(Tag(name = name)).also { existing[name] = it }
            dao.addNoteTag(NoteTag(noteId, id))
        }
    }

    private suspend fun insert(
        draft: NoteDraft,
        deckId: Long,
        now: Long,
        tagCache: MutableMap<String, Long>? = null,
    ): Long {
        val id =
            dao.addNote(
                Note(
                    deckId = deckId,
                    title = draft.title,
                    front = draft.front,
                    back = draft.back,
                    tags = draft.tags,
                    signature = draft.signature(),
                    createdAt = now,
                )
            )
        syncTags(id, draft.tags, tagCache)
        draft.clozeIndices
            .ifEmpty { listOf(0) }
            .forEach { dao.addCard(Card(noteId = id, cloze = it)) }
        return id
    }

    suspend fun importNotes(drafts: List<NoteDraft>, now: Long): Int = mutate {
        val seen = dao.signatures().toMutableSet()
        // One cache per transaction avoids 10,000 repeated deck/tag table scans.
        val decks = dao.decks().associate { (it.parentId to it.name) to it.id }.toMutableMap()
        val tags = dao.tags().associate { it.name to it.id }.toMutableMap()
        var count = 0
        drafts.forEach {
            if (seen.add(it.signature())) {
                insert(it, resolveDeck(it, decks), now, tags)
                count++
            }
        }
        count
    }

    suspend fun saveNote(
        previous: Note?,
        deckId: Long,
        title: String,
        front: String,
        back: String,
        tags: List<String>,
        now: Long,
    ) = mutate {
        require(
            title.isNotBlank() &&
                front.isNotBlank() &&
                Cloze.valid(front) &&
                (Cloze.indices(front).isNotEmpty() || back.isNotBlank())
        ) {
            "填写标题、正面、背面，并检查挖空格式"
        }
        val path = path(deckId, dao.decks())
        val draft =
            NoteDraft(
                title.trim(),
                path.first(),
                path.drop(1).joinToString("/"),
                tags.distinct(),
                front.trim(),
                back.trim(),
            )
        require(dao.notes().none { it.id != previous?.id && it.signature == draft.signature() }) {
            "完全相同的笔记已存在"
        }
        if (previous == null) insert(draft, deckId, now)
        else {
            dao.updateNote(
                previous.copy(
                    deckId = deckId,
                    title = draft.title,
                    front = draft.front,
                    back = draft.back,
                    tags = draft.tags,
                    signature = draft.signature(),
                )
            )
            syncTags(previous.id, draft.tags)
            val indices = draft.clozeIndices.ifEmpty { listOf(0) }
            dao.removeObsoleteCards(previous.id, indices)
            val current = dao.cards().filter { it.noteId == previous.id }.map { it.cloze }
            indices
                .filter { it !in current }
                .forEach { dao.addCard(Card(noteId = previous.id, cloze = it)) }
        }
    }

    suspend fun snapshot(): Backup =
        db.withTransaction {
            Backup(
                createdAt = System.currentTimeMillis(),
                decks = dao.decks().map { DeckData(it.id, it.name, it.parentId) },
                notes =
                    dao.notes().map {
                        NoteData(
                            it.id,
                            it.deckId,
                            it.title,
                            it.front,
                            it.back,
                            it.tags,
                            it.signature,
                            it.createdAt,
                        )
                    },
                cards =
                    dao.cards().map {
                        CardData(
                            it.id,
                            it.noteId,
                            it.cloze,
                            it.schedule,
                            it.buriedUntil,
                            it.revision,
                        )
                    },
                reviews =
                    dao.reviews().map {
                        ReviewData(
                            it.id,
                            it.cardId,
                            it.rating,
                            it.timestamp,
                            it.wasNew,
                            it.previous,
                            it.next,
                        )
                    },
                settings = dao.preference()!!.value,
                tags = dao.tags().map { TagData(it.id, it.name) },
                noteTags = dao.noteTags().map { NoteTagData(it.noteId, it.tagId) },
            )
        }

    suspend fun restore(backup: Backup): File {
        BackupCodec.validate(backup)
        return mutate {
            val safety = File(context.filesDir, "safety-${System.currentTimeMillis()}.zip")
            val temporary = File(context.filesDir, safety.name + ".tmp")
            try {
                temporary.outputStream().use { BackupCodec.write(snapshot(), it) }
                check(temporary.renameTo(safety)) { "无法保存恢复前安全备份" }
            } finally {
                if (temporary.exists()) temporary.delete()
            }
            dao.clearReviews()
            dao.clearCards()
            dao.clearNotes()
            dao.clearTags()
            val oldDecks = dao.decks()
            oldDecks
                .sortedByDescending { path(it.id, oldDecks).size }
                .forEach { dao.deleteDeck(it.id) }
            val pending = backup.decks.toMutableList()
            val inserted = mutableSetOf<Long>()
            while (pending.isNotEmpty()) {
                val next = pending.first { it.parentId == null || it.parentId in inserted }
                dao.addDeck(Deck(next.id, next.name, next.parentId))
                inserted += next.id
                pending.remove(next)
            }
            backup.notes.forEach {
                dao.addNote(
                    Note(
                        it.id,
                        it.deckId,
                        it.title,
                        it.front,
                        it.back,
                        it.tags,
                        it.signature,
                        it.createdAt,
                    )
                )
            }
            backup.tags.forEach { dao.addTag(Tag(it.id, it.name)) }
            backup.noteTags.forEach { dao.addNoteTag(NoteTag(it.noteId, it.tagId)) }
            backup.cards.forEach {
                dao.addCard(
                    Card(
                        it.id,
                        it.noteId,
                        it.cloze,
                        it.schedule,
                        it.schedule.due,
                        it.buriedUntil,
                        it.revision,
                    )
                )
            }
            backup.reviews.forEach {
                dao.addReview(
                    Review(
                        it.id,
                        it.cardId,
                        it.rating,
                        it.timestamp,
                        it.wasNew,
                        it.previous,
                        it.next,
                    )
                )
            }
            dao.savePreference(Preference(value = backup.settings))
            mirrorSettings(backup.settings)
            generation.value = generation.value + 1
            safety
        }
    }

    suspend fun deleteNote(id: Long) = mutate { dao.deleteNote(id) }
}
