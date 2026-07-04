package com.marnie.backend.db

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp

// NOTE: This is a single-admin, single-store app. There is intentionally no
// `Stores` or `AppUsers` table — the one operator's credentials live in
// environment variables (see AuthRoutes.kt), not the database.

object RefreshTokens : Table("refresh_tokens") {
    val id = uuid("id")
    val tokenHash = text("token_hash")
    val deviceId = text("device_id")
    val expiresAt = timestamp("expires_at")
    val revoked = bool("revoked")
    val createdAt = timestamp("created_at")
    override val primaryKey = PrimaryKey(id)
}

object Products : Table("products") {
    val id = uuid("id")
    val productCode = text("product_code").nullable()
    val name = text("name")
    val category = text("category").nullable()
    val costPrice = decimal("cost_price", 12, 2)
    val retailPrice = decimal("retail_price", 12, 2)
    val price = decimal("price", 12, 2)
    val stock = integer("stock")
    val lowStockThreshold = integer("low_stock_threshold")
    val isDeleted = bool("is_deleted")
    val version = long("version")
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
    override val primaryKey = PrimaryKey(id)
}

object Customers : Table("customers") {
    val id = uuid("id")
    val name = text("name")
    val phone = text("phone").nullable()
    val email = text("email").nullable()
    val accessPinHash = text("access_pin_hash").nullable()
    val isDeleted = bool("is_deleted")
    val version = long("version")
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
    override val primaryKey = PrimaryKey(id)
}

object Purchases : Table("purchases") {
    val id = uuid("id")
    val customerId = uuid("customer_id").nullable()
    val customerName = text("customer_name").nullable()
    val totalAmount = decimal("total_amount", 12, 2)
    val amountPaid = decimal("amount_paid", 12, 2)
    val status = text("status")
    val purchaseDate = timestamp("purchase_date")
    val isDeleted = bool("is_deleted")
    val version = long("version")
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
    override val primaryKey = PrimaryKey(id)
}

object PurchaseItems : Table("purchase_items") {
    val id = uuid("id")
    val purchaseId = uuid("purchase_id")
    val productId = uuid("product_id").nullable()
    val name = text("name")
    val price = decimal("price", 12, 2)
    val quantity = integer("quantity")
    val subtotal = decimal("subtotal", 12, 2)
    override val primaryKey = PrimaryKey(id)
}

object Payments : Table("payments") {
    val id = uuid("id")
    val purchaseId = uuid("purchase_id")
    val customerId = uuid("customer_id").nullable()
    val amount = decimal("amount", 12, 2)
    val method = text("method")
    val note = text("note").nullable()
    val isDeleted = bool("is_deleted")
    val version = long("version")
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
    override val primaryKey = PrimaryKey(id)
}

object AuditLog : Table("audit_log") {
    val id = long("id").autoIncrement()
    val action = text("action")
    val entityType = text("entity_type")
    val entityId = uuid("entity_id").nullable()
    val metadata = text("metadata").nullable()
    val ipAddress = text("ip_address").nullable()
    val deviceId = text("device_id").nullable()
    val createdAt = timestamp("created_at")
    override val primaryKey = PrimaryKey(id)
}
