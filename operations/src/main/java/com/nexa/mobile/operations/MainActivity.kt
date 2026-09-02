package com.nexa.mobile.operations

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModelProvider
import com.nexa.mobile.core.storage.KeystoreSessionStore
import com.nexa.mobile.feature.access.AccessNavigation
import com.nexa.mobile.feature.access.LaunchViewModel

class MainActivity : ComponentActivity() {
    private val launchViewModel: LaunchViewModel by lazy {
        ViewModelProvider(
            this,
            LaunchViewModel.Factory(
                sessionStore = KeystoreSessionStore(applicationContext),
                sessionConfirmation = null,
            ),
        )[LaunchViewModel::class.java]
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AccessNavigation(viewModel = launchViewModel)
        }
    }
}
