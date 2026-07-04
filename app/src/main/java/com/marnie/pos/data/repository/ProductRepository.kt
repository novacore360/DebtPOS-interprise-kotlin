package com.marnie.pos.data.repository

import com.marnie.pos.data.local.dao.ProductDao
import com.marnie.pos.data.local.entities.ProductEntity
import com.marnie.pos.data.remote.dto.ProductDto
import com.marnie.pos.sync.OutboxWriter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProductRepository @Inject constructor(
    private val productDao: ProductDao,
    private val outboxWriter: OutboxWriter,
) {
    fun observeAll(): Flow<List<ProductEntity>> = productDao.observeAll()

    fun observeLowStock(): Flow<List<ProductEntity>> =
        productDao.observeAll().map { list -> list.filter { it.stock <= it.lowStockThreshold } }

    suspend fun findByBarcode(code: String): ProductEntity? = productDao.findByBarcode(code)

    suspend fun getById(id: String): ProductEntity? = productDao.getById(id)

    /** Writes to Room immediately (optimistic, works fully offline), then
     *  queues the same change for the backend. The UI never waits on network. */
    suspend fun upsert(
        id: String? = null,
        productCode: String?,
        name: String,
        category: String?,
        costPrice: Double,
        retailPrice: Double,
        price: Double,
        stock: Int,
        lowStockThreshold: Int,
    ) {
        val now = Instant.now().toString()
        val resolvedId = id ?: UUID.randomUUID().toString()
        val existing = id?.let { productDao.getById(it) }
        val entity = ProductEntity(
            id = resolvedId, productCode = productCode, name = name, category = category,
            costPrice = costPrice, retailPrice = retailPrice, price = price, stock = stock,
            lowStockThreshold = lowStockThreshold, isDeleted = false,
            version = (existing?.version ?: 0) + 1, updatedAt = now,
            createdAt = existing?.createdAt ?: now, dirty = true,
        )
        productDao.upsert(entity)
        outboxWriter.enqueue("product", resolvedId, "upsert", entity.toDto())
    }

    suspend fun delete(id: String) {
        val existing = productDao.getById(id) ?: return
        val updated = existing.copy(isDeleted = true, updatedAt = Instant.now().toString(), dirty = true)
        productDao.upsert(updated)
        outboxWriter.enqueue("product", id, "delete", updated.toDto())
    }

    /** Deducts stock locally the instant a sale is made (used by the
     *  purchase flow) so the next scan/reprint reflects reality immediately,
     *  even before the sync round-trip to Neon completes. */
    suspend fun deductStockForSale(productId: String, quantity: Int) {
        val existing = productDao.getById(productId) ?: return
        val updated = existing.copy(
            stock = (existing.stock - quantity).coerceAtLeast(0),
            updatedAt = Instant.now().toString(),
            dirty = true,
        )
        productDao.upsert(updated)
        outboxWriter.enqueue("product", productId, "upsert", updated.toDto())
    }
}

private fun ProductEntity.toDto() = ProductDto(
    id = id, productCode = productCode, name = name, category = category,
    costPrice = costPrice, retailPrice = retailPrice, price = price, stock = stock,
    lowStockThreshold = lowStockThreshold, isDeleted = isDeleted, version = version,
    updatedAt = updatedAt, createdAt = createdAt,
)
