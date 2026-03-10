package com.recengine.redis

import org.junit.jupiter.api.Test
import kotlin.math.exp
import kotlin.math.ln
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pure-math tests for the exponential decay formula used by SessionStore.
 *
 * decay(t) = exp(-λ × hoursElapsed),  λ = ln(2) / halfLifeHours
 *
 * No Redis connection required.
 */
class SessionStoreDecayTest {

    private fun decayFactor(hoursElapsed: Double, halfLifeHours: Double = 4.0): Double {
        val lambda = ln(2.0) / halfLifeHours
        return exp(-lambda * hoursElapsed)
    }

    // ── Half-life points ─────────────────────────────────────────────

    @Test
    fun `decay factor is 1_0 at t=0`() {
        assertEquals(1.0, decayFactor(0.0), 1e-9)
    }

    @Test
    fun `decay factor is 0_5 at one half-life`() {
        assertEquals(0.5, decayFactor(4.0, halfLifeHours = 4.0), 1e-9)
    }

    @Test
    fun `decay factor is 0_25 at two half-lives`() {
        assertEquals(0.25, decayFactor(8.0, halfLifeHours = 4.0), 1e-9)
    }

    @Test
    fun `decay factor is 0_125 at three half-lives`() {
        assertEquals(0.125, decayFactor(12.0, halfLifeHours = 4.0), 1e-9)
    }

    // ── Monotonicity ─────────────────────────────────────────────────

    @Test
    fun `decay factor strictly decreases over time`() {
        val times = listOf(0.0, 1.0, 2.0, 4.0, 8.0, 24.0, 48.0)
        val decays = times.map { decayFactor(it) }
        for (i in 0 until decays.size - 1) {
            assertTrue(decays[i] > decays[i + 1],
                "Decay at t=${times[i]} (${decays[i]}) should be > decay at t=${times[i+1]} (${decays[i+1]})")
        }
    }

    @Test
    fun `decay factor is always positive`() {
        listOf(0.0, 1.0, 4.0, 24.0, 168.0, 720.0).forEach { t ->
            assertTrue(decayFactor(t) > 0.0, "Decay must be positive at t=$t hours")
        }
    }

    @Test
    fun `decay factor is always at most 1_0`() {
        listOf(0.0, 0.001, 4.0, 24.0).forEach { t ->
            assertTrue(decayFactor(t) <= 1.0, "Decay must be <= 1.0 at t=$t hours")
        }
    }

    // ── Half-life parameter sensitivity ──────────────────────────────

    @Test
    fun `shorter half-life decays faster`() {
        val t = 4.0
        assertTrue(decayFactor(t, halfLifeHours = 2.0) < decayFactor(t, halfLifeHours = 4.0),
            "Half-life of 2h should decay faster than half-life of 4h at t=$t hours")
    }

    @Test
    fun `longer half-life decays slower`() {
        val t = 4.0
        assertTrue(decayFactor(t, halfLifeHours = 8.0) > decayFactor(t, halfLifeHours = 4.0),
            "Half-life of 8h should decay slower than half-life of 4h at t=$t hours")
    }

    // ── Moving average formula ────────────────────────────────────────

    @Test
    fun `moving average with alpha=0_3 gives correct blend`() {
        val alpha      = 0.3
        val oldWeight  = 0.6
        val eventWeight = 1.0
        val expected   = alpha * eventWeight + (1.0 - alpha) * oldWeight
        assertEquals(0.72, expected, 1e-9)
    }

    @Test
    fun `moving average with no prior weight equals alpha times event weight`() {
        val alpha       = 0.3
        val oldWeight   = 0.0
        val eventWeight = 1.0
        val result      = alpha * eventWeight + (1.0 - alpha) * oldWeight
        assertEquals(0.3, result, 1e-9)
    }

    @Test
    fun `moving average with alpha=1_0 always returns event weight`() {
        val alpha = 1.0
        listOf(0.0, 0.5, 0.9).forEach { old ->
            val result = alpha * 0.7 + (1.0 - alpha) * old
            assertEquals(0.7, result, 1e-9, "With alpha=1.0, result should be eventWeight regardless of old=$old")
        }
    }
}
