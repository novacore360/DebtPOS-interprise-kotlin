package com.marnie.pos.security

import com.marnie.pos.data.remote.api.AuthApi
import com.marnie.pos.data.remote.dto.RefreshRequestDto
import kotlinx.coroutines.runBlocking
import okhttp3.Authenticator
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import java.util.concurrent.locks.ReentrantLock
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlin.concurrent.withLock

@Singleton
class AuthInterceptor @Inject constructor(
    private val cryptoManager: CryptoManager,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        // Auth + sync endpoints need the bearer token; login/refresh do not.
        if (original.url.encodedPath.contains("/auth/login") || original.url.encodedPath.contains("/auth/refresh")) {
            return chain.proceed(original)
        }
        val token = cryptoManager.getAccessToken()
        val request = if (token != null) {
            original.newBuilder().addHeader("Authorization", "Bearer $token").build()
        } else original
        return chain.proceed(request)
    }
}

/**
 * On a 401, attempts exactly one refresh (guarded by a lock so concurrent
 * requests don't all fire refresh calls) then retries the original request
 * with the new token. If refresh itself fails, the session is cleared and
 * the UI observing SessionManager.session will drop back to the login screen.
 */
@Singleton
class TokenAuthenticator @Inject constructor(
    private val authApiProvider: Provider<AuthApi>,
    private val cryptoManager: CryptoManager,
    private val sessionManager: SessionManager,
) : Authenticator {

    private val lock = ReentrantLock()

    override fun authenticate(route: Route?, response: Response): Request? {
        if (responseCount(response) >= 2) return null // avoid infinite retry loop

        return lock.withLock {
            val currentToken = cryptoManager.getAccessToken()
            val requestToken = response.request.header("Authorization")?.removePrefix("Bearer ")
            // Another thread already refreshed while we waited for the lock.
            if (currentToken != null && requestToken != currentToken) {
                return@withLock response.request.newBuilder()
                    .header("Authorization", "Bearer $currentToken")
                    .build()
            }

            val refreshToken = cryptoManager.getRefreshToken() ?: run {
                sessionManager.logout()
                return@withLock null
            }
            val deviceId = cryptoManager.getOrCreateDeviceId()

            val result = runCatching {
                runBlocking { authApiProvider.get().refresh(RefreshRequestDto(refreshToken, deviceId)) }
            }.getOrNull()

            if (result == null) {
                sessionManager.logout()
                return@withLock null
            }

            sessionManager.onTokensRefreshed(result.accessToken, result.refreshToken)
            response.request.newBuilder()
                .header("Authorization", "Bearer ${result.accessToken}")
                .build()
        }
    }

    private fun responseCount(response: Response): Int {
        var res: Response? = response
        var count = 1
        while (res?.priorResponse != null) { count++; res = res.priorResponse }
        return count
    }
}
