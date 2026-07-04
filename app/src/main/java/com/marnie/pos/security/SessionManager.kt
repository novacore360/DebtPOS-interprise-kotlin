package com.marnie.pos.security

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Single-admin app: a session is just the fixed admin's id and display name. */
data class Session(
    val userId: String,
    val displayName: String?,
)

@Singleton
class SessionManager @Inject constructor(
    private val cryptoManager: CryptoManager,
) {
    private val _session = MutableStateFlow(loadFromDisk())
    val session: StateFlow<Session?> = _session.asStateFlow()

    val isLoggedIn: Boolean get() = _session.value != null

    private fun loadFromDisk(): Session? {
        val userId = cryptoManager.getUserId() ?: return null
        return Session(userId, cryptoManager.getDisplayName())
    }

    fun onLoginSuccess(
        accessToken: String,
        refreshToken: String,
        userId: String,
        displayName: String?,
    ) {
        cryptoManager.saveAccessToken(accessToken)
        cryptoManager.saveRefreshToken(refreshToken)
        cryptoManager.saveSession(userId, displayName)
        _session.value = Session(userId, displayName)
    }

    fun onTokensRefreshed(accessToken: String, refreshToken: String) {
        cryptoManager.saveAccessToken(accessToken)
        cryptoManager.saveRefreshToken(refreshToken)
    }

    fun logout() {
        cryptoManager.clearSession()
        _session.value = null
    }
}
