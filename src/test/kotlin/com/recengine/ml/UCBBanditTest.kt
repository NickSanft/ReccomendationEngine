package com.recengine.ml

import com.recengine.config.BanditConfig
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UCBBanditTest {

    private val cfg = BanditConfig(epsilon = 0.1, ucbAlpha = 1.0, windowSize = 1_000)
    private fun bandit() = UCBBandit(cfg)

    @Test
    fun `select returns a valid arm from the list`() {
        val b    = bandit()
        val arms = listOf("A", "B", "C")
        val selected = b.select(arms)
        assertTrue(selected in arms)
    }

    @Test
    fun `unvisited arm is always selected first`() {
        val b = bandit()
        b.update("A", 1.0)
        b.update("A", 1.0)
        // B has never been pulled — must win via MAX_VALUE
        assertEquals("B", b.select(listOf("A", "B")))
    }

    @Test
    fun `select prefers higher reward arm after sufficient updates`() {
        val b = bandit()
        // Train A with high reward, B with low reward
        repeat(50) { b.update("A", 1.0) }
        repeat(50) { b.update("B", 0.0) }

        // After many pulls UCB uncertainty is small; A should dominate
        val counts = mutableMapOf("A" to 0, "B" to 0)
        repeat(100) { counts.merge(b.select(listOf("A", "B")), 1, Int::plus) }
        assertTrue((counts["A"] ?: 0) > (counts["B"] ?: 0),
            "A should be selected more often than B")
    }

    @Test
    fun `update increments totalPulls`() {
        val b = bandit()
        b.update("A", 1.0)
        b.update("B", 0.5)
        assertEquals(2L, b.totalPulls())
    }

    @Test
    fun `meanReward returns correct average`() {
        val b = bandit()
        b.update("A", 1.0)
        b.update("A", 0.0)
        assertEquals(0.5, b.meanReward("A"), 1e-9)
    }

    @Test
    fun `meanReward returns zero for unknown arm`() {
        assertEquals(0.0, bandit().meanReward("unknown"))
    }

    @Test
    fun `single arm list always returns that arm`() {
        val b = bandit()
        assertEquals("only", b.select(listOf("only")))
    }
}
