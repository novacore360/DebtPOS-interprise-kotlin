package com.marnie.pos.data.repository

import com.marnie.pos.data.remote.api.AuthApi
import com.marnie.pos.data.remote.dto.LoginRequestDto
import com.marnie.pos.data.remote.dto.RefreshRequestDto
import com.marnie.pos.security.CryptoManager
import com.marnie.pos.security.SessionManager
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthRepository @Inject constructor(
    private val authApi: AuthApi,
    private val sessionManager: SessionManager,
    private val cryptoManager: CryptoManager,
) {
    suspend fun login(email: String, password: String, totpCode: String? = null): Result<Unit> = runCatching {
        val deviceId = cryptoManager.getOrCreateDeviceId()
        val response = authApi.login(LoginRequestDto(email.trim().lowercase(), password, deviceId, totpCode))
        sessionManager.onLoginSuccess(
            accessToken = response.accessToken,
            refreshToken = response.refreshToken,
            userId = response.userId,
            displayName = response.displayName,
        )
    }

    suspend fun logout() {
        val refreshToken = cryptoManager.getRefreshToken()
        val deviceId = cryptoManager.getOrCreateDeviceId()
        if (refreshToken != null) {
            runCatching { authApi.logout(RefreshRequestDto(refreshToken, deviceId)) }
        }
        sessionManager.logout()
    }
}
