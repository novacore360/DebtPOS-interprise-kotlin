package com.marnie.backend.models

import kotlinx.serialization.Serializable

@Serializable
data class LoginRequest(val email: String, val password: String, val deviceId: String, val totpCode: String? = null)

@Serializable
data class AuthResponse(
    val accessToken: String,
    val refreshToken: String,
    val userId: String,
    val displayName: String?,
    val expiresInSeconds: Long = 900,
)

@Serializable
data class RefreshRequest(val refreshToken: String, val deviceId: String)

@Serializable
data class ProductDto(
    val id: String,
    val productCode: String? = null,
    val name: String,
    val category: String? = null,
    val costPrice: Double = 0.0,
    val retailPrice: Double = 0.0,
    val price: Double = 0.0,
    val stock: Int = 0,
    val lowStockThreshold: Int = 5,
    val isDeleted: Boolean = false,
    val version: Long = 0,
    val updatedAt: String? = null,
    val createdAt: String? = null,
)

@Serializable
data class CustomerDto(
    val id: String,
    val name: String,
    val phone: String? = null,
    val email: String? = null,
    val hasAccessPin: Boolean = false,
    val isDeleted: Boolean = false,
    val version: Long = 0,
    val updatedAt: String? = null,
    val createdAt: String? = null,
)

@Serializable
data class PurchaseItemDto(
    val id: String,
    val productId: String? = null,
    val name: String,
    val price: Double,
    val quantity: Int,
    val subtotal: Double,
)

@Serializable
data class PurchaseDto(
    val id: String,
    val customerId: String? = null,
    val customerName: String? = null,
    val items: List<PurchaseItemDto> = emptyList(),
    val totalAmount: Double = 0.0,
    val amountPaid: Double = 0.0,
    val status: String = "pending",
    val purchaseDate: String,
    val createdByEmail: String? = null,
    val isDeleted: Boolean = false,
    val version: Long = 0,
    val updatedAt: String? = null,
    val createdAt: String? = null,
)

@Serializable
data class PaymentDto(
    val id: String,
    val purchaseId: String,
    val customerId: String? = null,
    val amount: Double,
    val method: String = "cash",
    val note: String? = null,
    val isDeleted: Boolean = false,
    val version: Long = 0,
    val updatedAt: String? = null,
    val createdAt: String? = null,
)

@Serializable
data class SyncPullResponse(
    val serverTime: String,
    val products: List<ProductDto>,
    val customers: List<CustomerDto>,
    val purchases: List<PurchaseDto>,
    val payments: List<PaymentDto>,
)

@Serializable
data class MutationDto(
    val entity: String,        // "product" | "customer" | "purchase" | "payment"
    val op: String,            // "upsert" | "delete"
    val clientUpdatedAt: String,
    val product: ProductDto? = null,
    val customer: CustomerDto? = null,
    val purchase: PurchaseDto? = null,
    val payment: PaymentDto? = null,
    val customerPin: String? = null, // plaintext PIN, set only when creating/rotating; hashed server-side, never stored/echoed back
)

@Serializable
data class MutationResult(
    val entity: String,
    val id: String,
    val status: String,        // "applied" | "conflict" | "error"
    val message: String? = null,
)

@Serializable
data class SyncPushRequest(val mutations: List<MutationDto>)

@Serializable
data class SyncPushResponse(val results: List<MutationResult>, val serverTime: String)

@Serializable
data class ErrorResponse(val error: String)
