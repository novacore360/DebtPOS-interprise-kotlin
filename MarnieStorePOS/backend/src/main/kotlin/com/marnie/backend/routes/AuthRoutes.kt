package com.marnie.backend.routes

import com.marnie.backend.db.RefreshTokens
import com.marnie.backend.models.*
import com.marnie.backend.security.JwtService
import com.marnie.backend.security.PasswordHasher
import dev.samstevens.totp.code.CodeVerifier
import dev.samstevens.totp.code.DefaultCodeGenerator
import dev.samstevens.totp.code.DefaultCodeVerifier
import dev.samstevens.totp.time.SystemTimeProvider
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.sql.*
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * This app has exactly one operator. Their credentials come from environment
 * variables — there is no `app_users` table and no sign-up flow. The admin's
 * id is fixed so tokens/audit rows/created_by-style bookkeeping stay stable
 * across restarts.
 */
private val ADMIN_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")

/** In-memory lockout state for the single admin account (5 bad attempts -> 15 min lock). */
private object AdminLoginGuard {
    private val mutex = Mutex()
    private var failedAttempts = 0
    private var lockedUntil: Instant? = null

    suspend fun isLocked(): Instant? = mutex.withLock { lockedUntil?.takeIf { it.isAfter(Instant.now()) } }

    suspend fun recordFailure() = mutex.withLock {
        failedAttempts += 1
        if (failedAttempts >= 5) lockedUntil = Instant.now().plus(15, ChronoUnit.MINUTES)
    }

    suspend fun recordSuccess() = mutex.withLock {
        failedAttempts = 0
        lockedUntil = null
    }
}

class AdminConfig(
    val email: String,
    val passwordHash: String,
    val mfaSecret: String?,
    val displayName: String?,
) {
    companion object {
        fun fromEnv(): AdminConfig = AdminConfig(
            email = (System.getenv("ADMIN_EMAIL") ?: error("ADMIN_EMAIL is required")).lowercase(),
            passwordHash = System.getenv("ADMIN_PASSWORD_HASH")
                ?: error("ADMIN_PASSWORD_HASH is required (a bcrypt hash — generate with PasswordHasher.hash(\"...\"))"),
            mfaSecret = System.getenv("ADMIN_MFA_SECRET")?.takeIf { it.isNotBlank() },
            displayName = System.getenv("ADMIN_DISPLAY_NAME") ?: "Admin",
        )
    }
}

// A dummy bcrypt hash to compare against when the email doesn't match, so a
// wrong-email attempt takes the same amount of time as a wrong-password one.
private const val DUMMY_HASH = "\$2a\$12\$C6UzMDM.H6dfI/f/IKcEeOem2/91E4gDpFrjR/UUmKtM4nOoIiiBK"

fun Route.authRoutes(jwtService: JwtService, admin: AdminConfig) {
    route("/api/v1/auth") {

        post("/login") {
            val req = call.receive<LoginRequest>()
            val clientIp = call.request.origin.remoteHost

            val lockedUntil = AdminLoginGuard.isLocked()
            if (lockedUntil != null) {
                call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("Account temporarily locked. Try again later."))
                return@post
            }

            val emailMatches = req.email.trim().lowercase() == admin.email
            val passwordOk = PasswordHasher.verify(req.password, if (emailMatches) admin.passwordHash else DUMMY_HASH) && emailMatches
            val mfaOk = if (admin.mfaSecret.isNullOrBlank()) true else {
                val verifier: CodeVerifier = DefaultCodeVerifier(DefaultCodeGenerator(), SystemTimeProvider())
                req.totpCode != null && verifier.isValidCode(admin.mfaSecret, req.totpCode)
            }

            if (!passwordOk || !mfaOk) {
                AdminLoginGuard.recordFailure()
                call.respond(HttpStatusCode.Unauthorized, ErrorResponse(if (!passwordOk) "Invalid email or password" else "Invalid MFA code"))
                return@post
            }

            AdminLoginGuard.recordSuccess()

            val accessToken = jwtService.createAccessToken(ADMIN_ID)
            val refreshRaw = jwtService.generateRefreshToken()

            withContext(Dispatchers.IO) {
                transaction {
                    RefreshTokens.insert {
                        it[id] = UUID.randomUUID()
                        it[tokenHash] = PasswordHasher.sha256Hex(refreshRaw)
                        it[deviceId] = req.deviceId
                        it[expiresAt] = Instant.now().plus(30, ChronoUnit.DAYS)
                        it[revoked] = false
                        it[createdAt] = Instant.now()
                    }
                    com.marnie.backend.db.AuditLog.insert {
                        it[action] = "login"
                        it[entityType] = "session"
                        it[entityId] = null
                        it[metadata] = null
                        it[ipAddress] = clientIp
                        it[deviceId] = req.deviceId
                        it[createdAt] = Instant.now()
                    }
                }
            }

            call.respond(
                AuthResponse(
                    accessToken = accessToken,
                    refreshToken = refreshRaw,
                    userId = ADMIN_ID.toString(),
                    displayName = admin.displayName,
                )
            )
        }

        post("/refresh") {
            val req = call.receive<RefreshRequest>()
            val hash = PasswordHasher.sha256Hex(req.refreshToken)

            val row = withContext(Dispatchers.IO) {
                transaction {
                    RefreshTokens.selectAll().where {
                        (RefreshTokens.tokenHash eq hash) and (RefreshTokens.deviceId eq req.deviceId)
                    }.singleOrNull()
                }
            }

            if (row == null || row[RefreshTokens.revoked] || row[RefreshTokens.expiresAt].isBefore(Instant.now())) {
                call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Refresh token invalid or expired"))
                return@post
            }

            // Rotate refresh token (single use) to limit replay window if stolen.
            val newRefresh = jwtService.generateRefreshToken()
            withContext(Dispatchers.IO) {
                transaction {
                    RefreshTokens.update({ RefreshTokens.id eq row[RefreshTokens.id] }) { it[revoked] = true }
                    RefreshTokens.insert {
                        it[id] = UUID.randomUUID()
                        it[tokenHash] = PasswordHasher.sha256Hex(newRefresh)
                        it[deviceId] = req.deviceId
                        it[expiresAt] = Instant.now().plus(30, ChronoUnit.DAYS)
                        it[revoked] = false
                        it[createdAt] = Instant.now()
                    }
                }
            }

            val accessToken = jwtService.createAccessToken(ADMIN_ID)
            call.respond(
                AuthResponse(
                    accessToken = accessToken,
                    refreshToken = newRefresh,
                    userId = ADMIN_ID.toString(),
                    displayName = admin.displayName,
                )
            )
        }

        post("/logout") {
            val req = call.receive<RefreshRequest>()
            val hash = PasswordHasher.sha256Hex(req.refreshToken)
            withContext(Dispatchers.IO) {
                transaction {
                    RefreshTokens.update({ RefreshTokens.tokenHash eq hash }) { it[revoked] = true }
                }
            }
            call.respond(HttpStatusCode.OK, mapOf("status" to "logged_out"))
        }
    }
}
