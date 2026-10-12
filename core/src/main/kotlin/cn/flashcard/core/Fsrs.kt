package cn.flashcard.core

import kotlin.math.*
import kotlinx.serialization.Serializable

@Serializable
enum class CardState {
    LEARNING,
    REVIEW,
    RELEARNING,
}

@Serializable
data class Schedule(
    val state: CardState = CardState.LEARNING,
    val step: Int? = 0,
    val stability: Double? = null,
    val difficulty: Double? = null,
    val due: Long = 0,
    val lastReview: Long? = null,
)

enum class Rating(val value: Int) {
    AGAIN(1),
    HARD(2),
    GOOD(3),
    EASY(4),
}

/** FSRS-6 adapted from py-fsrs v6.3.2, Copyright (c) 2022 Open Spaced Repetition, MIT. */
class Fsrs(val retention: Double = .9) {
    init {
        require(retention in 0.7..0.97)
    }

    private val w =
        doubleArrayOf(
            .212,
            1.2931,
            2.3065,
            8.2956,
            6.4133,
            .8334,
            3.0194,
            .001,
            1.8722,
            .1666,
            .796,
            1.4835,
            .0614,
            .2629,
            1.6483,
            .6014,
            1.8729,
            .5425,
            .0912,
            .0658,
            .1542,
        )
    private val decay = -w[20]
    private val factor = .9.pow(1 / decay) - 1
    private val day = 86_400_000L

    fun retrievability(c: Schedule, now: Long): Double =
        if (c.lastReview == null || c.stability == null) 0.0
        else (1 + factor * max(0L, (now - c.lastReview) / day) / c.stability).pow(decay)

    private fun initialDifficulty(r: Rating) = w[4] - exp(w[5] * (r.value - 1)) + 1

    private fun nextDifficulty(d: Double, r: Rating) =
        (w[7] * initialDifficulty(Rating.EASY) +
                (1 - w[7]) * (d + (10 - d) * (-w[6] * (r.value - 3)) / 9))
            .coerceIn(1.0, 10.0)

    private fun stability(s: Double, d: Double, r: Rating, elapsed: Long?, recall: Double): Double {
        if (elapsed != null && elapsed < 1) {
            var increase = exp(w[17] * (r.value - 3 + w[18])) * s.pow(-w[19])
            if (r != Rating.AGAIN) increase = max(increase, 1.0)
            return max(.001, s * increase)
        }
        return max(
            .001,
            if (r == Rating.AGAIN)
                min(
                    w[11] * d.pow(-w[12]) * ((s + 1).pow(w[13]) - 1) * exp((1 - recall) * w[14]),
                    s / exp(w[17] * w[18]),
                )
            else
                s *
                    (1 +
                        exp(w[8]) *
                            (11 - d) *
                            s.pow(-w[9]) *
                            (exp((1 - recall) * w[10]) - 1) *
                            (if (r == Rating.HARD) w[15] else 1.0) *
                            (if (r == Rating.EASY) w[16] else 1.0)),
        )
    }

    private fun interval(s: Double) =
        Math.rint(s / factor * (retention.pow(1 / decay) - 1)).toLong().coerceIn(1, 36500) * day

    fun review(c: Schedule, r: Rating, now: Long): Schedule {
        require(c.lastReview == null || now >= c.lastReview) { "复习时间不能早于上次复习" }
        val s =
            if (c.stability == null || c.difficulty == null) w[r.value - 1]
            else
                stability(
                    c.stability,
                    c.difficulty,
                    r,
                    c.lastReview?.let { (now - it) / day },
                    retrievability(c, now),
                )
        val d =
            if (c.difficulty == null) initialDifficulty(r).coerceIn(1.0, 10.0)
            else nextDifficulty(c.difficulty, r)
        var state = c.state
        var step = c.step
        val delay: Long
        if (state == CardState.REVIEW) {
            if (r == Rating.AGAIN) {
                state = CardState.RELEARNING
                step = 0
                delay = 600000
            } else delay = interval(s)
        } else {
            val steps =
                if (state == CardState.LEARNING) longArrayOf(60000, 600000) else longArrayOf(600000)
            val i = step ?: 0
            when (r) {
                Rating.AGAIN -> {
                    step = 0
                    delay = steps[0]
                }
                Rating.HARD -> {
                    delay =
                        if (i == 0)
                            if (steps.size == 1) steps[0] * 3 / 2 else (steps[0] + steps[1]) / 2
                        else steps.getOrElse(i) { interval(s) }
                }
                Rating.GOOD ->
                    if (i + 1 >= steps.size) {
                        state = CardState.REVIEW
                        step = null
                        delay = interval(s)
                    } else {
                        step = i + 1
                        delay = steps[i + 1]
                    }
                Rating.EASY -> {
                    state = CardState.REVIEW
                    step = null
                    delay = interval(s)
                }
            }
        }
        return Schedule(state, step, s, d, now + delay, now)
    }
}
