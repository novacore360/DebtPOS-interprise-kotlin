package com.marnie.pos.ui.screens.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.marnie.pos.data.repository.AuthRepository
import com.marnie.pos.security.SessionManager
import com.marnie.pos.sync.SyncManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    val sessionManager: SessionManager,
    val syncManager: SyncManager,
) : ViewModel() {
    fun logout(onDone: () -> Unit) {
        viewModelScope.launch { authRepository.logout(); onDone() }
    }

    fun forceSync() { viewModelScope.launch { syncManager.syncNow() } }
}

@Composable
fun SettingsScreen(viewModel: SettingsViewModel = hiltViewModel(), onLoggedOut: () -> Unit) {
    val session by viewModel.sessionManager.session.collectAsState()
    val pendingCount by viewModel.syncManager.pendingOutboxCount.collectAsState(initial = 0)
    var showLogoutConfirm by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))

        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Signed in as", style = MaterialTheme.typography.labelMedium)
                Text(session?.displayName ?: session?.userId ?: "Unknown", fontWeight = FontWeight.SemiBold)
            }
        }

        Spacer(Modifier.height(12.dp))

        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Sync", style = MaterialTheme.typography.labelMedium)
                Text("$pendingCount change(s) waiting to sync", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                Button(onClick = viewModel::forceSync) { Text("Sync Now") }
            }
        }

        Spacer(Modifier.height(12.dp))

        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Security", style = MaterialTheme.typography.labelMedium)
                Text(
                    "Your local data is encrypted on this device (SQLCipher) and every backend request is authenticated with a short-lived token. Sign out on shared devices when you're done.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Spacer(Modifier.weight(1f))

        OutlinedButton(onClick = { showLogoutConfirm = true }, modifier = Modifier.fillMaxWidth()) {
            Text("Sign Out")
        }
    }

    if (showLogoutConfirm) {
        AlertDialog(
            onDismissRequest = { showLogoutConfirm = false },
            title = { Text("Sign out?") },
            text = { Text("Any unsynced changes will keep waiting locally and finish syncing next time you sign in with network access.") },
            confirmButton = {
                TextButton(onClick = { viewModel.logout(onLoggedOut) }) { Text("Sign Out") }
            },
            dismissButton = { TextButton(onClick = { showLogoutConfirm = false }) { Text("Cancel") } },
        )
    }
}
