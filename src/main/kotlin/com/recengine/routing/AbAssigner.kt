package com.recengine.routing

import com.recengine.config.AbConfig
import io.github.oshai.kotlinlogging.KotlinLogging
import io.lettuce.core.api.coroutines.RedisCoroutinesCommands
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

private val log = KotlinLogging.logger {}

/**
 * Deterministic A/B variant assigner.
 *
 * Uses SHA-256 of `"$salt:$userId"` to map each user to a variant index,
 * then caches the assignment in Redis for 7 days so it is stable across
 * requests and server restarts.
 *
 * MessageDigest is not thread-safe; a new instance is created per call.
 */
class AbAssigner(
    private val redis: RedisCoroutinesCommands<String, String>,
    private val config: AbConfig
) {
    private val serveCounts = ConcurrentHashMap<String, AtomicLong>()

    /** Returns how many times each variant has been served since server start. */
    fun getServeCounts(): Map<String, Long> = serveCounts.mapValues { it.value.get() }

    internal fun assignVariant(userId: String): String {
        val sha256    = MessageDigest.getInstance("SHA-256")
        val hashBytes = sha256.digest("${config.salt}:$userId".toByteArray(Charsets.UTF_8))
        val hashInt   = ByteBuffer.wrap(hashBytes).int
        return config.variants[Math.floorMod(hashInt, config.variants.size)]
    }

    suspend fun getOrAssign(userId: String): String {
        val cached = redis.get("ab:$userId:variant")
        val variant = if (cached != null) cached else {
            val v = assignVariant(userId)
            redis.setex("ab:$userId:variant", 7 * 24 * 3600L, v)
            log.debug { "Assigned variant=$v to user=$userId" }
            v
        }
        serveCounts.getOrPut(variant) { AtomicLong(0) }.incrementAndGet()
        return variant
    }
}
