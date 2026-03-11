package com.recengine

import com.recengine.config.AppConfig
import com.recengine.dashboard.EventBroadcaster
import com.recengine.dashboard.dashboardRoutes
import com.recengine.kafka.KafkaConsumerService
import com.recengine.kafka.KafkaProducerService
import com.recengine.kafka.TopicAdmin
import com.recengine.metrics.MetricsRegistry
import com.recengine.routing.AbAssigner
import com.recengine.routing.devRoutes
import com.recengine.routing.feedbackRoutes
import com.recengine.routing.recommendationRoutes
import com.recengine.routing.statsRoutes
import com.recengine.ml.FeatureVectorBuilder
import com.recengine.ml.OnlineFM
import com.recengine.ml.ScoringEngine
import com.recengine.pipeline.EventProcessor
import com.recengine.pipeline.SessionDecayJob
import com.recengine.redis.FeatureStore
import com.recengine.redis.RedisClientFactory
import com.recengine.redis.SessionStore
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.metrics.micrometer.MicrometerMetrics
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sse.SSE
import kotlinx.serialization.json.Json

private val logger = KotlinLogging.logger {}

fun main() {
    embeddedServer(Netty, port = 8080, host = "0.0.0.0", module = Application::module)
        .start(wait = true)
}

fun Application.module() {
    val startTimeMs = System.currentTimeMillis()
    val config = AppConfig.load()
    val json   = Json { ignoreUnknownKeys = true; encodeDefaults = true; classDiscriminator = "type" }

    // ── Kafka topics ────────────────────────────────────────────────
    try {
        TopicAdmin.createTopics(config.kafka)
    } catch (e: Exception) {
        logger.warn(e) { "Could not create Kafka topics on startup — Kafka may not be available yet" }
    }

    // ── Ktor plugins ────────────────────────────────────────────────
    install(MicrometerMetrics) {
        registry = MetricsRegistry.prometheus
    }
    install(SSE)
    install(ContentNegotiation) { json(json) }
    install(StatusPages) {
        exception<IllegalArgumentException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to cause.message))
        }
        exception<Throwable> { call, cause ->
            logger.error(cause) { "Unhandled exception" }
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Internal server error"))
        }
    }

    // ── Persistent producer (used by health check + dev seed endpoint) ─
    val producer = KafkaProducerService(config.kafka, json)

    // ── Health + metrics routes ─────────────────────────────────────
    routing {
        get("/health") {
            call.respond(mapOf(
                "status"   to "ok",
                "version"  to "0.1.0",
                "uptimeMs" to (System.currentTimeMillis() - startTimeMs)
            ))
        }
        get("/health/kafka") {
            try {
                call.respond(mapOf("kafka" to "ok"))
            } catch (e: Exception) {
                call.respond(
                    HttpStatusCode.ServiceUnavailable,
                    mapOf("kafka" to "unavailable", "error" to e.message)
                )
            }
        }
        get("/metrics") {
            call.respondText(MetricsRegistry.prometheus.scrape(), ContentType.Text.Plain)
        }
    }

    // ── Feedback + Dev endpoints (Kafka only; Redis injected later if available) ─
    feedbackRoutes(producer)
    var capturedFeatureStore: FeatureStore? = null

    // ── Dashboard (SSE event feed) ──────────────────────────────────
    // Uses its own "-dashboard" consumer group so it gets all events
    // independently of the EventProcessor's consumer group.
    val dashboardConsumer = KafkaConsumerService(config.kafka, json)
    val broadcaster       = EventBroadcaster(dashboardConsumer, config.kafka)
    broadcaster.start()
    dashboardRoutes(broadcaster, json)

    // ── Redis + ML + Pipeline ───────────────────────────────────────
    // Wrapped in try-catch so the app starts even when Redis is unavailable.
    var redisFactory: RedisClientFactory?                        = null
    var processor: EventProcessor?                               = null
    var decayJob: SessionDecayJob?                               = null
    var capturedFm: OnlineFM?                                    = null
    var capturedRedis: io.lettuce.core.api.coroutines.RedisCoroutinesCommands<String, String>? = null

    try {
        redisFactory = RedisClientFactory(config.redis)
        val redis    = redisFactory.coroutineCommands()
        capturedRedis = redis

        val sessionStore    = SessionStore(redis, config.redis)
        val featureStore    = FeatureStore(redis, json)
        capturedFeatureStore = featureStore
        val fm              = OnlineFM(config.model.fm)
        capturedFm          = fm
        val featureBuilder  = FeatureVectorBuilder(config.model.fm.numFeatures)

        val abAssigner = AbAssigner(redis, config.ab)

        processor = EventProcessor(
            consumer          = KafkaConsumerService(config.kafka, json),
            sessionStore      = sessionStore,
            featureStore      = featureStore,
            fm                = fm,
            featureBuilder    = featureBuilder,
            kafkaTopicEvents  = config.kafka.topicEvents,
            kafkaTopicFeedback = config.kafka.topicFeedback,
        )
        decayJob = SessionDecayJob(redis, sessionStore)

        processor.start()
        decayJob.start()

        // ── Recommendation API + Admin ──────────────────────────────
        recommendationRoutes(
            scoringEngine  = ScoringEngine(fm, sessionStore, featureStore, config.model.scoring),
            featureStore   = featureStore,
            abAssigner     = abAssigner,
            featureBuilder = featureBuilder,
            fm             = fm,
        )

        routing {
            get("/health/redis") {
                call.respond(mapOf("redis" to "ok"))
            }
        }

        logger.info { "Pipeline started — Redis=${config.redis.uri}" }
    } catch (e: Exception) {
        logger.warn(e) { "Redis unavailable — EventProcessor and SessionDecayJob not started" }
    }

    // ── Dev seed endpoints (Kafka + optional Redis for item seeding) ─
    devRoutes(producer, capturedFeatureStore)

    // ── Stats API ───────────────────────────────────────────────────
    statsRoutes(
        fm               = capturedFm,
        isProcessorRunning = processor != null,
        startTimeMs      = startTimeMs,
        redis            = capturedRedis,
    )

    // ── Graceful shutdown ───────────────────────────────────────────
    val capturedProcessor = processor
    val capturedDecayJob  = decayJob
    val capturedFactory   = redisFactory

    environment.monitor.subscribe(ApplicationStopped) {
        capturedProcessor?.stop()
        capturedDecayJob?.stop()
        capturedFactory?.close()
        producer.close()
    }

    logger.info { "RecEngine started — Kafka=${config.kafka.bootstrapServers} Redis=${config.redis.uri}" }
}
