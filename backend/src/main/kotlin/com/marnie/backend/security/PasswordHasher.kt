package com.marnie.backend.security

import at.favre.lib.crypto.bcrypt.BCrypt
import java.security.MessageDigest

object PasswordHasher {
    // Cost factor 12 — tuned so a single hash takes ~150-250ms server side,
    // slow enough to blunt brute force, fast enough not to bottleneck login.
    private const val COST = 12

    fun hash(plain: String): String =
        BCrypt.withDefaults().hashToString(COST, plain.toCharArray())

    fun verify(plain: String, hash: String): Boolean =
        BCrypt.verifyer().verify(plain.toCharArray(), hash).verified

    /** Used only for opaque refresh tokens — sha256 is fine since they're
     *  high-entropy random values, not user-chosen secrets. */
    fun sha256Hex(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}
