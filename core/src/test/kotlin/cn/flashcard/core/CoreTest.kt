package cn.flashcard.core

import java.io.*
import kotlin.system.measureTimeMillis
import org.junit.Assert.*
import org.junit.Test

class CoreTest {
    @Test
    fun newCardRatingsAndLearning() {
        val f = Fsrs()
        val now = 1700000000000L
        val expected = longArrayOf(60000, 330000, 600000, 8 * 86400000L)
        Rating.entries.forEachIndexed { i, r ->
            val c = f.review(Schedule(), r, now)
            assertEquals(expected[i], c.due - now)
            assertEquals(doubleArrayOf(.212, 1.2931, 2.3065, 8.2956)[i], c.stability!!, 1e-12)
        }
        val good = f.review(Schedule(), Rating.GOOD, now)
        val graduated = f.review(good, Rating.GOOD, good.due)
        assertEquals(CardState.REVIEW, graduated.state)
        assertNull(graduated.step)
        val forgotten = f.review(graduated, Rating.AGAIN, graduated.due)
        assertEquals(CardState.RELEARNING, forgotten.state)
        assertEquals(600000L, forgotten.due - graduated.due)
        assertEquals(CardState.REVIEW, f.review(forgotten, Rating.GOOD, forgotten.due).state)
    }

    @Test
    fun parserCodeClozeErrorsAndDedup() {
        val md =
            """---
subject: "408"
deck: "操作系统/进程管理"
tags: ["进程"]
---
## 一
### 正面
{{c1::答案}} + {{c1::另一答案}} + {{c2::第三::提示}}
```markdown
## 不是笔记
### 正面
```
### 背面
补充
## 二
### 正面
坏的 {{c0::空}}
### 背面
答案
"""
        val parsed = MarkdownImport.parse(md)
        assertEquals(1, parsed.candidates.size)
        assertEquals(2, parsed.cardCount)
        assertEquals(1, parsed.issues.size)
        assertTrue(parsed.issues[0].line > 10)
        val draft = parsed.candidates.single().note
        assertEquals(
            "操作系统/进程管理",
            MarkdownImport.parse(md.replace("操作系统/进程管理", " 操作系统 / 进程管理 "))
                .candidates
                .single()
                .note
                .deck,
        )
        assertEquals(listOf(1, 2), draft.clozeIndices)
        assertEquals(1, MarkdownImport.parse(md, setOf(draft.signature())).duplicates)
        assertTrue(Cloze.render(draft.front, 1, false).contains("【…】 + 【…】"))
        assertTrue(Cloze.render(draft.front, 2, false).contains("【提示】"))
        assertTrue(
            MarkdownImport.parse("---\nsubject: 408\ndeck: a\n---\n## a").issues.isNotEmpty()
        )
        val invalidSubject =
            MarkdownImport.parse(md.replace("subject: \"408\"", "subject: \"408/非法科目\""))
        assertTrue(invalidSubject.candidates.isEmpty())
        assertTrue(invalidSubject.issues.single().message.contains("subject"))
    }

    @Test
    fun backupRoundtripValidationAndPerformance() {
        val b =
            Backup(
                createdAt = 1,
                decks = listOf(DeckData(1, "408")),
                notes =
                    listOf(
                        NoteData(
                            1,
                            1,
                            "a",
                            "q",
                            "a",
                            emptyList(),
                            NoteDraft("a", "408", "", emptyList(), "q", "a").signature(),
                            1,
                        )
                    ),
                cards = listOf(CardData(1, 1, 0, Schedule())),
                reviews = emptyList(),
                settings = Settings(),
            )
        val out = ByteArrayOutputStream()
        BackupCodec.write(b, out)
        assertTrue(BackupCodec.json.encodeToString(b).contains("\"version\": 1"))
        assertTrue(BackupCodec.json.encodeToString(b).contains("\"format\": \"exam-flashcards\""))
        assertEquals(b, BackupCodec.read(ByteArrayInputStream(out.toByteArray())))
        assertThrows(IllegalArgumentException::class.java) {
            BackupCodec.validate(b.copy(decks = listOf(DeckData(1, "a", 1))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            BackupCodec.validate(b.copy(cards = listOf(CardData(1, 99, 0, Schedule()))))
        }
        assertThrows(Exception::class.java) {
            BackupCodec.read(ByteArrayInputStream(byteArrayOf(1, 2, 3)))
        }
        val large =
            b.copy(
                notes =
                    (1L..10000L).map {
                        NoteData(
                            it,
                            1,
                            "n$it",
                            "q$it",
                            "a",
                            emptyList(),
                            NoteDraft("n$it", "408", "", emptyList(), "q$it", "a").signature(),
                            1,
                        )
                    },
                cards = (1L..10000L).map { CardData(it, it, 0, Schedule()) },
            )
        val elapsed = measureTimeMillis { BackupCodec.validate(large) }
        println("Validated 10000 notes/cards in ${elapsed}ms")
        assertTrue("10k validation unexpectedly slow: $elapsed", elapsed < 10000)
    }

    @Test
    fun tenThousandMarkdownNotesAndBackupRoundtrip() {
        val source = buildString {
            append("---\nsubject: \"408\"\ndeck: \"操作系统\"\ntags: []\n---\n")
            for (i in 1..10000) append("## 笔记 $i\n### 正面\n问题 $i\n### 背面\n答案 $i\n")
        }
        var preview: ImportPreview? = null
        val parseMs = measureTimeMillis { preview = MarkdownImport.parse(source) }
        assertEquals(10000, preview!!.candidates.size)
        assertTrue(preview!!.issues.isEmpty())
        assertEquals(10000, preview!!.cardCount)
        val drafts = preview!!.candidates.map { it.note }
        val backup =
            Backup(
                createdAt = 1,
                decks = listOf(DeckData(1, "408"), DeckData(2, "操作系统", 1)),
                notes =
                    drafts.mapIndexed { index, d ->
                        NoteData(index + 1L, 2, d.title, d.front, d.back, d.tags, d.signature(), 1)
                    },
                cards = drafts.indices.map { CardData(it + 1L, it + 1L, 0, Schedule()) },
                reviews = emptyList(),
                settings = Settings(),
            )
        val bytes = ByteArrayOutputStream()
        val zipMs = measureTimeMillis {
            BackupCodec.write(backup, bytes)
            assertEquals(backup, BackupCodec.read(ByteArrayInputStream(bytes.toByteArray())))
        }
        val duplicatesMs = measureTimeMillis {
            assertEquals(
                10000,
                MarkdownImport.parse(source, drafts.map { it.signature() }.toSet()).duplicates,
            )
        }
        println(
            "10k notes: parse=${parseMs}ms dedup=${duplicatesMs}ms ZIP roundtrip=${zipMs}ms bytes=${bytes.size()}"
        )
        assertTrue(parseMs < 30000 && zipMs < 30000 && duplicatesMs < 30000)
    }

    @Test
    fun backupRejectsBlankBasicAnswerSignatureMismatchAndTruncatedZip() {
        val draft = NoteDraft("a", "408", "", emptyList(), "q", "a")
        val b =
            Backup(
                createdAt = 1,
                decks = listOf(DeckData(1, "408")),
                notes = listOf(NoteData(1, 1, "a", "q", "a", emptyList(), draft.signature(), 1)),
                cards = listOf(CardData(1, 1, 0, Schedule())),
                reviews = emptyList(),
                settings = Settings(),
            )
        assertThrows(IllegalArgumentException::class.java) {
            BackupCodec.validate(b.copy(notes = listOf(b.notes[0].copy(back = ""))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            BackupCodec.validate(b.copy(notes = listOf(b.notes[0].copy(signature = "bad"))))
        }
        val out = ByteArrayOutputStream()
        BackupCodec.write(b, out)
        assertThrows(Exception::class.java) {
            BackupCodec.read(ByteArrayInputStream(out.toByteArray().dropLast(22).toByteArray()))
        }
        val missingVersion = ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(missingVersion).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("backup.json"))
            zip.write(
                BackupCodec.json
                    .encodeToString(b)
                    .replace("\"version\": 1,", "")
                    .toByteArray(Charsets.UTF_8)
            )
            zip.closeEntry()
        }
        assertThrows(Exception::class.java) {
            BackupCodec.read(ByteArrayInputStream(missingVersion.toByteArray()))
        }
    }
}
