package com.marnie.backend.security

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import java.security.SecureRandom
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.UUID

/**
 * Access tokens are short-lived (15 min) JWTs used for API calls.
 * Refresh tokens are long-lived opaque random strings, stored server-side
 * ONLY as a salted hash (see PasswordHasher.sha256Hex), so a leaked DB dump
 * cannot be replayed as a valid refresh token.
 *
 * There is only ever one operator (see AuthRoutes.kt), so tokens carry just
 * that fixed admin id — no store/role claims to check.
 */
class JwtService(
    private val secret: String,
    private val issuer: String = "marnie-pos",
    private val audience: String = "marnie-pos-app",
) {
    private val algorithm = Algorithm.HMAC256(secret)

    val verifier = JWT.require(algorithm)
        .withIssuer(issuer)
        .withAudience(audience)
        .build()

    fun createAccessToken(userId: UUID): String =
        JWT.create()
            .withIssuer(issuer)
            .withAudience(audience)
            .withSubject(userId.toString())
            .withClaim("type", "access")
            .withIssuedAt(Instant.now())
            .withExpiresAt(Instant.now().plus(15, ChronoUnit.MINUTES))
            .sign(algorithm)

    fun generateRefreshToken(): String {
        val bytes = ByteArray(48)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}
