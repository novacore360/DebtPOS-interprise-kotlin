package com.marnie.pos.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.PointOfSale
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector

sealed class Destination(val route: String, val label: String, val icon: ImageVector) {
    data object Dashboard : Destination("dashboard", "Dashboard", Icons.Filled.Dashboard)
    data object NewPurchase : Destination("new_purchase", "New Sale", Icons.Filled.PointOfSale)
    data object Products : Destination("products", "Products", Icons.Filled.Inventory2)
    data object Customers : Destination("customers", "Customers", Icons.Filled.People)
    data object Settings : Destination("settings", "Settings", Icons.Filled.Settings)

    companion object {
        val bottomItems = listOf(Dashboard, NewPurchase, Products, Customers, Settings)
    }
}

const val ROUTE_LOGIN = "login"
const val ROUTE_CUSTOMER_DETAIL = "customer_detail/{customerId}"
fun customerDetailRoute(customerId: String) = "customer_detail/$customerId"
