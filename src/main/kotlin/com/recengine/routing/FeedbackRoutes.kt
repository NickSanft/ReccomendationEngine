package com.recengine.routing

import com.recengine.kafka.KafkaProducerService
import com.recengine.model.ClickEvent
import com.recengine.model.FeedbackEvent
import com.recengine.model.ImpressionEvent
import com.recengine.model.PurchaseEvent
import com.recengine.model.RecEngineEvent
import com.recengine.model.ViewEvent
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Application.feedbackRoutes(producer: KafkaProducerService) {
    routing {
        route("/api/v1/events") {

            post("/click") {
                val event = call.receive<ClickEvent>()
                producer.sendEvent(event)
                call.respond(HttpStatusCode.Accepted, mapOf("status" to "queued"))
            }

            post("/view") {
                val event = call.receive<ViewEvent>()
                producer.sendEvent(event)
                call.respond(HttpStatusCode.Accepted, mapOf("status" to "queued"))
            }

            post("/impression") {
                val event = call.receive<ImpressionEvent>()
                producer.sendEvent(event)
                call.respond(HttpStatusCode.Accepted, mapOf("status" to "queued"))
            }

            post("/purchase") {
                val event = call.receive<PurchaseEvent>()
                producer.sendEvent(event)
                call.respond(HttpStatusCode.Accepted, mapOf("status" to "queued"))
            }

            post("/feedback") {
                val event = call.receive<FeedbackEvent>()
                producer.sendFeedback(event)
                call.respond(HttpStatusCode.Accepted, mapOf("status" to "queued"))
            }

            // Bulk ingest — max 100 events per request
            post("/batch") {
                val events = call.receive<List<RecEngineEvent>>()
                if (events.size > 100) {
                    return@post call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "max 100 events per batch")
                    )
                }
                events.forEach { producer.sendEvent(it) }
                call.respond(HttpStatusCode.Accepted, mapOf("status" to "queued", "count" to events.size))
            }
        }
    }
}
