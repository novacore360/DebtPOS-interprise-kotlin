package com.marnie.pos.ui.screens.products

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.marnie.pos.data.local.entities.ProductEntity
import com.marnie.pos.data.repository.ProductRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.NumberFormat
import javax.inject.Inject

@HiltViewModel
class ProductsViewModel @Inject constructor(private val repository: ProductRepository) : ViewModel() {
    val products = repository.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun save(id: String?, code: String?, name: String, category: String?, cost: Double, retail: Double, price: Double, stock: Int, lowStock: Int) {
        viewModelScope.launch {
            repository.upsert(id, code, name, category, cost, retail, price, stock, lowStock)
        }
    }

    fun delete(id: String) { viewModelScope.launch { repository.delete(id) } }
}

@Composable
fun ProductsScreen(viewModel: ProductsViewModel = hiltViewModel()) {
    val products by viewModel.products.collectAsState()
    var showDialog by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<ProductEntity?>(null) }
    val currency = remember { NumberFormat.getCurrencyInstance() }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = { editing = null; showDialog = true }) { Icon(Icons.Filled.Add, "Add product") }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            Text("Products", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))

            if (products.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No products yet. Tap + to add your first item.")
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(products, key = { it.id }) { product ->
                        ProductRow(
                            product = product,
                            currency = currency,
                            onClick = { editing = product; showDialog = true },
                        )
                    }
                }
            }
        }
    }

    if (showDialog) {
        ProductEditDialog(
            initial = editing,
            onDismiss = { showDialog = false },
            onSave = { code, name, category, cost, retail, price, stock, lowStock ->
                viewModel.save(editing?.id, code, name, category, cost, retail, price, stock, lowStock)
                showDialog = false
            },
            onDelete = editing?.let { p -> { viewModel.delete(p.id); showDialog = false } },
        )
    }
}

@Composable
private fun ProductRow(product: ProductEntity, currency: NumberFormat, onClick: () -> Unit) {
    val lowStock = product.stock <= product.lowStockThreshold
    ElevatedCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(product.name, fontWeight = FontWeight.SemiBold)
                Text(product.category ?: "Uncategorized", style = MaterialTheme.typography.bodySmall)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(currency.format(product.price), fontWeight = FontWeight.Bold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (lowStock) Icon(Icons.Filled.Warning, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(14.dp))
                    Text(" Stock: ${product.stock}", style = MaterialTheme.typography.bodySmall,
                        color = if (lowStock) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun ProductEditDialog(
    initial: ProductEntity?,
    onDismiss: () -> Unit,
    onSave: (String?, String, String?, Double, Double, Double, Int, Int) -> Unit,
    onDelete: (() -> Unit)?,
) {
    var code by remember { mutableStateOf(initial?.productCode ?: "") }
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var category by remember { mutableStateOf(initial?.category ?: "") }
    var cost by remember { mutableStateOf(initial?.costPrice?.toString() ?: "") }
    var retail by remember { mutableStateOf(initial?.retailPrice?.toString() ?: "") }
    var price by remember { mutableStateOf(initial?.price?.toString() ?: "") }
    var stock by remember { mutableStateOf(initial?.stock?.toString() ?: "0") }
    var lowStock by remember { mutableStateOf(initial?.lowStockThreshold?.toString() ?: "5") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Add Product" else "Edit Product") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(code, { code = it }, label = { Text("Barcode / Code") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(name, { name = it }, label = { Text("Name*") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(category, { category = it }, label = { Text("Category") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row {
                    OutlinedTextField(cost, { cost = it }, label = { Text("Cost") }, modifier = Modifier.weight(1f).padding(end = 4.dp))
                    OutlinedTextField(retail, { retail = it }, label = { Text("Retail") }, modifier = Modifier.weight(1f).padding(start = 4.dp))
                }
                OutlinedTextField(price, { price = it }, label = { Text("Selling Price*") }, modifier = Modifier.fillMaxWidth())
                Row {
                    OutlinedTextField(stock, { stock = it }, label = { Text("Stock*") }, modifier = Modifier.weight(1f).padding(end = 4.dp))
                    OutlinedTextField(lowStock, { lowStock = it }, label = { Text("Low stock alert") }, modifier = Modifier.weight(1f).padding(start = 4.dp))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(
                    code.ifBlank { null }, name, category.ifBlank { null },
                    cost.toDoubleOrNull() ?: 0.0, retail.toDoubleOrNull() ?: 0.0, price.toDoubleOrNull() ?: 0.0,
                    stock.toIntOrNull() ?: 0, lowStock.toIntOrNull() ?: 5,
                )
            }, enabled = name.isNotBlank()) { Text("Save") }
        },
        dismissButton = {
            Row {
                onDelete?.let { TextButton(onClick = it) { Text("Delete", color = MaterialTheme.colorScheme.error) } }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}
