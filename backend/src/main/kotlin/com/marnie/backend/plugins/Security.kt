package com.marnie.backend.plugins

import com.marnie.backend.security.JwtService
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import java.util.UUID

/** Single-admin app: the principal is just the fixed admin id from the JWT subject. */
data class UserPrincipal(
    val userId: UUID,
) : Principal

fun Application.configureSecurity(jwtService: JwtService) {
    authentication {
        jwt("auth-jwt") {
            verifier(jwtService.verifier)
            validate { credential ->
                val type = credential.payload.getClaim("type").asString()
                val sub = credential.subject
                if (type == "access" && sub != null) {
                    UserPrincipal(UUID.fromString(sub))
                } else null
            }
            challenge { _, _ ->
                call.respond(io.ktor.http.HttpStatusCode.Unauthorized, mapOf("error" to "Invalid or expired token"))
            }
        }
    }
}
