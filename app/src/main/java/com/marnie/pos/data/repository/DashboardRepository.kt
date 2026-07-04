package com.marnie.pos.data.repository

import com.marnie.pos.data.local.dao.CustomerDao
import com.marnie.pos.data.local.dao.ProductDao
import com.marnie.pos.data.local.dao.PurchaseDao
import kotlinx.coroutines.flow.combine
import javax.inject.Inject
import javax.inject.Singleton

data class DashboardStats(
    val totalProducts: Int,
    val totalCustomers: Int,
    val totalOutstandingDebt: Double,
    val todaysSales: Double,
    val lowStockCount: Int,
    val pendingSyncCount: Int,
)

@Singleton
class DashboardRepository @Inject constructor(
    private val productDao: ProductDao,
    private val customerDao: CustomerDao,
    private val purchaseDao: PurchaseDao,
) {
    fun observeStats() = combine(
        productDao.observeAll(),
        customerDao.observeAll(),
        purchaseDao.observeAll(),
    ) { products, customers, purchases ->
        val today = java.time.LocalDate.now()
        val todaysSales = purchases.filter {
            runCatching { java.time.Instant.parse(it.purchaseDate).atZone(java.time.ZoneId.systemDefault()).toLocalDate() == today }
                .getOrDefault(false)
        }.sumOf { it.totalAmount }

        DashboardStats(
            totalProducts = products.size,
            totalCustomers = customers.size,
            totalOutstandingDebt = purchases.sumOf { it.totalAmount - it.amountPaid },
            todaysSales = todaysSales,
            lowStockCount = products.count { it.stock <= it.lowStockThreshold },
            pendingSyncCount = 0, // combined in ViewModel with SyncManager.pendingOutboxCount
        )
    }
}
