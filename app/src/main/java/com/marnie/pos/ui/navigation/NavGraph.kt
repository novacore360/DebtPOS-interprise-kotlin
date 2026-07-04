package com.marnie.pos.ui.navigation

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import com.marnie.pos.security.SessionManager
import com.marnie.pos.ui.screens.customerdetail.CustomerDetailScreen
import com.marnie.pos.ui.screens.customers.CustomersScreen
import com.marnie.pos.ui.screens.dashboard.DashboardScreen
import com.marnie.pos.ui.screens.login.LoginScreen
import com.marnie.pos.ui.screens.products.ProductsScreen
import com.marnie.pos.ui.screens.purchase.NewPurchaseScreen
import com.marnie.pos.ui.screens.settings.SettingsScreen

/**
 * Single adaptive layout: a NavigationRail on medium/expanded widths
 * (tablets, foldables unfolded, landscape) and a bottom NavigationBar on
 * compact widths (phones) — same NavHost and screens underneath, so no
 * feature is phone-only or tablet-only.
 */
@OptIn(androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi::class)
@Composable
fun MarnieNavHost(sessionManager: SessionManager, widthSizeClass: WindowWidthSizeClass) {
    val navController = rememberNavController()
    val session by sessionManager.session.collectAsState()
    val isCompact = widthSizeClass == WindowWidthSizeClass.COMPACT

    if (session == null) {
        NavHost(navController = navController, startDestination = ROUTE_LOGIN) {
            loginGraph(navController)
            appGraph(navController)
        }
        return
    }

    if (isCompact) {
        Scaffold(
            bottomBar = { BottomNav(navController) },
        ) { padding ->
            Row(Modifier.fillMaxSize().padding(padding)) {
                NavHost(
                    navController = navController,
                    startDestination = Destination.Dashboard.route,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    appGraph(navController)
                }
            }
        }
    } else {
        Row(Modifier.fillMaxSize()) {
            SideNav(navController)
            NavHost(
                navController = navController,
                startDestination = Destination.Dashboard.route,
                modifier = Modifier.weight(1f).fillMaxSize(),
            ) {
                appGraph(navController)
            }
        }
    }
}

private fun androidx.navigation.NavGraphBuilder.loginGraph(navController: NavHostController) {
    composable(ROUTE_LOGIN) {
        LoginScreen(onLoginSuccess = {
            navController.navigate(Destination.Dashboard.route) {
                popUpTo(ROUTE_LOGIN) { inclusive = true }
            }
        })
    }
}

private fun androidx.navigation.NavGraphBuilder.appGraph(navController: NavHostController) {
    composable(Destination.Dashboard.route) { DashboardScreen() }
    composable(Destination.NewPurchase.route) { NewPurchaseScreen() }
    composable(Destination.Products.route) { ProductsScreen() }
    composable(Destination.Customers.route) {
        CustomersScreen(onOpenCustomer = { id -> navController.navigate(customerDetailRoute(id)) })
    }
    composable(ROUTE_CUSTOMER_DETAIL) {
        CustomerDetailScreen(onBack = { navController.popBackStack() })
    }
    composable(Destination.Settings.route) {
        SettingsScreen(onLoggedOut = {
            navController.navigate(ROUTE_LOGIN) { popUpTo(0) { inclusive = true } }
        })
    }
}

@Composable
private fun BottomNav(navController: NavHostController) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    NavigationBar {
        Destination.bottomItems.forEach { destination ->
            NavigationBarItem(
                selected = currentRoute == destination.route,
                onClick = { navigateSingleTop(navController, destination.route) },
                icon = { Icon(destination.icon, contentDescription = destination.label) },
                label = { Text(destination.label) },
            )
        }
    }
}

@Composable
private fun SideNav(navController: NavHostController) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    NavigationRail(modifier = Modifier.width(96.dp)) {
        Destination.bottomItems.forEach { destination ->
            NavigationRailItem(
                selected = currentRoute == destination.route,
                onClick = { navigateSingleTop(navController, destination.route) },
                icon = { Icon(destination.icon, contentDescription = destination.label) },
                label = { Text(destination.label) },
            )
        }
    }
}

private fun navigateSingleTop(navController: NavHostController, route: String) {
    navController.navigate(route) {
        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
