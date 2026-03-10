package com.recengine.routing

import com.recengine.config.AbConfig
import io.github.oshai.kotlinlogging.KotlinLogging
import io.lettuce.core.api.coroutines.RedisCoroutinesCommands
import java.nio.ByteBuffer
import java.security.MessageDigest

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
    internal fun assignVariant(userId: String): String {
        val sha256    = MessageDigest.getInstance("SHA-256")
        val hashBytes = sha256.digest("${config.salt}:$userId".toByteArray(Charsets.UTF_8))
        val hashInt   = ByteBuffer.wrap(hashBytes).int
        return config.variants[Math.floorMod(hashInt, config.variants.size)]
    }

    suspend fun getOrAssign(userId: String): String {
        redis.get("ab:$userId:variant")?.let { return it }
        val variant = assignVariant(userId)
        redis.setex("ab:$userId:variant", 7 * 24 * 3600L, variant)
        log.debug { "Assigned variant=$variant to user=$userId" }
        return variant
    }
}
