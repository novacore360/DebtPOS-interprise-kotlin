package com.marnie.pos.ui.screens.customerdetail

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.marnie.pos.data.local.entities.PurchaseEntity
import com.marnie.pos.data.repository.CustomerRepository
import com.marnie.pos.data.repository.PurchaseRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.NumberFormat
import javax.inject.Inject

@HiltViewModel
class CustomerDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val customerRepository: CustomerRepository,
    private val purchaseRepository: PurchaseRepository,
) : ViewModel() {
    private val customerId: String = checkNotNull(savedStateHandle["customerId"])

    val customer = kotlinx.coroutines.flow.flow { emit(customerRepository.getById(customerId)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val purchases = purchaseRepository.observeByCustomer(customerId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun recordPayment(purchaseId: String, amount: Double) {
        viewModelScope.launch { purchaseRepository.recordPayment(purchaseId, amount, "cash", null) }
    }
}

@Composable
fun CustomerDetailScreen(viewModel: CustomerDetailViewModel = hiltViewModel(), onBack: () -> Unit) {
    val customer by viewModel.customer.collectAsState()
    val purchases by viewModel.purchases.collectAsState()
    val currency = remember { NumberFormat.getCurrencyInstance() }
    var payingFor by remember { mutableStateOf<PurchaseEntity?>(null) }

    val outstanding = purchases.sumOf { it.totalAmount - it.amountPaid }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(customer?.name ?: "Customer") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(androidx.compose.material.icons.Icons.Filled.ArrowBack, "Back")
                    }
                },
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Total Outstanding Debt", style = MaterialTheme.typography.labelMedium)
                    Text(
                        currency.format(outstanding),
                        style = MaterialTheme.typography.headlineMedium,
                        color = if (outstanding > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary,
                        fontWeight = FontWeight.Bold,
                    )
                    customer?.phone?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
            Spacer(Modifier.height(16.dp))
            Text("Purchase History", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))

            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(purchases, key = { it.id }) { purchase ->
                    PurchaseRow(purchase, currency, onRecordPayment = { payingFor = purchase })
                }
            }
        }
    }

    payingFor?.let { purchase ->
        RecordPaymentDialog(
            purchase = purchase,
            currency = currency,
            onDismiss = { payingFor = null },
            onConfirm = { amount -> viewModel.recordPayment(purchase.id, amount); payingFor = null },
        )
    }
}

@Composable
private fun PurchaseRow(purchase: PurchaseEntity, currency: NumberFormat, onRecordPayment: () -> Unit) {
    val remaining = purchase.totalAmount - purchase.amountPaid
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(currency.format(purchase.totalAmount), fontWeight = FontWeight.SemiBold)
                StatusBadge(purchase.status)
            }
            Text("Paid: ${currency.format(purchase.amountPaid)}  •  Remaining: ${currency.format(remaining)}", style = MaterialTheme.typography.bodySmall)
            if (remaining > 0) {
                Spacer(Modifier.height(6.dp))
                TextButton(onClick = onRecordPayment) { Text("Record Payment") }
            }
        }
    }
}

@Composable
private fun StatusBadge(status: String) {
    val color = when (status) {
        "paid" -> MaterialTheme.colorScheme.secondary
        "partial" -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.error
    }
    AssistChip(onClick = {}, label = { Text(status.replaceFirstChar { it.uppercase() }) },
        colors = AssistChipDefaults.assistChipColors(labelColor = color))
}

@Composable
private fun RecordPaymentDialog(purchase: PurchaseEntity, currency: NumberFormat, onDismiss: () -> Unit, onConfirm: (Double) -> Unit) {
    var amountText by remember { mutableStateOf("") }
    val remaining = purchase.totalAmount - purchase.amountPaid

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Record Payment") },
        text = {
            Column {
                Text("Remaining balance: ${currency.format(remaining)}")
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(amountText, { amountText = it }, label = { Text("Amount") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(onClick = { amountText.toDoubleOrNull()?.let(onConfirm) }, enabled = (amountText.toDoubleOrNull() ?: 0.0) > 0) {
                Text("Confirm")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
