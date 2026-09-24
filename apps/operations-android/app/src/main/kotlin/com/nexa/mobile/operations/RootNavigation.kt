package com.nexa.mobile.operations

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.ui.NavDisplay
import com.nexa.mobile.operations.core.auth.session.SessionState

internal enum class RootDestination {
    Bootstrapping,
    SignedOut,
    Restoring,
    ContextRequired,
    Active,
    ReauthenticationRequired,
    LocalProtectionError
}

internal fun SessionState.rootDestination(): RootDestination = when (this) {
    SessionState.Bootstrapping -> RootDestination.Bootstrapping
    SessionState.SignedOut -> RootDestination.SignedOut
    is SessionState.Restoring -> RootDestination.Restoring
    SessionState.ContextRequired -> RootDestination.ContextRequired
    SessionState.Active -> RootDestination.Active
    SessionState.ReauthenticationRequired -> RootDestination.ReauthenticationRequired
    SessionState.LocalProtectionError -> RootDestination.LocalProtectionError
}

/** Every session state change replaces the whole unsaved root stack. */
@Composable
internal fun RootNavigation(state: SessionState, onLogout: () -> Unit, onRetry: () -> Unit) {
    val destination = state.rootDestination()
    key(destination) {
        val backStack = remember { mutableStateListOf<Any>(destination) }
        NavDisplay(
            backStack = backStack,
            onBack = { if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) },
            entryProvider = { entryKey ->
                when (entryKey) {
                    RootDestination.Bootstrapping -> NavEntry(entryKey) {
                        TechnicalShell("Preparing session")
                    }

                    RootDestination.SignedOut -> NavEntry(entryKey) {
                        TechnicalShell("Sign in required")
                    }

                    RootDestination.Restoring -> NavEntry(entryKey) {
                        TechnicalShell("Restoring session") {
                            if ((state as? SessionState.Restoring)?.canRetryConnection == true) {
                                Button(onClick = onRetry) { Text("Retry connection") }
                            }
                        }
                    }

                    RootDestination.ContextRequired -> NavEntry(entryKey) {
                        TechnicalShell("Context required") {
                            Button(onClick = onLogout) { Text("Clear local session") }
                        }
                    }

                    RootDestination.Active -> NavEntry(entryKey) {
                        TechnicalShell("Session verified") {
                            Button(onClick = onLogout) { Text("Sign out") }
                        }
                    }

                    RootDestination.ReauthenticationRequired -> NavEntry(entryKey) {
                        TechnicalShell("Sign in required")
                    }

                    RootDestination.LocalProtectionError -> NavEntry(entryKey) {
                        TechnicalShell("Local session unavailable")
                    }

                    else -> error("Unknown root destination")
                }
            }
        )
    }
}

@Composable
private fun TechnicalShell(status: String, actions: @Composable () -> Unit = {}) {
    Column(
        modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Operations technical shell")
        Text(status)
        actions()
    }
}
