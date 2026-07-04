package com.marnie.pos.data.repository

import com.marnie.pos.data.local.dao.CustomerDao
import com.marnie.pos.data.local.entities.CustomerEntity
import com.marnie.pos.data.remote.dto.CustomerDto
import com.marnie.pos.sync.OutboxWriter
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class CustomerOutboxPayload(val customer: CustomerDto, val pin: String? = null)

@Singleton
class CustomerRepository @Inject constructor(
    private val customerDao: CustomerDao,
    private val outboxWriter: OutboxWriter,
) {
    fun observeAll(): Flow<List<CustomerEntity>> = customerDao.observeAll()

    suspend fun getById(id: String): CustomerEntity? = customerDao.getById(id)

    /** [pin] is the plaintext 4-6 digit customer-portal PIN; it's hashed
     *  server-side and never persisted locally in plaintext or synced back. */
    suspend fun upsert(
        id: String? = null,
        name: String,
        phone: String?,
        email: String?,
        pin: String? = null,
    ) {
        val now = Instant.now().toString()
        val resolvedId = id ?: UUID.randomUUID().toString()
        val existing = id?.let { customerDao.getById(it) }
        val entity = CustomerEntity(
            id = resolvedId, name = name, phone = phone, email = email,
            hasAccessPin = pin != null || existing?.hasAccessPin == true,
            isDeleted = false, version = (existing?.version ?: 0) + 1,
            updatedAt = now, createdAt = existing?.createdAt ?: now, dirty = true,
        )
        customerDao.upsert(entity)
        outboxWriter.enqueue("customer", resolvedId, "upsert", CustomerOutboxPayload(entity.toDto(), pin))
    }

    suspend fun delete(id: String) {
        val existing = customerDao.getById(id) ?: return
        val updated = existing.copy(isDeleted = true, updatedAt = Instant.now().toString(), dirty = true)
        customerDao.upsert(updated)
        outboxWriter.enqueue("customer", id, "delete", CustomerOutboxPayload(updated.toDto()))
    }
}

private fun CustomerEntity.toDto() = CustomerDto(
    id = id, name = name, phone = phone, email = email, hasAccessPin = hasAccessPin,
    isDeleted = isDeleted, version = version, updatedAt = updatedAt, createdAt = createdAt,
)
