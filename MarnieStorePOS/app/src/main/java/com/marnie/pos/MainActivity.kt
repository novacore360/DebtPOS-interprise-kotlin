package com.marnie.pos

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.marnie.pos.data.remote.ws.RealtimeSyncClient
import com.marnie.pos.security.SessionManager
import com.marnie.pos.sync.ConnectivityObserver
import com.marnie.pos.sync.SyncWorker
import com.marnie.pos.ui.navigation.MarnieNavHost
import com.marnie.pos.ui.theme.MarnieTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var sessionManager: SessionManager
    @Inject lateinit var connectivityObserver: ConnectivityObserver
    @Inject lateinit var realtimeSyncClient: RealtimeSyncClient

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Safety-net background sync every 15 min regardless of foreground state.
        SyncWorker.schedulePeriodic(applicationContext)

        setContent {
            val windowSizeClass = calculateWindowSizeClass(this)

            // Sync immediately whenever connectivity is (re)gained.
            LaunchedEffect(Unit) {
                connectivityObserver.observe()
                    .distinctUntilChanged()
                    .filter { online -> online }
                    .collect { SyncWorker.triggerImmediate(applicationContext) }
            }

            // Keep the realtime socket connected while the app is foregrounded
            // and a session exists; disconnect otherwise to save battery.
            LaunchedEffect(Unit) {
                sessionManager.session.collect { session ->
                    if (session != null) realtimeSyncClient.connect() else realtimeSyncClient.disconnect()
                }
            }

            DisposableLifecycleEffect(realtimeSyncClient)

            MarnieTheme {
                MarnieNavHost(sessionManager = sessionManager, widthSizeClass = windowSizeClass.widthSizeClass)
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun DisposableLifecycleEffect(realtimeSyncClient: RealtimeSyncClient) {
    val lifecycleOwner = LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> realtimeSyncClient.disconnect()
                Lifecycle.Event.ON_START -> realtimeSyncClient.connect()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}
