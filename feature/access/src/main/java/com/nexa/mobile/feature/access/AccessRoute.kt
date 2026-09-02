package com.nexa.mobile.feature.access

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import androidx.savedstate.serialization.SavedStateConfiguration
import com.nexa.mobile.core.designsystem.NexaTheme
import kotlinx.serialization.Serializable
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass

@Serializable
sealed interface AccessRoute : NavKey {
    @Serializable
    data object Launch : AccessRoute

    @Serializable
    data object SignIn : AccessRoute

    @Serializable
    data object OperationsEntry : AccessRoute
}

/**
 * Minimal typed Navigation 3 shell. Server-confirmed access remains disabled
 * until the tagged API surface semantics are approved.
 */
@Composable
fun AccessNavigation(viewModel: LaunchViewModel) {
    val configuration = remember {
        SavedStateConfiguration {
            serializersModule = SerializersModule {
                polymorphic(NavKey::class) {
                    subclass(AccessRoute.Launch::class)
                    subclass(AccessRoute.SignIn::class)
                    subclass(AccessRoute.OperationsEntry::class)
                }
            }
        }
    }
    val backStack = rememberNavBackStack(configuration, AccessRoute.Launch)
    val launchState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) {
        viewModel.restore()
    }

    NexaTheme {
        NavDisplay(
            backStack = backStack,
            onBack = { if (backStack.size > 1) backStack.removeLastOrNull() },
            entryProvider = entryProvider {
                entry<AccessRoute.Launch> {
                    LaunchScreen(
                        state = launchState,
                        onRetry = viewModel::restore,
                        onSignIn = dropUnlessResumed { backStack += AccessRoute.SignIn },
                    )
                }
                entry<AccessRoute.SignIn> {
                    PlaceholderScreen(onBack = { if (backStack.size > 1) backStack.removeLastOrNull() })
                }
                entry<AccessRoute.OperationsEntry> {
                    PlaceholderScreen(onBack = { if (backStack.size > 1) backStack.removeLastOrNull() })
                }
            },
        )
    }
}

@Composable
private fun PlaceholderScreen(onBack: () -> Unit) {
    Scaffold(contentWindowInsets = WindowInsets.safeDrawing) { contentPadding: PaddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .padding(horizontal = 24.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(R.string.access_blocked_title),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = stringResource(R.string.access_blocked_body),
                style = MaterialTheme.typography.bodyLarge,
            )
            Button(onClick = onBack) {
                Text(text = stringResource(R.string.launch_back))
            }
        }
    }
}
