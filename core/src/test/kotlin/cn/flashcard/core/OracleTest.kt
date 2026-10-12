package cn.flashcard.core

import kotlinx.serialization.Serializable
import org.junit.Assert.*
import org.junit.Test

@Serializable
private data class Oracle(
    val retention: Double,
    val now: Long,
    val rating: Int,
    val previous: Schedule,
    val next: Schedule,
    val retrievability: Double,
)

class OracleTest {
    @Test
    fun matchesAuthoritativePyFsrs632() {
        val resource = javaClass.getResourceAsStream("/fsrs-6.3.2-oracle.json")!!
        val rows =
            BackupCodec.json.decodeFromString<List<Oracle>>(resource.bufferedReader().readText())
        assertEquals(132, rows.size)
        rows.forEachIndexed { index, row ->
            val f = Fsrs(row.retention)
            val actual = f.review(row.previous, Rating.entries[row.rating - 1], row.now)
            assertEquals("state at $index", row.next.state, actual.state)
            assertEquals(row.next.step, actual.step)
            assertEquals(row.next.due, actual.due)
            assertEquals(row.next.lastReview, actual.lastReview)
            assertEquals("stability at $index", row.next.stability!!, actual.stability!!, 1e-10)
            assertEquals("difficulty at $index", row.next.difficulty!!, actual.difficulty!!, 1e-10)
            assertEquals(row.retrievability, f.retrievability(row.previous, row.now), 1e-12)
        }
    }
}
