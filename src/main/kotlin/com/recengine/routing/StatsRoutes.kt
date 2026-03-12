package com.recengine.routing

import com.recengine.metrics.MetricsRegistry
import com.recengine.ml.OnlineFM
import com.recengine.model.LossPoint
import com.recengine.model.PipelineStats
import io.lettuce.core.api.coroutines.RedisCoroutinesCommands
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.util.concurrent.TimeUnit

fun Application.statsRoutes(
    fm: OnlineFM?,
    isProcessorRunning: Boolean,
    startTimeMs: Long,
    redis: RedisCoroutinesCommands<String, String>?,
    abAssigner: AbAssigner? = null,
) {
    routing {
        get("/api/v1/stats") {
            // Event counters from Micrometer, grouped by event type tag
            val eventsProcessed = MetricsRegistry.prometheus
                .find("recengine.events.processed")
                .counters()
                .associate { c -> (c.id.getTag("type") ?: "unknown") to c.count().toLong() }

            // Timer percentiles via histogram snapshot — values come back in the requested TimeUnit
            val snap    = MetricsRegistry.recommendationLatency.takeSnapshot()
            val percs   = snap.percentileValues()
            fun perc(p: Double) = percs.find { it.percentile() == p }?.value(TimeUnit.MILLISECONDS) ?: 0.0
            val latencyP50 = perc(0.50)
            val latencyP95 = perc(0.95)
            val latencyP99 = perc(0.99)

            // Redis approximate key count (DBSIZE); -1 when Redis is unavailable
            val redisKeys = try { redis?.dbsize() ?: -1L } catch (_: Exception) { -1L }

            val lossHistory = fm?.getLossHistory()
                ?.map { (ts, loss) -> LossPoint(ts, loss) }
                ?: emptyList()

            call.respond(
                PipelineStats(
                    eventsProcessed     = eventsProcessed,
                    fmTotalUpdates      = fm?.totalUpdates?.get() ?: 0L,
                    fmLastUpdateMs      = fm?.lastUpdateMs?.get() ?: 0L,
                    fmRunningLoss       = fm?.averageLoss() ?: 0.0,
                    recLatencyP50Ms     = latencyP50,
                    recLatencyP95Ms     = latencyP95,
                    recLatencyP99Ms     = latencyP99,
                    processorRunning    = isProcessorRunning,
                    uptimeMs            = System.currentTimeMillis() - startTimeMs,
                    redisApproxKeys     = redisKeys,
                    fmLossHistory       = lossHistory,
                    variantServeCounts  = abAssigner?.getServeCounts() ?: emptyMap(),
                )
            )
        }
    }
}
