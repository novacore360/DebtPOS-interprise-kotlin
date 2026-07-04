package com.marnie.pos.sync

import com.marnie.pos.data.local.dao.*
import com.marnie.pos.data.local.entities.*
import com.marnie.pos.data.remote.api.SyncApi
import com.marnie.pos.data.remote.dto.*
import com.marnie.pos.data.repository.CustomerOutboxPayload
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

sealed class SyncStatus {
    data object Idle : SyncStatus()
    data object Syncing : SyncStatus()
    data class Success(val at: Instant) : SyncStatus()
    data class Error(val message: String) : SyncStatus()
}

/**
 * The single choke point through which every local mutation eventually
 * reaches Neon Postgres, and through which every remote change reaches the
 * local Room cache. Two-phase, always in this order:
 *
 *   1. PUSH — drain the outbox (offline queue) to /sync/push. Each mutation
 *      is applied by the server with last-write-wins conflict resolution
 *      keyed on `clientUpdatedAt` vs the server row's updated_at.
 *   2. PULL — fetch everything changed since our last cursor from
 *      /sync/pull and upsert it into Room, clearing the `dirty` flag.
 *
 * This runs: on app foreground, on network reconnect (ConnectivityObserver),
 * on a WorkManager periodic tick (every 15 min as a safety net), on realtime
 * WebSocket notification, and immediately after any user-initiated write so
 * the UI feels instant even before the network round-trip completes (Room
 * is written first, optimistically, then reconciled).
 */
@Singleton
class SyncManager @Inject constructor(
    private val syncApi: SyncApi,
    private val productDao: ProductDao,
    private val customerDao: CustomerDao,
    private val purchaseDao: PurchaseDao,
    private val paymentDao: PaymentDao,
    private val outboxDao: OutboxDao,
    private val cursorStore: SyncCursorStore,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()

    private val _status = MutableStateFlow<SyncStatus>(SyncStatus.Idle)
    val status: StateFlow<SyncStatus> = _status

    val pendingOutboxCount = outboxDao.observePendingCount()

    suspend fun syncNow(): Result<Unit> = mutex.withLock {
        _status.value = SyncStatus.Syncing
        return try {
            push()
            pull()
            _status.value = SyncStatus.Success(Instant.now())
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.w(e, "Sync failed")
            _status.value = SyncStatus.Error(e.message ?: "Sync failed")
            Result.failure(e)
        }
    }

    private suspend fun push() {
        val outbox = outboxDao.getAll()
        if (outbox.isEmpty()) return

        val mutations = outbox.map { entry ->
            when (entry.entity) {
                "product" -> MutationDto("product", entry.op, entry.clientUpdatedAt, product = json.decodeFromString(entry.payloadJson))
                "customer" -> {
                    val wrapper = json.decodeFromString<CustomerOutboxPayload>(entry.payloadJson)
                    MutationDto("customer", entry.op, entry.clientUpdatedAt, customer = wrapper.customer, customerPin = wrapper.pin)
                }
                "purchase" -> MutationDto("purchase", entry.op, entry.clientUpdatedAt, purchase = json.decodeFromString(entry.payloadJson))
                "payment" -> MutationDto("payment", entry.op, entry.clientUpdatedAt, payment = json.decodeFromString(entry.payloadJson))
                else -> null
            }
        }.filterNotNull()

        if (mutations.isEmpty()) return

        val response = syncApi.push(SyncPushRequestDto(mutations))

        response.results.forEachIndexed { index, result ->
            val outboxEntry = outbox.getOrNull(index) ?: return@forEachIndexed
            when (result.status) {
                "applied" -> outboxDao.remove(outboxEntry.seq)
                "conflict" -> {
                    // Server wins: drop our queued mutation, the upcoming pull()
                    // will overwrite local state with the authoritative row.
                    Timber.w("Sync conflict on ${result.entity}/${result.id}: ${result.message}")
                    outboxDao.remove(outboxEntry.seq)
                }
                else -> outboxDao.incrementAttempts(outboxEntry.seq)
            }
        }
        outboxDao.purgeExhausted()
    }

    private suspend fun pull() {
        val since = cursorStore.getLastSyncAt()
        val data = syncApi.pull(since)

        productDao.upsertAll(data.products.map { it.toEntity() })
        customerDao.upsertAll(data.customers.map { it.toEntity() })

        data.purchases.forEach { p ->
            purchaseDao.upsert(p.toEntity())
            purchaseDao.clearItems(p.id)
            purchaseDao.upsertItems(p.items.map { it.toEntity(p.id) })
        }
        paymentDao.upsertAll(data.payments.map { it.toEntity() })

        cursorStore.setLastSyncAt(data.serverTime)
    }
}

private fun ProductDto.toEntity() = ProductEntity(
    id = id, productCode = productCode, name = name, category = category,
    costPrice = costPrice, retailPrice = retailPrice, price = price, stock = stock,
    lowStockThreshold = lowStockThreshold, isDeleted = isDeleted, version = version,
    updatedAt = updatedAt ?: Instant.now().toString(), createdAt = createdAt ?: Instant.now().toString(), dirty = false,
)

private fun CustomerDto.toEntity() = CustomerEntity(
    id = id, name = name, phone = phone, email = email, hasAccessPin = hasAccessPin,
    isDeleted = isDeleted, version = version,
    updatedAt = updatedAt ?: Instant.now().toString(), createdAt = createdAt ?: Instant.now().toString(), dirty = false,
)

private fun PurchaseDto.toEntity() = PurchaseEntity(
    id = id, customerId = customerId, customerName = customerName, totalAmount = totalAmount,
    amountPaid = amountPaid, status = status, purchaseDate = purchaseDate, createdByEmail = createdByEmail,
    isDeleted = isDeleted, version = version,
    updatedAt = updatedAt ?: Instant.now().toString(), createdAt = createdAt ?: Instant.now().toString(), dirty = false,
)

private fun PurchaseItemDto.toEntity(purchaseId: String) = PurchaseItemEntity(
    id = id, purchaseId = purchaseId, productId = productId, name = name, price = price, quantity = quantity, subtotal = subtotal,
)

private fun PaymentDto.toEntity() = PaymentEntity(
    id = id, purchaseId = purchaseId, customerId = customerId, amount = amount, method = method, note = note,
    isDeleted = isDeleted, version = version,
    updatedAt = updatedAt ?: Instant.now().toString(), createdAt = createdAt ?: Instant.now().toString(), dirty = false,
)
