package com.nexa.mobile.operations

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.nexa.mobile.operations.core.designsystem.LocalNexaProfileAction
import com.nexa.mobile.operations.core.designsystem.NexaTopAppBar
import com.nexa.mobile.operations.tenantaccessgovernance.domain.model.access.VerifiedContextAuthority
import com.nexa.mobile.operations.tenantaccessgovernance.presentation.R as AccessR
import com.nexa.mobile.operations.tenantaccessgovernance.presentation.profile.OwnProfileScreen
import com.nexa.mobile.operations.tenantaccessgovernance.presentation.profile.OwnProfileViewModel

@Composable
internal fun OperationsOwnProfileRoute(
    viewModel: OwnProfileViewModel,
    authority: VerifiedContextAuthority,
    onClose: () -> Unit
) {
    DisposableEffect(viewModel, authority) {
        viewModel.activate(authority)
        onDispose { viewModel.deactivate() }
    }
    val state by viewModel.state.collectAsState()
    BackHandler(onBack = onClose)
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing)) {
                CompositionLocalProvider(LocalNexaProfileAction provides null) {
                    NexaTopAppBar(
                        title = stringResource(R.string.profile_personal_details),
                        onBack = onClose
                    )
                }
                OwnProfileScreen(state, viewModel::save, viewModel::load)
            }
        }
    }
}
