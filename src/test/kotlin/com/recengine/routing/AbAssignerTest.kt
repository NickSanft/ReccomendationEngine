package com.recengine.routing

import com.recengine.config.AbConfig
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.lettuce.core.api.coroutines.RedisCoroutinesCommands
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AbAssignerTest {

    private val redis   = mockk<RedisCoroutinesCommands<String, String>>()
    private val config  = AbConfig(salt = "test-salt", variants = listOf("control", "fm_v1", "bandit_ucb"))
    private val assigner = AbAssigner(redis, config)

    // ── Determinism ───────────────────────────────────────────────────

    @Test
    fun `same user always receives same variant`() {
        val v1 = assigner.assignVariant("user-001")
        val v2 = assigner.assignVariant("user-001")
        assertEquals(v1, v2)
    }

    @Test
    fun `variant is always a known variant`() {
        repeat(50) { i ->
            val variant = assigner.assignVariant("user-${i.toString().padStart(3, '0')}")
            assertTrue(variant in config.variants, "Unexpected variant: $variant")
        }
    }

    @Test
    fun `different users can receive different variants`() {
        val variants = (1..100).map { assigner.assignVariant("user-$it") }.toSet()
        assertTrue(variants.size > 1, "Expected multiple distinct variants across 100 users")
    }

    @Test
    fun `assignment uses salt so different salts give different results`() {
        val assigner2 = AbAssigner(redis, AbConfig(salt = "other-salt", variants = config.variants))
        // With 100 users there is near-zero probability all assignments match under different salts
        val same = (1..100).count { i ->
            assigner.assignVariant("user-$i") == assigner2.assignVariant("user-$i")
        }
        assertTrue(same < 100, "Expected at least one difference with a different salt")
    }

    // ── Distribution ─────────────────────────────────────────────────

    @Test
    fun `variant distribution is roughly uniform across many users`() {
        val counts = mutableMapOf<String, Int>()
        repeat(3000) { i ->
            val v = assigner.assignVariant("user-$i")
            counts[v] = (counts[v] ?: 0) + 1
        }
        // Each variant should receive 25–42% of 3000 users (expected ~33.3% each)
        val expected = 3000.0 / config.variants.size
        counts.values.forEach { count ->
            assertTrue(count > expected * 0.75 && count < expected * 1.25,
                "Variant count $count is outside ±25% of expected $expected — distribution may be skewed")
        }
    }

    // ── Redis caching ─────────────────────────────────────────────────

    @Test
    fun `getOrAssign returns cached value from Redis without recomputing`() = runBlocking {
        coEvery { redis.get("ab:user-001:variant") } returns "fm_v1"

        val result = assigner.getOrAssign("user-001")

        assertEquals("fm_v1", result)
        coVerify(exactly = 0) { redis.setex(any(), any(), any()) }
    }

    @Test
    fun `getOrAssign assigns and caches when Redis has no entry`() = runBlocking {
        coEvery { redis.get("ab:user-001:variant") } returns null
        coEvery { redis.setex(any(), any(), any()) } returns "OK"

        val result = assigner.getOrAssign("user-001")

        assertTrue(result in config.variants)
        coVerify(exactly = 1) { redis.setex("ab:user-001:variant", 7 * 24 * 3600L, result) }
    }

    @Test
    fun `getOrAssign cached value matches deterministic assignment`() = runBlocking {
        val computed = assigner.assignVariant("user-42")
        coEvery { redis.get("ab:user-42:variant") } returns null
        coEvery { redis.setex(any(), any(), any()) } returns "OK"

        val result = assigner.getOrAssign("user-42")

        assertEquals(computed, result)
    }
}
