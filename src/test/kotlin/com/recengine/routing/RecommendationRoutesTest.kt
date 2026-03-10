package com.recengine.routing

import com.recengine.config.AbConfig
import com.recengine.config.FmConfig
import com.recengine.config.ScoringWeights
import com.recengine.ml.FeatureVectorBuilder
import com.recengine.ml.OnlineFM
import com.recengine.ml.ScoringEngine
import com.recengine.model.RecommendationRequest
import com.recengine.model.RecommendationResponse
import com.recengine.redis.FeatureStore
import com.recengine.redis.SessionStore
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.testing.*
import io.lettuce.core.api.coroutines.RedisCoroutinesCommands
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RecommendationRoutesTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; classDiscriminator = "type" }

    private val redis         = mockk<RedisCoroutinesCommands<String, String>>()
    private val featureStore  = mockk<FeatureStore>()
    private val sessionStore  = mockk<SessionStore>()
    private val fm            = OnlineFM(FmConfig(numFactors = 4, learningRate = 0.01, regularization = 0.0, numFeatures = 1_000))
    private val builder       = FeatureVectorBuilder(1_000)
    private val abConfig      = AbConfig(salt = "test-salt", variants = listOf("control", "fm_v1", "bandit_ucb"))
    private val scoringEngine = ScoringEngine(fm, sessionStore, featureStore, ScoringWeights(0.4, 0.35, 0.15, 0.1))

    private fun withApp(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        install(ContentNegotiation) { json(json) }
        application {
            val abAssigner = AbAssigner(redis, abConfig)
            recommendationRoutes(scoringEngine, featureStore, abAssigner, builder, fm)
        }
        block()
    }

    private suspend fun ApplicationTestBuilder.getRecommendations(userId: String, extra: String = ""): RecommendationResponse {
        val body = client.get("/api/v1/recommendations/$userId$extra").bodyAsText()
        return json.decodeFromString(body)
    }

    // ── GET /api/v1/recommendations/{userId} ─────────────────────────

    @Test
    fun `GET recommendations returns 200 with empty list when no popular items`() = withApp {
        coEvery { redis.get(any()) } returns null
        coEvery { redis.setex(any(), any(), any()) } returns "OK"
        coEvery { featureStore.getPopularItems(any(), any()) } returns emptyList()
        coEvery { sessionStore.getDecayedVector(any()) } returns emptyMap()

        val resp = client.get("/api/v1/recommendations/user-001")
        assertEquals(HttpStatusCode.OK, resp.status)

        val body = json.decodeFromString<RecommendationResponse>(resp.bodyAsText())
        assertEquals("user-001", body.userId)
        assertTrue(body.items.isEmpty())
        assertTrue(body.variantId in abConfig.variants)
    }

    @Test
    fun `GET recommendations returns session_id from query param`() = withApp {
        coEvery { redis.get(any()) } returns null
        coEvery { redis.setex(any(), any(), any()) } returns "OK"
        coEvery { featureStore.getPopularItems(any(), any()) } returns emptyList()
        coEvery { sessionStore.getDecayedVector(any()) } returns emptyMap()

        val body = getRecommendations("user-001", "?session_id=sess-xyz")
        assertEquals("sess-xyz", body.sessionId)
    }

    @Test
    fun `GET recommendations variant is stable for same user`() = withApp {
        val fixedVariant = AbAssigner(redis, abConfig).assignVariant("user-stable")
        coEvery { redis.get("ab:user-stable:variant") } returns fixedVariant
        coEvery { featureStore.getPopularItems(any(), any()) } returns emptyList()
        coEvery { sessionStore.getDecayedVector(any()) } returns emptyMap()

        val r1 = getRecommendations("user-stable")
        val r2 = getRecommendations("user-stable")
        assertEquals(r1.variantId, r2.variantId)
    }

    @Test
    fun `GET recommendations each response has a unique recommendationId`() = withApp {
        coEvery { redis.get(any()) } returns null
        coEvery { redis.setex(any(), any(), any()) } returns "OK"
        coEvery { featureStore.getPopularItems(any(), any()) } returns emptyList()
        coEvery { sessionStore.getDecayedVector(any()) } returns emptyMap()

        val r1 = getRecommendations("user-001")
        val r2 = getRecommendations("user-001")
        assertFalse(r1.recommendationId == r2.recommendationId, "Each response should have a unique UUID")
    }

    // ── POST /api/v1/recommendations ─────────────────────────────────

    @Test
    fun `POST recommendations excludes specified itemIds`() = withApp {
        coEvery { redis.get(any()) } returns null
        coEvery { redis.setex(any(), any(), any()) } returns "OK"
        coEvery { featureStore.getPopularItems(any(), any()) } returns listOf("item-99", "item-01")
        coEvery { featureStore.getItemFeatures(any()) } returns null
        coEvery { sessionStore.getDecayedVector(any()) } returns emptyMap()

        val resp = client.post("/api/v1/recommendations") {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(
                RecommendationRequest.serializer(),
                RecommendationRequest(userId = "user-001", sessionId = "s1", excludeItemIds = listOf("item-99"))
            ))
        }
        assertEquals(HttpStatusCode.OK, resp.status)
        val body = json.decodeFromString<RecommendationResponse>(resp.bodyAsText())
        assertTrue(body.items.none { it.itemId == "item-99" }, "Excluded item must not appear in results")
    }

    @Test
    fun `POST recommendations returns correct userId`() = withApp {
        coEvery { redis.get(any()) } returns null
        coEvery { redis.setex(any(), any(), any()) } returns "OK"
        coEvery { featureStore.getPopularItems(any(), any()) } returns emptyList()
        coEvery { sessionStore.getDecayedVector(any()) } returns emptyMap()

        val resp = client.post("/api/v1/recommendations") {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(
                RecommendationRequest.serializer(),
                RecommendationRequest(userId = "user-007", sessionId = "s-007")
            ))
        }
        val body = json.decodeFromString<RecommendationResponse>(resp.bodyAsText())
        assertEquals("user-007", body.userId)
    }

    // ── GET /api/v1/trending ─────────────────────────────────────────

    @Test
    fun `GET trending returns 200`() = withApp {
        coEvery { featureStore.getPopularItems("hourly", 50L) } returns listOf("item-1", "item-2")
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/trending").status)
    }

    @Test
    fun `GET trending passes window query param`() = withApp {
        coEvery { featureStore.getPopularItems("daily", 50L) } returns listOf("item-A")
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/trending?window=daily").status)
    }

    // ── GET /api/v1/admin/model/metrics ──────────────────────────────

    @Test
    fun `GET model metrics returns 200`() = withApp {
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/admin/model/metrics").status)
    }
}
