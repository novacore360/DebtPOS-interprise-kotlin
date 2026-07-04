package com.marnie.pos.data.local.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "products")
data class ProductEntity(
    @PrimaryKey val id: String,
    val productCode: String?,
    val name: String,
    val category: String?,
    val costPrice: Double,
    val retailPrice: Double,
    val price: Double,
    val stock: Int,
    val lowStockThreshold: Int,
    val isDeleted: Boolean,
    val version: Long,
    val updatedAt: String,
    val createdAt: String,
    val dirty: Boolean = false, // true = local change not yet confirmed by server
)

@Entity(tableName = "customers")
data class CustomerEntity(
    @PrimaryKey val id: String,
    val name: String,
    val phone: String?,
    val email: String?,
    val hasAccessPin: Boolean,
    val isDeleted: Boolean,
    val version: Long,
    val updatedAt: String,
    val createdAt: String,
    val dirty: Boolean = false,
)

@Entity(tableName = "purchases")
data class PurchaseEntity(
    @PrimaryKey val id: String,
    val customerId: String?,
    val customerName: String?,
    val totalAmount: Double,
    val amountPaid: Double,
    val status: String,
    val purchaseDate: String,
    val createdByEmail: String?,
    val isDeleted: Boolean,
    val version: Long,
    val updatedAt: String,
    val createdAt: String,
    val dirty: Boolean = false,
)

@Entity(tableName = "purchase_items")
data class PurchaseItemEntity(
    @PrimaryKey val id: String,
    val purchaseId: String,
    val productId: String?,
    val name: String,
    val price: Double,
    val quantity: Int,
    val subtotal: Double,
)

@Entity(tableName = "payments")
data class PaymentEntity(
    @PrimaryKey val id: String,
    val purchaseId: String,
    val customerId: String?,
    val amount: Double,
    val method: String,
    val note: String?,
    val isDeleted: Boolean,
    val version: Long,
    val updatedAt: String,
    val createdAt: String,
    val dirty: Boolean = false,
)

/**
 * Outbox pattern: every local write (online or offline) is first committed
 * to Room AND appended here. SyncWorker drains this queue to the backend
 * whenever connectivity is available, in FIFO order, so purchases made while
 * offline sync automatically the moment the device reconnects — no user
 * action required.
 */
@Entity(tableName = "sync_outbox")
data class OutboxEntity(
    @PrimaryKey(autoGenerate = true) val seq: Long = 0,
    val entity: String,          // "product" | "customer" | "purchase" | "payment"
    val entityId: String,
    val op: String,              // "upsert" | "delete"
    val payloadJson: String,     // serialized DTO at time of the mutation
    val clientUpdatedAt: String,
    val createdAt: Long = System.currentTimeMillis(),
    val attempts: Int = 0,
)
