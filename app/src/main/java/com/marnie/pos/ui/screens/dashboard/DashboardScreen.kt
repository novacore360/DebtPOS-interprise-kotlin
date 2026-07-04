package com.marnie.pos.ui.screens.dashboard

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.marnie.pos.data.repository.DashboardRepository
import com.marnie.pos.data.repository.DashboardStats
import com.marnie.pos.sync.SyncManager
import com.marnie.pos.sync.SyncStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.NumberFormat
import javax.inject.Inject

@HiltViewModel
class DashboardViewModel @Inject constructor(
    dashboardRepository: DashboardRepository,
    val syncManager: SyncManager,
) : ViewModel() {

    val stats = combine(dashboardRepository.observeStats(), syncManager.pendingOutboxCount) { stats, pending ->
        stats.copy(pendingSyncCount = pending)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DashboardStats(0, 0, 0.0, 0.0, 0, 0))

    val syncStatus = syncManager.status

    fun refresh() { viewModelScope.launch { syncManager.syncNow() } }
}

@Composable
fun DashboardScreen(viewModel: DashboardViewModel = hiltViewModel()) {
    val stats by viewModel.stats.collectAsState()
    val syncStatus by viewModel.syncStatus.collectAsState()
    val currency = remember { NumberFormat.getCurrencyInstance() }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Dashboard", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            SyncStatusChip(syncStatus, stats.pendingSyncCount, onClick = viewModel::refresh)
        }

        Spacer(Modifier.height(16.dp))

        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 160.dp), // responsive: 2 cols on phones, more on tablets
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(
                listOf(
                    Triple("Today's Sales", currency.format(stats.todaysSales), MaterialTheme.colorScheme.primary),
                    Triple("Outstanding Debt", currency.format(stats.totalOutstandingDebt), MaterialTheme.colorScheme.error),
                    Triple("Products", stats.totalProducts.toString(), MaterialTheme.colorScheme.secondary),
                    Triple("Customers", stats.totalCustomers.toString(), MaterialTheme.colorScheme.secondary),
                    Triple("Low Stock Alerts", stats.lowStockCount.toString(), MaterialTheme.colorScheme.tertiary),
                )
            ) { (label, value, color) ->
                StatCard(label, value, color)
            }
        }
    }
}

@Composable
private fun StatCard(label: String, value: String, color: androidx.compose.ui.graphics.Color) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(6.dp))
            Text(value, style = MaterialTheme.typography.headlineSmall, color = color, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun SyncStatusChip(status: SyncStatus, pendingCount: Int, onClick: () -> Unit) {
    val (icon, text) = when (status) {
        is SyncStatus.Syncing -> Icons.Filled.CloudSync to "Syncing…"
        is SyncStatus.Error -> Icons.Filled.CloudOff to "Offline ($pendingCount pending)"
        else -> if (pendingCount > 0) Icons.Filled.CloudSync to "$pendingCount pending" else Icons.Filled.CloudDone to "Synced"
    }
    AssistChip(onClick = onClick, label = { Text(text) }, leadingIcon = { Icon(icon, null, modifier = Modifier.size(18.dp)) })
}
