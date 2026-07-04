package com.marnie.pos.data.local.dao

import androidx.room.*
import com.marnie.pos.data.local.entities.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ProductDao {
    @Query("SELECT * FROM products WHERE isDeleted = 0 ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<ProductEntity>>

    @Query("SELECT * FROM products WHERE id = :id")
    suspend fun getById(id: String): ProductEntity?

    @Query("SELECT * FROM products WHERE productCode = :code AND isDeleted = 0 LIMIT 1")
    suspend fun findByBarcode(code: String): ProductEntity?

    @Query("SELECT MAX(updatedAt) FROM products")
    suspend fun latestUpdatedAt(): String?

    @Upsert
    suspend fun upsertAll(items: List<ProductEntity>)

    @Upsert
    suspend fun upsert(item: ProductEntity)

    @Query("UPDATE products SET stock = stock - :qty WHERE id = :id")
    suspend fun decrementStock(id: String, qty: Int)
}

@Dao
interface CustomerDao {
    @Query("SELECT * FROM customers WHERE isDeleted = 0 ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<CustomerEntity>>

    @Query("SELECT * FROM customers WHERE id = :id")
    suspend fun getById(id: String): CustomerEntity?

    @Query("SELECT MAX(updatedAt) FROM customers")
    suspend fun latestUpdatedAt(): String?

    @Upsert
    suspend fun upsertAll(items: List<CustomerEntity>)

    @Upsert
    suspend fun upsert(item: CustomerEntity)
}

@Dao
interface PurchaseDao {
    @Query("SELECT * FROM purchases WHERE isDeleted = 0 ORDER BY purchaseDate DESC")
    fun observeAll(): Flow<List<PurchaseEntity>>

    @Query("SELECT * FROM purchases WHERE customerId = :customerId AND isDeleted = 0 ORDER BY purchaseDate DESC")
    fun observeByCustomer(customerId: String): Flow<List<PurchaseEntity>>

    @Query("SELECT * FROM purchases WHERE id = :id")
    suspend fun getById(id: String): PurchaseEntity?

    @Query("SELECT * FROM purchase_items WHERE purchaseId = :purchaseId")
    suspend fun itemsFor(purchaseId: String): List<PurchaseItemEntity>

    @Query("SELECT * FROM purchase_items WHERE purchaseId IN (:purchaseIds)")
    suspend fun itemsForPurchases(purchaseIds: List<String>): List<PurchaseItemEntity>

    @Query("SELECT MAX(updatedAt) FROM purchases")
    suspend fun latestUpdatedAt(): String?

    @Upsert
    suspend fun upsertAll(items: List<PurchaseEntity>)

    @Upsert
    suspend fun upsert(item: PurchaseEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertItems(items: List<PurchaseItemEntity>)

    @Query("DELETE FROM purchase_items WHERE purchaseId = :purchaseId")
    suspend fun clearItems(purchaseId: String)
}

@Dao
interface PaymentDao {
    @Query("SELECT * FROM payments WHERE purchaseId = :purchaseId AND isDeleted = 0 ORDER BY createdAt DESC")
    fun observeForPurchase(purchaseId: String): Flow<List<PaymentEntity>>

    @Query("SELECT MAX(updatedAt) FROM payments")
    suspend fun latestUpdatedAt(): String?

    @Upsert
    suspend fun upsertAll(items: List<PaymentEntity>)

    @Upsert
    suspend fun upsert(item: PaymentEntity)
}

@Dao
interface OutboxDao {
    @Query("SELECT * FROM sync_outbox ORDER BY seq ASC")
    suspend fun getAll(): List<OutboxEntity>

    @Query("SELECT COUNT(*) FROM sync_outbox")
    fun observePendingCount(): Flow<Int>

    @Insert
    suspend fun enqueue(item: OutboxEntity)

    @Query("DELETE FROM sync_outbox WHERE seq = :seq")
    suspend fun remove(seq: Long)

    @Query("UPDATE sync_outbox SET attempts = attempts + 1 WHERE seq = :seq")
    suspend fun incrementAttempts(seq: Long)

    @Query("DELETE FROM sync_outbox WHERE attempts >= :maxAttempts")
    suspend fun purgeExhausted(maxAttempts: Int = 10)
}
