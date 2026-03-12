package com.recengine.model

import kotlinx.serialization.Serializable

@Serializable
data class RecommendationRequest(
    val userId: String,
    val sessionId: String,
    val context: Map<String, String> = emptyMap(),
    val limit: Int = 20,
    val excludeItemIds: List<String> = emptyList()
)

@Serializable
data class ScoreComponents(
    val sessionScore: Double,
    val contentScore: Double,
    val popularityScore: Double,
    val recencyScore: Double,
    val compositeScore: Double
)

@Serializable
data class ScoredItem(
    val itemId: String,
    val score: Double,
    val components: ScoreComponents,
    val metadata: Map<String, String> = emptyMap()
)

@Serializable
data class RecommendationResponse(
    val userId: String,
    val sessionId: String,
    val recommendationId: String,
    val variantId: String,
    val items: List<ScoredItem>,
    val generatedAtMs: Long = System.currentTimeMillis()
)

@Serializable
data class LossPoint(val timestampMs: Long, val loss: Double)

@Serializable
data class ModelMetrics(
    val totalUpdates: Long,
    val lastUpdateMs: Long,
    val averageLoss: Double,
    val variantDistribution: Map<String, Int>
)

@Serializable
data class TrendingResponse(val items: List<String>, val window: String)

@Serializable
data class PipelineStats(
    val eventsProcessed:  Map<String, Long>,
    val fmTotalUpdates:   Long,
    val fmLastUpdateMs:   Long,
    val fmRunningLoss:    Double,
    val recLatencyP50Ms:  Double,
    val recLatencyP95Ms:  Double,
    val recLatencyP99Ms:  Double,
    val processorRunning:    Boolean,
    val uptimeMs:            Long,
    val redisApproxKeys:     Long,
    val fmLossHistory:       List<LossPoint> = emptyList(),
    val variantServeCounts:  Map<String, Long> = emptyMap(),
)
