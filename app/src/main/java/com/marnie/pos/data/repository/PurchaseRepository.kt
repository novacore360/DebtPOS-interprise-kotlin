package com.marnie.pos.data.repository

import com.marnie.pos.data.local.dao.PaymentDao
import com.marnie.pos.data.local.dao.PurchaseDao
import com.marnie.pos.data.local.entities.PaymentEntity
import com.marnie.pos.data.local.entities.PurchaseEntity
import com.marnie.pos.data.local.entities.PurchaseItemEntity
import com.marnie.pos.data.remote.dto.PaymentDto
import com.marnie.pos.data.remote.dto.PurchaseDto
import com.marnie.pos.data.remote.dto.PurchaseItemDto
import com.marnie.pos.sync.OutboxWriter
import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class CartLine(val productId: String, val name: String, val price: Double, val quantity: Int) {
    val subtotal: Double get() = price * quantity
}

@Singleton
class PurchaseRepository @Inject constructor(
    private val purchaseDao: PurchaseDao,
    private val paymentDao: PaymentDao,
    private val productRepository: ProductRepository,
    private val outboxWriter: OutboxWriter,
) {
    fun observeAll(): Flow<List<PurchaseEntity>> = purchaseDao.observeAll()
    fun observeByCustomer(customerId: String): Flow<List<PurchaseEntity>> = purchaseDao.observeByCustomer(customerId)
    fun observePaymentsFor(purchaseId: String): Flow<List<PaymentEntity>> = paymentDao.observeForPurchase(purchaseId)
    suspend fun itemsFor(purchaseId: String): List<PurchaseItemEntity> = purchaseDao.itemsFor(purchaseId)

    /**
     * Records a sale: writes the purchase + line items to Room immediately
     * (works fully offline — a store with spotty internet can keep selling),
     * deducts stock for each product, and queues both for sync. amountPaid=0
     * means the whole thing becomes customer debt, matching the original
     * app's "utang" (debt) workflow; any amountPaid>0 records a partial/paid
     * sale in one step.
     */
    suspend fun recordSale(
        customerId: String?,
        customerName: String?,
        items: List<CartLine>,
        amountPaid: Double,
        createdByEmail: String?,
    ): String {
        val id = UUID.randomUUID().toString()
        val now = Instant.now().toString()
        val total = items.sumOf { it.subtotal }
        val status = when {
            amountPaid >= total -> "paid"
            amountPaid > 0 -> "partial"
            else -> "pending"
        }

        val purchase = PurchaseEntity(
            id = id, customerId = customerId, customerName = customerName,
            totalAmount = total, amountPaid = amountPaid, status = status,
            purchaseDate = now, createdByEmail = createdByEmail,
            isDeleted = false, version = 1, updatedAt = now, createdAt = now, dirty = true,
        )
        purchaseDao.upsert(purchase)

        val itemEntities = items.map {
            PurchaseItemEntity(
                id = UUID.randomUUID().toString(), purchaseId = id, productId = it.productId,
                name = it.name, price = it.price, quantity = it.quantity, subtotal = it.subtotal,
            )
        }
        purchaseDao.upsertItems(itemEntities)

        items.forEach { productRepository.deductStockForSale(it.productId, it.quantity) }

        val dto = PurchaseDto(
            id = id, customerId = customerId, customerName = customerName,
            items = itemEntities.map { it.toDto() }, totalAmount = total, amountPaid = amountPaid,
            status = status, purchaseDate = now, createdByEmail = createdByEmail,
            isDeleted = false, version = 1, updatedAt = now, createdAt = now,
        )
        outboxWriter.enqueue("purchase", id, "upsert", dto)

        return id
    }

    /** Records a debt repayment against an existing purchase. Supports
     *  partial payments (essential feature) — status auto-updates to
     *  "paid" once the running total covers the sale amount. */
    suspend fun recordPayment(purchaseId: String, amount: Double, method: String, note: String?) {
        val current = purchaseDao.getById(purchaseId) ?: return
        val newPaid = current.amountPaid + amount
        val newStatus = when {
            newPaid >= current.totalAmount -> "paid"
            newPaid > 0 -> "partial"
            else -> "pending"
        }
        val now = Instant.now().toString()

        purchaseDao.upsert(current.copy(amountPaid = newPaid, status = newStatus, updatedAt = now, dirty = true))

        val paymentId = UUID.randomUUID().toString()
        val paymentEntity = PaymentEntity(
            id = paymentId, purchaseId = purchaseId, customerId = current.customerId,
            amount = amount, method = method, note = note, isDeleted = false,
            version = 1, updatedAt = now, createdAt = now, dirty = true,
        )
        paymentDao.upsert(paymentEntity)

        outboxWriter.enqueue(
            "payment", paymentId, "upsert",
            PaymentDto(
                id = paymentId, purchaseId = purchaseId, customerId = current.customerId,
                amount = amount, method = method, note = note, isDeleted = false,
                version = 1, updatedAt = now, createdAt = now,
            )
        )
    }
}

private fun PurchaseItemEntity.toDto() = PurchaseItemDto(
    id = id, productId = productId, name = name, price = price, quantity = quantity, subtotal = subtotal,
)
