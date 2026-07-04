package com.marnie.backend.plugins

import com.marnie.backend.plugins.DatabaseFactory.dataSource
import io.ktor.websocket.*
import kotlinx.coroutines.*
import org.postgresql.PGConnection
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory hub of WebSocket sessions. A background coroutine holds a single
 * dedicated JDBC connection and executes `LISTEN pos_changes`; every commit
 * (including from admin SQL, etc.) fires a Postgres NOTIFY (see schema.sql),
 * which is fanned out here to every connected device — this is how
 * "realtime" works across multiple phones/tablets without polling. This is a
 * single-admin, single-store app, so there is no per-tenant fan-out: every
 * connected device gets every notification.
 */
object RealtimeHub {
    private val sessions = ConcurrentHashMap.newKeySet<WebSocketSession>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun register(session: WebSocketSession) {
        sessions.add(session)
    }

    fun unregister(session: WebSocketSession) {
        sessions.remove(session)
    }

    suspend fun broadcast(message: String) {
        sessions.toList().forEach { s ->
            try {
                s.send(Frame.Text(message))
            } catch (_: Exception) {
                unregister(s)
            }
        }
    }

    fun startListener() {
        scope.launch {
            while (isActive) {
                try {
                    val conn = dataSource.connection
                    val pgConn = conn.unwrap(PGConnection::class.java)
                    conn.createStatement().execute("LISTEN pos_changes")
                    while (isActive && !conn.isClosed) {
                        val notifications = pgConn.notifications
                        if (notifications != null) {
                            for (n in notifications) {
                                broadcast(n.parameter)
                            }
                        }
                        delay(500) // poll interval for the JDBC notification queue
                    }
                    conn.close()
                } catch (e: Exception) {
                    delay(3000) // reconnect after Neon idle/cold-start disconnects
                }
            }
        }
    }
}
