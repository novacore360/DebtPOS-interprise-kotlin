package com.marnie.backend

import com.marnie.backend.models.ErrorResponse
import com.marnie.backend.plugins.DatabaseFactory
import com.marnie.backend.plugins.RealtimeHub
import com.marnie.backend.plugins.configureSecurity
import com.marnie.backend.routes.AdminConfig
import com.marnie.backend.routes.authRoutes
import com.marnie.backend.routes.realtimeRoutes
import com.marnie.backend.routes.syncRoutes
import com.marnie.backend.security.JwtService
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.calllogging.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.plugins.defaultheaders.*
import io.ktor.server.plugins.forwardedheaders.*
import io.ktor.server.plugins.ratelimit.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import kotlinx.serialization.json.Json
import java.time.Duration
import kotlin.time.Duration.Companion.minutes

fun main() {
    embeddedServer(Netty, port = System.getenv("PORT")?.toIntOrNull() ?: 8080, module = Application::module)
        .start(wait = true)
}

fun Application.module() {
    // --- Config from environment (never hardcode secrets) ---
    val jdbcUrl = System.getenv("NEON_JDBC_URL")
        ?: error("NEON_JDBC_URL is required, e.g. jdbc:postgresql://<host>/<db>?sslmode=require")
    val dbUser = System.getenv("NEON_DB_USER") ?: error("NEON_DB_USER is required")
    val dbPassword = System.getenv("NEON_DB_PASSWORD") ?: error("NEON_DB_PASSWORD is required")
    val jwtSecret = System.getenv("JWT_SECRET") ?: error("JWT_SECRET is required (32+ random bytes)")
    val allowedOrigins = System.getenv("ALLOWED_ORIGINS")?.split(",") ?: emptyList()
    val admin = AdminConfig.fromEnv()

    DatabaseFactory.init(jdbcUrl, dbUser, dbPassword)
    RealtimeHub.startListener()

    val jwtService = JwtService(jwtSecret)

    install(ForwardedHeaders) // trust X-Forwarded-* from the CI/host's load balancer only
    install(DefaultHeaders) {
        header("X-Content-Type-Options", "nosniff")
        header("X-Frame-Options", "DENY")
        header("Referrer-Policy", "no-referrer")
        header("Strict-Transport-Security", "max-age=31536000; includeSubDomains")
    }
    install(CallLogging)
    install(WebSockets) {
        pingPeriod = Duration.ofSeconds(30)
        timeout = Duration.ofSeconds(60)
    }
    install(ContentNegotiation) {
        json(Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        })
    }
    install(CORS) {
        allowedOrigins.forEach { allowHost(it, schemes = listOf("https")) }
        allowMethod(HttpMethod.Get); allowMethod(HttpMethod.Post); allowMethod(HttpMethod.Put); allowMethod(HttpMethod.Delete)
        allowHeader(HttpHeaders.Authorization); allowHeader(HttpHeaders.ContentType)
    }
    install(RateLimit) {
        global {
            rateLimiter(limit = 60, refillPeriod = 1.minutes)
        }
    }
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            call.application.environment.log.error("Unhandled error", cause)
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Internal server error"))
        }
    }

    configureSecurity(jwtService)

    routing {
        get("/health") { call.respond(mapOf("status" to "ok")) }
        authRoutes(jwtService, admin)
        syncRoutes()
        realtimeRoutes()
    }
}
