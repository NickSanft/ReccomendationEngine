package com.recengine.routing

import com.recengine.kafka.KafkaProducerService
import com.recengine.model.ClickEvent
import com.recengine.model.ImpressionEvent
import com.recengine.model.ItemFeatures
import com.recengine.model.PurchaseEvent
import com.recengine.model.RecEngineEvent
import com.recengine.model.ViewEvent
import com.recengine.redis.FeatureStore
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlin.random.Random

private val log = KotlinLogging.logger {}

@Serializable
data class SeedResult(
    val sent: Int,
    val types: Map<String, Int>
)

fun Application.devRoutes(producer: KafkaProducerService, featureStore: FeatureStore? = null) {
    routing {

        /**
         * POST /dev/seed/items
         *
         * Seeds all 50 catalogue items into Redis:
         * - ItemFeatures stored at item:{id}:features
         * - Initial popularity scores in popularity:hourly so getPopularItems() returns results immediately
         */
        post("/dev/seed/items") {
            val store = featureStore
            if (store == null) {
                call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "Redis unavailable"))
                return@post
            }
            val now = System.currentTimeMillis()
            seedItemCatalogue.forEach { item ->
                store.setItemFeatures(item.copy(publishedAtMs = now - Random.nextLong(0, 30L * 86_400_000)))
                // Seed random initial popularity so items appear in trending immediately
                store.incrementPopularity(item.itemId, "hourly", Random.nextDouble(10.0, 200.0))
                store.incrementPopularity(item.itemId, "daily",  Random.nextDouble(50.0, 500.0))
            }
            log.info { "Seeded ${seedItemCatalogue.size} items into Redis" }
            call.respond(mapOf("seeded" to seedItemCatalogue.size))
        }

        /**
         * POST /dev/seed?count=20&type=random
         *
         * Produces [count] synthetic events to Kafka (default 20, max 500).
         * All sends run concurrently and the response is returned once every
         * event has been acknowledged by the broker.
         *
         * Optional [type] query param: click | view | purchase | impression | random (default)
         */
        post("/dev/seed") {
            val count    = call.request.queryParameters["count"]?.toIntOrNull()?.coerceIn(1, 500) ?: 20
            val typeFilter = call.request.queryParameters["type"]?.lowercase()

            val events = (1..count).map { randomEvent(typeFilter) }

            try {
                coroutineScope {
                    events.forEach { event -> launch { producer.sendEvent(event) } }
                }
                val typeCounts = events.groupingBy { it::class.simpleName ?: "Unknown" }.eachCount()
                log.info { "Seeded $count events to Kafka: $typeCounts" }
                call.respond(SeedResult(sent = count, types = typeCounts))
            } catch (e: Exception) {
                log.error(e) { "Seed failed" }
                call.respond(
                    HttpStatusCode.ServiceUnavailable,
                    SeedResult(sent = 0, types = mapOf("error" to 1))
                )
            }
        }
    }
}

// ── Synthetic data pools ──────────────────────────────────────────────────

private val userIds  = (1..20).map  { "user-${it.toString().padStart(3, '0')}" }
private val itemIds  = (1..50).map  { "item-${it.toString().padStart(3, '0')}" }
private val variants = listOf("control", "fm_v1", "bandit_ucb")
private val categories = listOf("electronics", "books", "clothing", "home", "sports")
private val tags       = listOf("sale", "new", "trending", "premium", "clearance")

private fun sessionId(userId: String) = "session-${userId.takeLast(3)}-${Random.nextInt(1, 5)}"

private fun randomEvent(typeFilter: String? = null): RecEngineEvent {
    val userId = userIds.random()
    val itemId = itemIds.random()
    val sessId = sessionId(userId)
    val now    = System.currentTimeMillis()

    return when (typeFilter) {
        "click"      -> click(userId, itemId, sessId, now)
        "view"       -> view(userId, itemId, sessId, now)
        "purchase"   -> purchase(userId, itemId, sessId, now)
        "impression" -> impression(userId, sessId, now)
        else -> when (Random.nextInt(10)) {
            // 40% view, 40% click, 10% purchase, 10% impression
            in 0..3 -> view(userId, itemId, sessId, now)
            in 4..7 -> click(userId, itemId, sessId, now)
            8       -> purchase(userId, itemId, sessId, now)
            else    -> impression(userId, sessId, now)
        }
    }
}

private fun click(userId: String, itemId: String, sessionId: String, now: Long) =
    ClickEvent(
        userId           = userId,
        timestampMs      = now,
        itemId           = itemId,
        sessionId        = sessionId,
        recommendationId = if (Random.nextBoolean()) "rec-${Random.nextInt(1000)}" else null
    )

private fun view(userId: String, itemId: String, sessionId: String, now: Long) =
    ViewEvent(
        userId         = userId,
        timestampMs    = now,
        itemId         = itemId,
        sessionId      = sessionId,
        dwellSeconds   = (Random.nextDouble(2.0, 90.0) * 10).toLong() / 10.0,
        sourcePosition = Random.nextInt(1, 21)
    )

private fun purchase(userId: String, itemId: String, sessionId: String, now: Long) =
    PurchaseEvent(
        userId      = userId,
        timestampMs = now,
        itemId      = itemId,
        sessionId   = sessionId,
        revenue     = (Random.nextDouble(4.99, 499.99) * 100).toLong() / 100.0,
        quantity    = Random.nextInt(1, 4)
    )

private fun impression(userId: String, sessionId: String, now: Long) =
    ImpressionEvent(
        userId      = userId,
        timestampMs = now,
        itemIds     = (1..Random.nextInt(3, 9)).map { itemIds.random() }.distinct(),
        sessionId   = sessionId,
        variantId   = variants.random()
    )

// ── Static item catalogue (mirrors shop.html JS catalogue) ───────────────────

private fun item(id: String, cat: String, vararg tags: String) = ItemFeatures(
    itemId       = id,
    categoryIds  = listOf(cat),
    tags         = tags.toList(),
    publishedAtMs = 0L,  // overwritten in the seed endpoint with a random offset
    popularity   = 0.0,
)

private val seedItemCatalogue: List<ItemFeatures> = listOf(
    // Electronics
    item("item-001","electronics","sale","trending"),
    item("item-002","electronics","popular"),
    item("item-003","electronics","sale"),
    item("item-004","electronics"),
    item("item-005","electronics","popular"),
    item("item-006","electronics","trending"),
    item("item-007","electronics","trending"),
    item("item-008","electronics","sale"),
    item("item-009","electronics"),
    item("item-010","electronics","popular"),
    // Books
    item("item-011","books","trending"),
    item("item-012","books","popular"),
    item("item-013","books"),
    item("item-014","books","trending"),
    item("item-015","books","sale"),
    item("item-016","books","trending","sale"),
    item("item-017","books","popular"),
    item("item-018","books"),
    item("item-019","books"),
    item("item-020","books","trending"),
    // Sports
    item("item-021","sports","trending"),
    item("item-022","sports","popular"),
    item("item-023","sports","sale"),
    item("item-024","sports"),
    item("item-025","sports","sale"),
    item("item-026","sports"),
    item("item-027","sports","popular"),
    item("item-028","sports"),
    item("item-029","sports","trending"),
    item("item-030","sports","sale"),
    // Home
    item("item-031","home","popular"),
    item("item-032","home","trending"),
    item("item-033","home","sale"),
    item("item-034","home"),
    item("item-035","home"),
    item("item-036","home","trending","sale"),
    item("item-037","home"),
    item("item-038","home","popular"),
    item("item-039","home"),
    item("item-040","home","trending"),
    // Clothing
    item("item-041","clothing","sale"),
    item("item-042","clothing","popular"),
    item("item-043","clothing"),
    item("item-044","clothing","sale"),
    item("item-045","clothing","trending"),
    // Toys
    item("item-046","toys","popular"),
    item("item-047","toys"),
    item("item-048","toys","trending"),
    item("item-049","toys"),
    item("item-050","toys","sale","popular"),
)
