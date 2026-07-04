package com.marnie.pos.ui.screens.purchase

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.marnie.pos.data.local.entities.CustomerEntity
import com.marnie.pos.data.repository.CartLine
import com.marnie.pos.data.repository.CustomerRepository
import com.marnie.pos.data.repository.ProductRepository
import com.marnie.pos.data.repository.PurchaseRepository
import com.marnie.pos.security.SessionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.NumberFormat
import javax.inject.Inject

data class NewPurchaseUiState(
    val cart: List<CartLine> = emptyList(),
    val selectedCustomer: CustomerEntity? = null,
    val amountPaid: String = "",
    val scanError: String? = null,
    val lastSaleSaved: Boolean = false,
) {
    val total: Double get() = cart.sumOf { it.subtotal }
}

@HiltViewModel
class NewPurchaseViewModel @Inject constructor(
    private val productRepository: ProductRepository,
    private val purchaseRepository: PurchaseRepository,
    customerRepository: CustomerRepository,
    private val sessionManager: SessionManager,
) : ViewModel() {

    val customers = customerRepository.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _uiState = MutableStateFlow(NewPurchaseUiState())
    val uiState: StateFlow<NewPurchaseUiState> = _uiState

    fun onBarcodeScanned(code: String) {
        viewModelScope.launch {
            val product = productRepository.findByBarcode(code)
            if (product == null) {
                _uiState.value = _uiState.value.copy(scanError = "No product found for code $code")
                return@launch
            }
            addToCart(product.id, product.name, product.price)
        }
    }

    fun addToCart(productId: String, name: String, price: Double) {
        val current = _uiState.value.cart.toMutableList()
        val idx = current.indexOfFirst { it.productId == productId }
        if (idx >= 0) {
            current[idx] = current[idx].copy(quantity = current[idx].quantity + 1)
        } else {
            current.add(CartLine(productId, name, price, 1))
        }
        _uiState.value = _uiState.value.copy(cart = current, scanError = null)
    }

    fun updateQuantity(productId: String, delta: Int) {
        val current = _uiState.value.cart.toMutableList()
        val idx = current.indexOfFirst { it.productId == productId }
        if (idx >= 0) {
            val newQty = current[idx].quantity + delta
            if (newQty <= 0) current.removeAt(idx) else current[idx] = current[idx].copy(quantity = newQty)
        }
        _uiState.value = _uiState.value.copy(cart = current)
    }

    fun selectCustomer(customer: CustomerEntity?) {
        _uiState.value = _uiState.value.copy(selectedCustomer = customer)
    }

    fun onAmountPaidChange(v: String) {
        _uiState.value = _uiState.value.copy(amountPaid = v)
    }

    fun checkout() {
        val state = _uiState.value
        if (state.cart.isEmpty()) return
        val paid = state.amountPaid.toDoubleOrNull() ?: 0.0
        viewModelScope.launch {
            purchaseRepository.recordSale(
                customerId = state.selectedCustomer?.id,
                customerName = state.selectedCustomer?.name,
                items = state.cart,
                amountPaid = paid,
                createdByEmail = sessionManager.session.value?.displayName,
            )
            _uiState.value = NewPurchaseUiState(lastSaleSaved = true)
        }
    }
}

@Composable
fun NewPurchaseScreen(viewModel: NewPurchaseViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsState()
    val customers by viewModel.customers.collectAsState()
    var showScanner by remember { mutableStateOf(false) }
    var showCustomerPicker by remember { mutableStateOf(false) }
    val currency = remember { NumberFormat.getCurrencyInstance() }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("New Sale", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Button(onClick = { showScanner = true }) {
                Icon(Icons.Filled.QrCodeScanner, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Scan")
            }
        }

        state.scanError?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        Spacer(Modifier.height(12.dp))

        OutlinedButton(onClick = { showCustomerPicker = true }, modifier = Modifier.fillMaxWidth()) {
            Text(state.selectedCustomer?.name ?: "Walk-in customer (tap to assign debt to someone)")
        }

        Spacer(Modifier.height(12.dp))

        if (state.cart.isEmpty()) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text("Cart is empty. Scan a barcode or add items to start a sale.")
            }
        } else {
            LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.cart, key = { it.productId }) { line ->
                    ElevatedCard(Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(line.name, fontWeight = FontWeight.SemiBold)
                                Text(currency.format(line.price), style = MaterialTheme.typography.bodySmall)
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = { viewModel.updateQuantity(line.productId, -1) }) { Icon(Icons.Filled.Remove, null) }
                                Text("${line.quantity}", modifier = Modifier.padding(horizontal = 4.dp))
                                IconButton(onClick = { viewModel.updateQuantity(line.productId, 1) }) { Icon(Icons.Filled.Add, null) }
                            }
                            Text(currency.format(line.subtotal), fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Divider()
        Spacer(Modifier.height(8.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Total", style = MaterialTheme.typography.titleMedium)
            Text(currency.format(state.total), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = state.amountPaid,
            onValueChange = viewModel::onAmountPaidChange,
            label = { Text("Amount paid now (leave 0 for full debt/utang)") },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = viewModel::checkout,
            enabled = state.cart.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) { Text("Complete Sale", style = MaterialTheme.typography.titleMedium) }
    }

    if (showScanner) {
        BarcodeScannerDialog(
            onDismiss = { showScanner = false },
            onDetected = { code -> viewModel.onBarcodeScanned(code) },
        )
    }

    if (showCustomerPicker) {
        AlertDialog(
            onDismissRequest = { showCustomerPicker = false },
            title = { Text("Select Customer") },
            text = {
                LazyColumn {
                    item {
                        TextButton(onClick = { viewModel.selectCustomer(null); showCustomerPicker = false }) { Text("Walk-in (no debt tracking)") }
                    }
                    items(customers, key = { it.id }) { c ->
                        TextButton(onClick = { viewModel.selectCustomer(c); showCustomerPicker = false }) { Text(c.name) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showCustomerPicker = false }) { Text("Close") } },
        )
    }
}
