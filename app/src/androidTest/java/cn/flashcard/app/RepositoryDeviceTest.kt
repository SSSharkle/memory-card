package cn.flashcard.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.flashcard.core.*
import java.time.ZoneId
import kotlin.system.measureTimeMillis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RepositoryDeviceTest {
    private lateinit var db: FlashDatabase
    private lateinit var repo: Repository

    @Before
    fun setup() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db =
            Room.inMemoryDatabaseBuilder(context, FlashDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        repo = Repository(context, db)
        repo.initialize()
        repo.dao.notes().forEach { repo.dao.deleteNote(it.id) }
    }

    @After
    fun close() {
        db.close()
    }

    private suspend fun importCloze() {
        repo.importNotes(
            listOf(NoteDraft("p", "408", "操作系统", listOf("进程"), "{{c1::进程}} {{c2::线程}}", "解释")),
            1700000000000,
        )
    }

    @Test
    fun atomicRatingsSiblingAndDailyLimit() = runBlocking {
        importCloze()
        val before = repo.dao.cards()
        val now = 1700000000000L
        assertTrue(repo.rate(before[0].id, 0, Rating.GOOD, now))
        assertFalse(repo.rate(before[0].id, 0, Rating.EASY, now))
        assertEquals(1, repo.dao.reviews().size)
        val after = repo.dao.cards()
        assertEquals(1, after.first { it.id == before[0].id }.revision)
        assertEquals(repo.dayBounds(now).second, after.first { it.id == before[1].id }.buriedUntil)
        assertTrue(repo.queue(after, repo.dao.reviews(), Settings(dailyNew = 1), now).isEmpty())
        val tomorrow = repo.dayBounds(now).second
        assertTrue(
            repo.queue(after, repo.dao.reviews(), Settings(), tomorrow).any {
                it.id == before[1].id
            }
        )
        assertEquals(after, repo.dao.cards())
    }

    @Test
    fun restoreRoundtripAndInvalidLeavesData() = runBlocking {
        importCloze()
        val original = repo.snapshot()
        val safety = repo.restore(original)
        assertTrue(safety.exists())
        val restored = repo.snapshot()
        assertEquals(original.notes, restored.notes)
        assertEquals(original.cards, restored.cards)
        assertEquals(original.settings, restored.settings)
        try {
            repo.restore(original.copy(cards = emptyList()))
            fail("invalid backup accepted")
        } catch (_: IllegalArgumentException) {}
        assertEquals(original.notes, repo.snapshot().notes)
    }

    @Test
    fun changedImportPreservesOldCardAndEditPreservesIndices() = runBlocking {
        importCloze()
        val now = 1700000000000L
        val c = repo.dao.cards().first()
        repo.rate(c.id, c.revision, Rating.EASY, now)
        val old = repo.dao.card(c.id)!!
        val n = repo.dao.notes().first()
        repo.saveNote(n, n.deckId, n.title, n.front + " {{c3::新增}}", n.back, n.tags, now)
        assertEquals(old, repo.dao.card(c.id))
        assertEquals(3, repo.dao.cards().size)
        assertEquals(
            1,
            repo.importNotes(
                listOf(NoteDraft("p", "408", "操作系统", listOf("进程"), "{{c1::改过}} {{c2::线程}}", "解释")),
                now,
            ),
        )
        assertEquals(old, repo.dao.card(c.id))
    }

    @Test
    fun localDayUsesTimezoneMidnight() {
        val now = java.time.Instant.parse("2026-10-11T17:00:00Z").toEpochMilli()
        val hongkong = repo.dayBounds(now, ZoneId.of("Asia/Hong_Kong"))
        assertEquals(java.time.Instant.parse("2026-10-11T16:00:00Z").toEpochMilli(), hongkong.first)
        assertEquals(86400000L, hongkong.second - hongkong.first)
    }

    @Test
    fun restoreRejectsStaleCapturedRating() = runBlocking {
        importCloze()
        val old = repo.dao.cards().first()
        val generation = repo.generation.value
        val backup = repo.snapshot()
        repo.restore(backup)
        assertFalse(repo.rate(old.id, old.revision, Rating.EASY, 1700000000000, generation))
        assertTrue(repo.dao.reviews().isEmpty())
        assertTrue(
            repo.rate(old.id, old.revision, Rating.GOOD, 1700000000000, repo.generation.value)
        )
    }

    @Test
    fun parallelDoubleTapOneAtomicLog() = runBlocking {
        importCloze()
        val card = repo.dao.cards().first()
        val generation = repo.generation.value
        val taps =
            (1..8).map {
                async(Dispatchers.Default) {
                    repo.rate(card.id, card.revision, Rating.GOOD, 1700000000000, generation)
                }
            }
        assertEquals(1, taps.map { it.await() }.count { it })
        assertEquals(1, repo.dao.reviews().size)
    }

    @Test
    fun persistentRestartRecreatesIdenticalQueue() =
        runBlocking<Unit> {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val name = "restart-${System.nanoTime()}.db"
            var persistent =
                Room.databaseBuilder(context, FlashDatabase::class.java, name)
                    .allowMainThreadQueries()
                    .build()
            var repository = Repository(context, persistent)
            repository.initialize()
            val now = System.currentTimeMillis()
            val card =
                repository
                    .queue(repository.dao.cards(), repository.dao.reviews(), Settings(), now)
                    .first()
            repository.rate(card.id, card.revision, Rating.GOOD, now)
            val expected =
                repository.queue(repository.dao.cards(), repository.dao.reviews(), Settings(), now)
            val logs = repository.dao.reviews()
            persistent.close()
            persistent =
                Room.databaseBuilder(context, FlashDatabase::class.java, name)
                    .allowMainThreadQueries()
                    .build()
            repository = Repository(context, persistent)
            repository.initialize()
            assertEquals(
                expected,
                repository.queue(repository.dao.cards(), repository.dao.reviews(), Settings(), now),
            )
            assertEquals(logs, repository.dao.reviews())
            persistent.close()
            context.deleteDatabase(name)
        }

    @Test
    fun queueAndSearchTenThousandNotes() =
        runBlocking<Unit> {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val name = "performance-${System.nanoTime()}.db"
            val persistent = Room.databaseBuilder(context, FlashDatabase::class.java, name).build()
            try {
                val repository = Repository(context, persistent)
                repository.initialize()
                repository.dao.notes().forEach { repository.dao.deleteNote(it.id) }
                val drafts =
                    (1..10000).map {
                        NoteDraft(
                            "笔记$it",
                            "408",
                            "操作系统/万条测试",
                            listOf("万条", "分组${it % 10}"),
                            "问题$it",
                            "答案$it",
                        )
                    }
                val now = 1700000000000L
                val importMs = measureTimeMillis {
                    assertEquals(10000, repository.importNotes(drafts, now))
                }
                val dedupMs = measureTimeMillis {
                    assertEquals(0, repository.importNotes(drafts, now))
                }
                var notes = emptyList<Note>()
                var cards = emptyList<Card>()
                val queryMs = measureTimeMillis {
                    notes = repository.dao.notes()
                    cards = repository.dao.cards()
                }
                assertEquals(10000, notes.size)
                assertEquals(10000, cards.size)
                val bulkTag = repository.dao.tags().first { it.name == "万条" }.id
                assertEquals(10000, repository.dao.noteTags().count { it.tagId == bulkTag })
                val deckIds = repository.dao.decks().map { it.id }
                val searchMs = measureTimeMillis {
                    assertEquals(10000, repository.dao.notesCount(deckIds, "", ""))
                    val first = repository.dao.notesPage(deckIds, "", "", 50, 0)
                    val second = repository.dao.notesPage(deckIds, "", "", 50, 50)
                    assertEquals(50, first.size)
                    assertEquals(50, second.size)
                    assertTrue(
                        first.map { it.id }.intersect(second.map { it.id }.toSet()).isEmpty()
                    )
                    assertEquals("笔记10000", first.first().title)
                    assertEquals("笔记9950", second.first().title)
                    assertEquals(19, repository.dao.notesCount(deckIds, "999", ""))
                    assertEquals(19, repository.dao.notesPage(deckIds, "999", "", 50, 0).size)
                    assertEquals(1000, repository.dao.notesCount(deckIds, "", "分组0"))
                    assertEquals(10000, repository.dao.notesCount(deckIds, "万条", ""))
                    assertEquals(0, repository.dao.notesCount(deckIds, "%", ""))
                    assertEquals(20, repository.queue(cards, emptyList(), Settings(), now).size)
                }
                val validationMs = measureTimeMillis { BackupCodec.validate(repository.snapshot()) }
                println(
                    "10k real Room disk DB: import=${importMs}ms dedup=${dedupMs}ms query=${queryMs}ms search+queue=${searchMs}ms snapshot+validation=${validationMs}ms"
                )
                assertTrue("10k database import exceeded 120 seconds", importMs < 120000)
                assertTrue("10k database query exceeded 30 seconds", queryMs < 30000)
                assertTrue("10k search/queue exceeded 2 seconds", searchMs < 2000)
            } finally {
                persistent.close()
                context.deleteDatabase(name)
            }
        }

    @Test
    fun spacedDeckImportUsesCanonicalExistingPathAndBackupRemainsValid() =
        runBlocking<Unit> {
            val now = 1700000000000L
            val draft = NoteDraft("space", "408", " 操作系统 / 进程管理 ", emptyList(), "q", "a")
            repo.importNotes(listOf(draft), now)
            val decks = repo.dao.decks()
            assertEquals(1, decks.count { it.name == "操作系统" })
            assertFalse(decks.any { it.name != normalize(it.name) })
            BackupCodec.validate(repo.snapshot())
            val os = decks.first { it.name == "操作系统" }
            repo.renameDeck(os, "操作系统新版")
            assertEquals(0, repo.importNotes(listOf(draft.copy(deck = "操作系统新版/进程管理")), now))
            BackupCodec.validate(repo.snapshot())
        }
}
