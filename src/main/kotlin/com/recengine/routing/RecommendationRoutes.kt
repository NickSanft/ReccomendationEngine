package com.recengine.routing

import com.recengine.ml.FeatureVectorBuilder
import com.recengine.ml.OnlineFM
import com.recengine.ml.ScoringEngine
import com.recengine.model.ModelMetrics
import com.recengine.model.RecommendationRequest
import com.recengine.model.RecommendationResponse
import com.recengine.model.TrendingResponse
import com.recengine.redis.FeatureStore
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.util.UUID

fun Application.recommendationRoutes(
    scoringEngine: ScoringEngine,
    featureStore: FeatureStore,
    abAssigner: AbAssigner,
    featureBuilder: FeatureVectorBuilder,
    fm: OnlineFM,
) {
    routing {
        route("/api/v1") {

            // GET /api/v1/recommendations/{userId}?limit=20&session_id=default
            get("/recommendations/{userId}") {
                val userId    = call.parameters["userId"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "userId required"))
                val limit     = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 100) ?: 20
                val sessionId = call.request.queryParameters["session_id"] ?: "default"
                val variantId = abAssigner.getOrAssign(userId)

                val candidates = featureStore.getPopularItems(window = "hourly", limit = 200)
                val scored     = scoringEngine.scoreItems(userId, candidates, featureBuilder).take(limit)

                call.respond(
                    RecommendationResponse(
                        userId           = userId,
                        sessionId        = sessionId,
                        recommendationId = UUID.randomUUID().toString(),
                        variantId        = variantId,
                        items            = scored,
                    )
                )
            }

            // POST /api/v1/recommendations  (with excludeItemIds support)
            post("/recommendations") {
                val req       = call.receive<RecommendationRequest>()
                val variantId = abAssigner.getOrAssign(req.userId)

                val candidates = featureStore.getPopularItems(window = "hourly", limit = 500)
                    .filter { it !in req.excludeItemIds }
                val scored     = scoringEngine.scoreItems(req.userId, candidates, featureBuilder).take(req.limit)

                call.respond(
                    RecommendationResponse(
                        userId           = req.userId,
                        sessionId        = req.sessionId,
                        recommendationId = UUID.randomUUID().toString(),
                        variantId        = variantId,
                        items            = scored,
                    )
                )
            }

            // GET /api/v1/trending?window=hourly
            get("/trending") {
                val window = call.request.queryParameters["window"] ?: "hourly"
                val items  = featureStore.getPopularItems(window = window, limit = 50L)
                call.respond(TrendingResponse(items = items, window = window))
            }

            // GET /api/v1/admin/model/metrics
            get("/admin/model/metrics") {
                call.respond(
                    ModelMetrics(
                        totalUpdates        = fm.totalUpdates.get(),
                        lastUpdateMs        = fm.lastUpdateMs.get(),
                        averageLoss         = fm.averageLoss(),
                        variantDistribution = emptyMap(),
                    )
                )
            }
        }
    }
}
