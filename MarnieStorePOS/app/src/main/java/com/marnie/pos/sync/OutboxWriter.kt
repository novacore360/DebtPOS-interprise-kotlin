package com.marnie.pos.sync

import android.content.Context
import com.marnie.pos.data.local.dao.OutboxDao
import com.marnie.pos.data.local.entities.OutboxEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OutboxWriter @Inject constructor(
    private val outboxDao: OutboxDao,
    @ApplicationContext private val context: Context,
) {
    private val json = Json { encodeDefaults = true }

    /** Every local write goes through here: append to the outbox, then nudge
     *  WorkManager to try flushing it right away (falls back silently to the
     *  next periodic tick / reconnect if there's no network). */
    suspend inline fun <reified T> enqueue(entity: String, entityId: String, op: String, payload: T) {
        outboxDao.enqueue(
            OutboxEntity(
                entity = entity,
                entityId = entityId,
                op = op,
                payloadJson = json.encodeToString(payload),
                clientUpdatedAt = Instant.now().toString(),
            )
        )
        SyncWorker.triggerImmediate(context)
    }
}
