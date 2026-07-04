package com.marnie.pos.sync

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

private val Context.syncDataStore by preferencesDataStore(name = "sync_prefs")

@Singleton
class SyncCursorStore @Inject constructor(@ApplicationContext private val context: Context) {
    private val lastSyncKey = stringPreferencesKey("last_sync_at")

    suspend fun getLastSyncAt(): String =
        context.syncDataStore.data.first()[lastSyncKey] ?: Instant.EPOCH.toString()

    suspend fun setLastSyncAt(value: String) {
        context.syncDataStore.edit { it[lastSyncKey] = value }
    }
}
