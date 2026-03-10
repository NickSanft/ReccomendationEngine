package com.recengine.pipeline

import com.recengine.kafka.KafkaConsumerService
import com.recengine.ml.FeatureVectorBuilder
import com.recengine.ml.OnlineFM
import com.recengine.model.ClickEvent
import com.recengine.model.ImpressionEvent
import com.recengine.model.ItemFeatures
import com.recengine.model.PurchaseEvent
import com.recengine.model.ViewEvent
import com.recengine.redis.FeatureStore
import com.recengine.redis.SessionStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.just
import io.mockk.Runs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class EventProcessorTest {

    private val consumer     = mockk<KafkaConsumerService>()
    private val sessionStore = mockk<SessionStore>()
    private val featureStore = mockk<FeatureStore>()
    private val fm           = mockk<OnlineFM>(relaxed = true)  // relaxed: learn() returns Unit
    private val builder      = FeatureVectorBuilder(1_000)

    private lateinit var processor: EventProcessor

    private val itemFeatures = ItemFeatures(
        itemId        = "item-1",
        categoryIds   = listOf("electronics"),
        tags          = listOf("sale"),
        publishedAtMs = System.currentTimeMillis(),
        popularity    = 0.8
    )

    @BeforeEach
    fun setUp() {
        processor = EventProcessor(
            consumer           = consumer,
            sessionStore       = sessionStore,
            featureStore       = featureStore,
            fm                 = fm,
            featureBuilder     = builder,
            kafkaTopicEvents   = "test-events",
            kafkaTopicFeedback = "test-feedback",
        )
    }

    @AfterEach
    fun tearDown() {
        processor.stop()
    }

    // ── handleEvent — direct unit tests ──────────────────────────────

    @Test
    fun `click event records session interaction with weight 0_7`() = runBlocking {
        coEvery { sessionStore.recordInteraction("u1", "item-1", 0.7) } just Runs
        coEvery { featureStore.incrementItemStat("item-1", "clicks") } just Runs
        coEvery { featureStore.getItemFeatures("item-1") } returns null

        processor.handleEvent(ClickEvent("u1", System.currentTimeMillis(), "item-1", "s1"))

        coVerify(exactly = 1) { sessionStore.recordInteraction("u1", "item-1", 0.7) }
    }

    @Test
    fun `click event triggers FM learning when item features are available`() = runBlocking {
        coEvery { sessionStore.recordInteraction(any(), any(), any()) } just Runs
        coEvery { featureStore.incrementItemStat(any(), any()) } just Runs
        coEvery { featureStore.getItemFeatures("item-1") } returns itemFeatures

        processor.handleEvent(ClickEvent("u1", System.currentTimeMillis(), "item-1", "s1"))

        coVerify(exactly = 1) { fm.learn(any(), 1.0) }
    }

    @Test
    fun `click event skips FM learning when item features are missing`() = runBlocking {
        coEvery { sessionStore.recordInteraction(any(), any(), any()) } just Runs
        coEvery { featureStore.incrementItemStat(any(), any()) } just Runs
        coEvery { featureStore.getItemFeatures("item-1") } returns null

        processor.handleEvent(ClickEvent("u1", System.currentTimeMillis(), "item-1", "s1"))

        coVerify(exactly = 0) { fm.learn(any(), any()) }
    }

    @Test
    fun `view event weight is dwell-time scaled`() = runBlocking {
        coEvery { sessionStore.recordInteraction(any(), any(), any()) } just Runs
        coEvery { featureStore.incrementItemStat(any(), any()) } just Runs

        // 15s dwell → 15/30 = 0.5
        processor.handleEvent(ViewEvent("u1", System.currentTimeMillis(), "item-1", "s1", dwellSeconds = 15.0))

        coVerify(exactly = 1) { sessionStore.recordInteraction("u1", "item-1", 0.5) }
    }

    @Test
    fun `view event weight is capped at 1_0`() = runBlocking {
        coEvery { sessionStore.recordInteraction(any(), any(), any()) } just Runs
        coEvery { featureStore.incrementItemStat(any(), any()) } just Runs

        // 120s dwell → 120/30 = 4.0, clamped to 1.0
        processor.handleEvent(ViewEvent("u1", System.currentTimeMillis(), "item-1", "s1", dwellSeconds = 120.0))

        coVerify(exactly = 1) { sessionStore.recordInteraction("u1", "item-1", 1.0) }
    }

    @Test
    fun `purchase event records session interaction with weight 1_0`() = runBlocking {
        coEvery { sessionStore.recordInteraction(any(), any(), any()) } just Runs
        coEvery { featureStore.incrementItemStat(any(), any()) } just Runs
        coEvery { featureStore.getItemFeatures("item-1") } returns null

        processor.handleEvent(PurchaseEvent("u1", System.currentTimeMillis(), "item-1", "s1", revenue = 29.99))

        coVerify(exactly = 1) { sessionStore.recordInteraction("u1", "item-1", 1.0) }
    }

    @Test
    fun `purchase event triggers FM learning`() = runBlocking {
        coEvery { sessionStore.recordInteraction(any(), any(), any()) } just Runs
        coEvery { featureStore.incrementItemStat(any(), any()) } just Runs
        coEvery { featureStore.getItemFeatures("item-1") } returns itemFeatures

        processor.handleEvent(PurchaseEvent("u1", System.currentTimeMillis(), "item-1", "s1", revenue = 29.99))

        coVerify(exactly = 1) { fm.learn(any(), 1.0) }
    }

    @Test
    fun `impression event does not record session interaction`() = runBlocking {
        coEvery { featureStore.incrementPopularity(any(), any()) } just Runs

        processor.handleEvent(
            ImpressionEvent("u1", System.currentTimeMillis(), listOf("i1", "i2"), "s1", "control")
        )

        coVerify(exactly = 0) { sessionStore.recordInteraction(any(), any(), any()) }
        coVerify(exactly = 0) { fm.learn(any(), any()) }
    }

    @Test
    fun `exception in handleEvent is caught and does not propagate`() = runBlocking {
        coEvery { sessionStore.recordInteraction(any(), any(), any()) } throws RuntimeException("Redis down")
        coEvery { featureStore.incrementItemStat(any(), any()) } just Runs
        coEvery { featureStore.getItemFeatures(any()) } returns null

        // Should not throw
        processor.handleEvent(ClickEvent("u1", System.currentTimeMillis(), "item-1", "s1"))
    }

    // ── start/stop integration ────────────────────────────────────────

    @Test
    fun `start processes events from Kafka flow then stops cleanly`() = runBlocking {
        val clickEvent = ClickEvent("u2", System.currentTimeMillis(), "item-2", "s2")

        every { consumer.eventFlow(any(), any(), any()) } returns flowOf(clickEvent)

        val processed = CompletableDeferred<Unit>()
        coEvery { sessionStore.recordInteraction("u2", "item-2", 0.7) } answers {
            processed.complete(Unit)
        }
        coEvery { featureStore.incrementItemStat("item-2", "clicks") } just Runs
        coEvery { featureStore.getItemFeatures("item-2") } returns null

        processor.start()
        withTimeout(5_000) { processed.await() }

        coVerify { sessionStore.recordInteraction("u2", "item-2", 0.7) }
    }

    // ── view weight formula ───────────────────────────────────────────

    @Test
    fun `dwell weight formula is correct at key dwell times`() {
        data class Case(val dwellSeconds: Double, val expectedWeight: Double)
        listOf(
            Case(0.0,   0.0),
            Case(15.0,  0.5),
            Case(30.0,  1.0),
            Case(60.0,  1.0),   // clamped
            Case(300.0, 1.0),   // clamped
        ).forEach { (dwell, expected) ->
            val weight = (dwell / 30.0).coerceIn(0.0, 1.0)
            assertEquals(expected, weight, 1e-9, "Dwell ${dwell}s should give weight $expected")
        }
    }
}
