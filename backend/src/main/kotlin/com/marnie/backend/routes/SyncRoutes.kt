package com.marnie.backend.routes

import com.marnie.backend.db.*
import com.marnie.backend.models.*
import com.marnie.backend.plugins.DatabaseFactory
import com.marnie.backend.plugins.UserPrincipal
import com.marnie.backend.security.PasswordHasher
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.jetbrains.exposed.sql.*
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

private fun Instant.iso(): String = toString()

fun Route.syncRoutes() {
    route("/api/v1/sync") {
        authenticate("auth-jwt") {

            // ---- PULL: everything changed since `since` (ISO instant) ----
            get("/pull") {
                val principal = call.principal<UserPrincipal>()!!
                val since = call.request.queryParameters["since"]?.let {
                    runCatching { Instant.parse(it) }.getOrNull()
                } ?: Instant.EPOCH

                val data = DatabaseFactory.dbTransaction {
                    val products = Products.selectAll().where {
                        Products.updatedAt greater since
                    }.map {
                        ProductDto(
                            id = it[Products.id].toString(),
                            productCode = it[Products.productCode],
                            name = it[Products.name],
                            category = it[Products.category],
                            costPrice = it[Products.costPrice].toDouble(),
                            retailPrice = it[Products.retailPrice].toDouble(),
                            price = it[Products.price].toDouble(),
                            stock = it[Products.stock],
                            lowStockThreshold = it[Products.lowStockThreshold],
                            isDeleted = it[Products.isDeleted],
                            version = it[Products.version],
                            updatedAt = it[Products.updatedAt].iso(),
                            createdAt = it[Products.createdAt].iso(),
                        )
                    }

                    val customers = Customers.selectAll().where {
                        Customers.updatedAt greater since
                    }.map {
                        CustomerDto(
                            id = it[Customers.id].toString(),
                            name = it[Customers.name],
                            phone = it[Customers.phone],
                            email = it[Customers.email],
                            hasAccessPin = it[Customers.accessPinHash] != null,
                            isDeleted = it[Customers.isDeleted],
                            version = it[Customers.version],
                            updatedAt = it[Customers.updatedAt].iso(),
                            createdAt = it[Customers.createdAt].iso(),
                        )
                    }

                    val purchaseRows = Purchases.selectAll().where {
                        Purchases.updatedAt greater since
                    }.toList()

                    val purchaseIds = purchaseRows.map { it[Purchases.id] }
                    val itemsByPurchase = if (purchaseIds.isEmpty()) emptyMap() else
                        PurchaseItems.selectAll().where { PurchaseItems.purchaseId inList purchaseIds }
                            .groupBy({ it[PurchaseItems.purchaseId] }) {
                                PurchaseItemDto(
                                    id = it[PurchaseItems.id].toString(),
                                    productId = it[PurchaseItems.productId]?.toString(),
                                    name = it[PurchaseItems.name],
                                    price = it[PurchaseItems.price].toDouble(),
                                    quantity = it[PurchaseItems.quantity],
                                    subtotal = it[PurchaseItems.subtotal].toDouble(),
                                )
                            }

                    val purchases = purchaseRows.map {
                        PurchaseDto(
                            id = it[Purchases.id].toString(),
                            customerId = it[Purchases.customerId]?.toString(),
                            customerName = it[Purchases.customerName],
                            items = itemsByPurchase[it[Purchases.id]] ?: emptyList(),
                            totalAmount = it[Purchases.totalAmount].toDouble(),
                            amountPaid = it[Purchases.amountPaid].toDouble(),
                            status = it[Purchases.status],
                            purchaseDate = it[Purchases.purchaseDate].iso(),
                            isDeleted = it[Purchases.isDeleted],
                            version = it[Purchases.version],
                            updatedAt = it[Purchases.updatedAt].iso(),
                            createdAt = it[Purchases.createdAt].iso(),
                        )
                    }

                    val payments = Payments.selectAll().where {
                        Payments.updatedAt greater since
                    }.map {
                        PaymentDto(
                            id = it[Payments.id].toString(),
                            purchaseId = it[Payments.purchaseId].toString(),
                            customerId = it[Payments.customerId]?.toString(),
                            amount = it[Payments.amount].toDouble(),
                            method = it[Payments.method],
                            note = it[Payments.note],
                            isDeleted = it[Payments.isDeleted],
                            version = it[Payments.version],
                            updatedAt = it[Payments.updatedAt].iso(),
                            createdAt = it[Payments.createdAt].iso(),
                        )
                    }

                    SyncPullResponse(Instant.now().iso(), products, customers, purchases, payments)
                }

                call.respond(data)
            }

            // ---- PUSH: apply the client's offline mutation queue ----
            post("/push") {
                val principal = call.principal<UserPrincipal>()!!
                val req = call.receive<SyncPushRequest>()

                val results = DatabaseFactory.dbTransaction {
                    req.mutations.map { m -> applyMutation(m, principal) }
                }

                call.respond(SyncPushResponse(results, Instant.now().iso()))
            }
        }
    }
}

private fun applyMutation(m: MutationDto, principal: UserPrincipal): MutationResult {
    val clientTime = runCatching { Instant.parse(m.clientUpdatedAt) }.getOrElse { Instant.now() }
    return try {
        when (m.entity) {
            "product" -> applyProduct(m, principal, clientTime)
            "customer" -> applyCustomer(m, principal, clientTime)
            "purchase" -> applyPurchase(m, principal, clientTime)
            "payment" -> applyPayment(m, principal, clientTime)
            else -> MutationResult(m.entity, "?", "error", "Unknown entity")
        }
    } catch (e: Exception) {
        MutationResult(m.entity, m.product?.id ?: m.customer?.id ?: m.purchase?.id ?: m.payment?.id ?: "?", "error", e.message)
    }
}

private fun applyProduct(m: MutationDto, principal: UserPrincipal, clientTime: Instant): MutationResult {
    val dto = m.product ?: return MutationResult("product", "?", "error", "Missing product payload")
    val id = UUID.fromString(dto.id)
    val existing = Products.selectAll().where { Products.id eq id }.singleOrNull()

    if (m.op == "delete") {
        if (existing != null) Products.update({ Products.id eq id }) { it[isDeleted] = true }
        return MutationResult("product", dto.id, "applied")
    }
    if (existing != null && existing[Products.updatedAt].isAfter(clientTime)) {
        return MutationResult("product", dto.id, "conflict", "Server has a newer version")
    }
    if (existing == null) {
        Products.insert {
            it[Products.id] = id
            it[productCode] = dto.productCode
            it[name] = dto.name
            it[category] = dto.category
            it[costPrice] = BigDecimal.valueOf(dto.costPrice)
            it[retailPrice] = BigDecimal.valueOf(dto.retailPrice)
            it[price] = BigDecimal.valueOf(dto.price)
            it[stock] = dto.stock
            it[lowStockThreshold] = dto.lowStockThreshold
            it[isDeleted] = dto.isDeleted
            it[version] = 1
            it[createdAt] = clientTime
            it[updatedAt] = clientTime
        }
    } else {
        Products.update({ Products.id eq id }) {
            it[productCode] = dto.productCode
            it[name] = dto.name
            it[category] = dto.category
            it[costPrice] = BigDecimal.valueOf(dto.costPrice)
            it[retailPrice] = BigDecimal.valueOf(dto.retailPrice)
            it[price] = BigDecimal.valueOf(dto.price)
            it[stock] = dto.stock
            it[lowStockThreshold] = dto.lowStockThreshold
            it[isDeleted] = dto.isDeleted
        }
    }
    return MutationResult("product", dto.id, "applied")
}

private fun applyCustomer(m: MutationDto, principal: UserPrincipal, clientTime: Instant): MutationResult {
    val dto = m.customer ?: return MutationResult("customer", "?", "error", "Missing customer payload")
    val id = UUID.fromString(dto.id)
    val existing = Customers.selectAll().where { Customers.id eq id }.singleOrNull()

    if (m.op == "delete") {
        if (existing != null) Customers.update({ Customers.id eq id }) { it[isDeleted] = true }
        return MutationResult("customer", dto.id, "applied")
    }
    if (existing != null && existing[Customers.updatedAt].isAfter(clientTime)) {
        return MutationResult("customer", dto.id, "conflict", "Server has a newer version")
    }
    val pinHash = m.customerPin?.let { PasswordHasher.hash(it) }
    if (existing == null) {
        Customers.insert {
            it[Customers.id] = id
            it[name] = dto.name
            it[phone] = dto.phone
            it[email] = dto.email
            it[accessPinHash] = pinHash
            it[isDeleted] = dto.isDeleted
            it[version] = 1
            it[createdAt] = clientTime
            it[updatedAt] = clientTime
        }
    } else {
        Customers.update({ Customers.id eq id }) {
            it[name] = dto.name
            it[phone] = dto.phone
            it[email] = dto.email
            if (pinHash != null) it[accessPinHash] = pinHash
            it[isDeleted] = dto.isDeleted
        }
    }
    return MutationResult("customer", dto.id, "applied")
}

private fun applyPurchase(m: MutationDto, principal: UserPrincipal, clientTime: Instant): MutationResult {
    val dto = m.purchase ?: return MutationResult("purchase", "?", "error", "Missing purchase payload")
    val id = UUID.fromString(dto.id)
    val existing = Purchases.selectAll().where { Purchases.id eq id }.singleOrNull()

    if (m.op == "delete") {
        if (existing != null) Purchases.update({ Purchases.id eq id }) { it[isDeleted] = true }
        return MutationResult("purchase", dto.id, "applied")
    }
    if (existing != null && existing[Purchases.updatedAt].isAfter(clientTime)) {
        return MutationResult("purchase", dto.id, "conflict", "Server has a newer version")
    }

    val purchaseDate = runCatching { Instant.parse(dto.purchaseDate) }.getOrDefault(clientTime)
    val customerUuid = dto.customerId?.let { runCatching { UUID.fromString(it) }.getOrNull() }

    if (existing == null) {
        Purchases.insert {
            it[Purchases.id] = id
            it[customerId] = customerUuid
            it[customerName] = dto.customerName
            it[totalAmount] = BigDecimal.valueOf(dto.totalAmount)
            it[amountPaid] = BigDecimal.valueOf(dto.amountPaid)
            it[status] = dto.status
            it[Purchases.purchaseDate] = purchaseDate
            it[isDeleted] = dto.isDeleted
            it[version] = 1
            it[createdAt] = clientTime
            it[updatedAt] = clientTime
        }
        // Reduce stock for each line item — mirrors the old app's
        // "deduct stock on sale" behaviour, done atomically server-side now.
        dto.items.forEach { item ->
            val productUuid = item.productId?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            PurchaseItems.insert {
                it[PurchaseItems.id] = UUID.fromString(item.id)
                it[purchaseId] = id
                it[productId] = productUuid
                it[name] = item.name
                it[price] = BigDecimal.valueOf(item.price)
                it[quantity] = item.quantity
                it[subtotal] = BigDecimal.valueOf(item.subtotal)
            }
            if (productUuid != null) {
                Products.update({ Products.id eq productUuid }) {
                    it.update(Products.stock, Products.stock - item.quantity)
                }
            }
        }
    } else {
        Purchases.update({ Purchases.id eq id }) {
            it[customerId] = customerUuid
            it[customerName] = dto.customerName
            it[totalAmount] = BigDecimal.valueOf(dto.totalAmount)
            it[amountPaid] = BigDecimal.valueOf(dto.amountPaid)
            it[status] = dto.status
            it[isDeleted] = dto.isDeleted
        }
    }
    return MutationResult("purchase", dto.id, "applied")
}

private fun applyPayment(m: MutationDto, principal: UserPrincipal, clientTime: Instant): MutationResult {
    val dto = m.payment ?: return MutationResult("payment", "?", "error", "Missing payment payload")
    val id = UUID.fromString(dto.id)
    val existing = Payments.selectAll().where { Payments.id eq id }.singleOrNull()

    if (m.op == "delete") {
        if (existing != null) Payments.update({ Payments.id eq id }) { it[isDeleted] = true }
        return MutationResult("payment", dto.id, "applied")
    }
    if (existing != null && existing[Payments.updatedAt].isAfter(clientTime)) {
        return MutationResult("payment", dto.id, "conflict", "Server has a newer version")
    }
    val purchaseUuid = UUID.fromString(dto.purchaseId)
    val customerUuid = dto.customerId?.let { runCatching { UUID.fromString(it) }.getOrNull() }

    if (existing == null) {
        Payments.insert {
            it[Payments.id] = id
            it[purchaseId] = purchaseUuid
            it[customerId] = customerUuid
            it[amount] = BigDecimal.valueOf(dto.amount)
            it[method] = dto.method
            it[note] = dto.note
            it[isDeleted] = dto.isDeleted
            it[version] = 1
            it[createdAt] = clientTime
            it[updatedAt] = clientTime
        }
        // Bump amount_paid on the parent purchase and flip status automatically.
        val purchase = Purchases.selectAll().where { Purchases.id eq purchaseUuid }.singleOrNull()
        if (purchase != null) {
            val newPaid = purchase[Purchases.amountPaid].toDouble() + dto.amount
            val total = purchase[Purchases.totalAmount].toDouble()
            val newStatus = when {
                newPaid >= total -> "paid"
                newPaid > 0 -> "partial"
                else -> "pending"
            }
            Purchases.update({ Purchases.id eq purchaseUuid }) {
                it[amountPaid] = BigDecimal.valueOf(newPaid)
                it[status] = newStatus
            }
        }
    } else {
        Payments.update({ Payments.id eq id }) {
            it[amount] = BigDecimal.valueOf(dto.amount)
            it[method] = dto.method
            it[note] = dto.note
            it[isDeleted] = dto.isDeleted
        }
    }
    return MutationResult("payment", dto.id, "applied")
}
