package com.recengine.ml

import com.recengine.config.BanditConfig
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EpsilonGreedyBanditTest {

    private val greedyCfg = BanditConfig(epsilon = 0.0, ucbAlpha = 1.0, windowSize = 1_000)
    private val exploreCfg = BanditConfig(epsilon = 1.0, ucbAlpha = 1.0, windowSize = 1_000)

    @Test
    fun `select returns a valid arm from the list`() {
        val b = EpsilonGreedyBandit(greedyCfg)
        val selected = b.select(listOf("X", "Y", "Z"))
        assertTrue(selected in listOf("X", "Y", "Z"))
    }

    @Test
    fun `with epsilon=0 always picks highest mean reward arm`() {
        val b = EpsilonGreedyBandit(greedyCfg)
        b.update("A", 0.2)
        b.update("B", 0.9)
        b.update("C", 0.5)
        // No randomness — greedy must always pick B
        repeat(20) {
            assertEquals("B", b.select(listOf("A", "B", "C")))
        }
    }

    @Test
    fun `with epsilon=1 explores all arms`() {
        val b    = EpsilonGreedyBandit(exploreCfg)
        val arms = listOf("A", "B", "C")
        b.update("A", 1.0)  // give A a head start

        val seen = mutableSetOf<String>()
        repeat(200) { seen.add(b.select(arms)) }
        assertEquals(arms.toSet(), seen, "Pure exploration should visit all arms")
    }

    @Test
    fun `update accumulates reward correctly`() {
        val b = EpsilonGreedyBandit(greedyCfg)
        b.update("A", 0.4)
        b.update("A", 0.6)
        assertEquals(0.5, b.meanReward("A"), 1e-9)
    }

    @Test
    fun `pullCount tracks updates per arm`() {
        val b = EpsilonGreedyBandit(greedyCfg)
        b.update("A", 1.0)
        b.update("A", 0.5)
        b.update("B", 0.2)
        assertEquals(2L, b.pullCount("A"))
        assertEquals(1L, b.pullCount("B"))
    }

    @Test
    fun `pullCount returns zero for unknown arm`() {
        assertEquals(0L, EpsilonGreedyBandit(greedyCfg).pullCount("ghost"))
    }

    @Test
    fun `unvisited arm wins before any updates with epsilon=0`() {
        val b = EpsilonGreedyBandit(greedyCfg)
        b.update("A", 1.0)
        // B never updated — should win via MAX_VALUE
        assertEquals("B", b.select(listOf("A", "B")))
    }
}
