package com.marnie.backend.routes

import com.marnie.backend.plugins.RealtimeHub
import com.marnie.backend.plugins.UserPrincipal
import io.ktor.server.auth.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.channels.consumeEach

/**
 * Devices open one WebSocket per session and simply wait for change
 * notifications. On receiving a message the client triggers a
 * `/api/v1/sync/pull` — the socket itself never carries entity data, keeping
 * the security-sensitive payload path single (REST + JWT). Single-admin,
 * single-store app, so there's nothing to scope the socket to besides "is
 * this the authenticated admin".
 */
fun Route.realtimeRoutes() {
    authenticate("auth-jwt") {
        webSocket("/api/v1/realtime") {
            call.principal<UserPrincipal>()!!
            RealtimeHub.register(this)
            try {
                send(Frame.Text("""{"type":"connected"}"""))
                incoming.consumeEach { /* client -> server pings only, ignored */ }
            } finally {
                RealtimeHub.unregister(this)
            }
        }
    }
}
