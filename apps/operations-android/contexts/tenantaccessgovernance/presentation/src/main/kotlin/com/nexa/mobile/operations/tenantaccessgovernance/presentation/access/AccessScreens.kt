package com.nexa.mobile.operations.tenantaccessgovernance.presentation.access

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.nexa.mobile.operations.core.designsystem.NexaActiveContextBar
import com.nexa.mobile.operations.core.designsystem.NexaAuthCanopy
import com.nexa.mobile.operations.core.designsystem.NexaColors
import com.nexa.mobile.operations.core.designsystem.NexaContextChoiceRow
import com.nexa.mobile.operations.core.designsystem.NexaFeedbackBanner
import com.nexa.mobile.operations.core.designsystem.NexaFeedbackTone
import com.nexa.mobile.operations.core.designsystem.NexaPrimaryButton
import com.nexa.mobile.operations.core.designsystem.NexaShapes
import com.nexa.mobile.operations.core.designsystem.NexaSizes
import com.nexa.mobile.operations.core.designsystem.NexaStatePanel
import com.nexa.mobile.operations.core.designsystem.NexaTopAppBar
import com.nexa.mobile.operations.core.designsystem.R as DesignR
import com.nexa.mobile.operations.tenantaccessgovernance.domain.model.access.WorkforceContextSummary
import com.nexa.mobile.operations.tenantaccessgovernance.presentation.R

@Composable
fun AccessScreen(
    state: AccessUiState,
    onIdentifierChanged: (String) -> Unit,
    onPasswordChanged: (String) -> Unit,
    onPasswordVisibilityChanged: () -> Unit,
    onSignIn: () -> Unit,
    modifier: Modifier = Modifier,
    onRecoveryEmailChanged: (String) -> Unit = {},
    onRequestPasswordReset: () -> Unit = {},
    onRetry: () -> Unit = {},
    onClearLocalSession: () -> Unit = {},
    showRetry: Boolean = false
) {
    var credentialsRequested by rememberSaveable { mutableStateOf(false) }
    var recoveryRequested by rememberSaveable { mutableStateOf(false) }
    var previousStage by remember { mutableStateOf<AccessStage?>(null) }
    LaunchedEffect(state.stage) {
        val returnedFromWork = previousStage == AccessStage.WorkAuthorized ||
            previousStage == AccessStage.NoContexts ||
            previousStage == AccessStage.LocalProtectionError
        if (state.stage == AccessStage.WorkAuthorized ||
            (state.stage == AccessStage.IdentityRequired && returnedFromWork)
        ) {
            credentialsRequested = false
        }
        if (state.stage !in setOf(AccessStage.IdentityRequired, AccessStage.SessionExpired)) {
            recoveryRequested = false
        }
        previousStage = state.stage
    }
    val showWelcome = state.stage == AccessStage.IdentityRequired &&
        !credentialsRequested && state.notice == null &&
        !state.identifierError && !state.passwordError
    val showCredentialsAction = state.stage in setOf(
        AccessStage.IdentityRequired,
        AccessStage.Authenticating,
        AccessStage.ContextChooser,
        AccessStage.WorkAuthorized,
        AccessStage.SessionExpired
    )
    val signInStage = state.stage == AccessStage.IdentityRequired ||
        state.stage == AccessStage.SessionExpired
    val keyboardVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    BoxWithConstraints(
        modifier = modifier.fillMaxSize().background(NexaColors.Surface)
            .imePadding()
    ) {
        val landscape = maxWidth > maxHeight
        val largeFont = LocalDensity.current.fontScale >= 1.5f
        val compactForm = keyboardVisible || landscape || maxHeight < 560.dp || largeFont
        val heroHeight = when {
            showWelcome -> maxHeight * 0.63f
            keyboardVisible || landscape || maxHeight < 480.dp -> 0.dp
            largeFont -> 128.dp.coerceAtMost(maxHeight * 0.22f)
            maxHeight >= 700.dp -> maxHeight * 0.46f
            else -> (maxHeight * 0.21f).coerceAtMost(180.dp)
        }
        val contentHeight = (maxHeight - heroHeight).coerceAtLeast(0.dp)
        val loginStage = state.stage == AccessStage.IdentityRequired ||
            state.stage == AccessStage.SessionExpired
        val horizontalInset = if (maxWidth >= 700.dp) 32.dp else 24.dp
        Column(
            modifier = Modifier.fillMaxSize()
                .semantics { isTraversalGroup = true }
        ) {
            if (heroHeight > 0.dp) {
                AccessBrandPlane(
                    modifier = Modifier.fillMaxWidth().height(heroHeight),
                    compact = largeFont
                )
            }
            Box(
                modifier = Modifier.widthIn(max = 1080.dp).fillMaxWidth()
                    .align(Alignment.CenterHorizontally)
                    .height(contentHeight)
                    .padding(horizontal = horizontalInset),
                contentAlignment = Alignment.TopCenter
            ) {
                if (showWelcome) {
                    AccessWelcomeContent(
                        modifier = Modifier.widthIn(max = 620.dp).fillMaxWidth()
                            .fillMaxHeight(),
                        compact = compactForm,
                        onContinue = { credentialsRequested = true }
                    )
                } else {
                    Column(
                        modifier = Modifier.widthIn(max = 620.dp).fillMaxWidth()
                            .fillMaxHeight()
                            .padding(
                                top = if (compactForm) 4.dp else 8.dp,
                                bottom = if (keyboardVisible) 12.dp else 16.dp
                            ),
                        verticalArrangement = Arrangement.spacedBy(
                            space = if (compactForm) 8.dp else 12.dp,
                            alignment = if (keyboardVisible || loginStage) {
                                Alignment.Bottom
                            } else {
                                Alignment.Top
                            }
                        )
                    ) {
                        if (recoveryRequested && loginStage) {
                            AccessRecoveryContent(
                                state = state,
                                onEmailChanged = onRecoveryEmailChanged,
                                onSubmit = onRequestPasswordReset,
                                onBack = { recoveryRequested = false },
                                compact = compactForm,
                                modifier = Modifier.fillMaxWidth()
                            )
                        } else {
                            AccessStageContent(
                                state = state,
                                onIdentifierChanged = onIdentifierChanged,
                                onPasswordChanged = onPasswordChanged,
                                onPasswordVisibilityChanged = onPasswordVisibilityChanged,
                                onSignIn = onSignIn,
                                onRetry = onRetry,
                                onClearLocalSession = onClearLocalSession,
                                showRetry = showRetry,
                                modifier = Modifier.fillMaxWidth(),
                                compact = compactForm,
                                onOpenRecovery = {
                                    val email = state.identifier.takeIf {
                                        it.contains('@')
                                    }.orEmpty()
                                    onRecoveryEmailChanged(email)
                                    recoveryRequested = true
                                },
                                onBackToWelcome = if (state.stage == AccessStage.IdentityRequired) {
                                    { credentialsRequested = false }
                                } else {
                                    null
                                }
                            )
                        }
                        if (showCredentialsAction && !signInStage) {
                            AccessBottomAction(
                                modifier = Modifier.fillMaxWidth(),
                                label = stringResource(R.string.access_submit),
                                loading = state.stage == AccessStage.Authenticating,
                                onClick = onSignIn
                            )
                        }
                        state.notice?.let { notice -> AccessNoticeBanner(notice) }
                    }
                }
            }
        }
    }
}

@Composable
fun ContextChooserScreen(
    state: ContextChooserUiState,
    modifier: Modifier = Modifier,
    onSelect: (String) -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    notice: AccessNotice? = null,
    onClearLocalSession: (() -> Unit)? = null
) {
    val changeMode = state.mode == ContextChooserMode.Change
    Column(
        modifier = modifier
            .fillMaxSize()
            .semantics { isTraversalGroup = true }
            .background(NexaColors.Canvas)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
    ) {
        NexaTopAppBar(
            title = stringResource(R.string.access_product_label),
            onBack = if (changeMode && state.phase != ContextChooserPhase.SelectionPending) {
                onBack
            } else {
                null
            }
        )
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            ContextChooserHeader(changeMode = changeMode)
            if (notice != null) {
                AccessNoticeBanner(notice)
            }
            ContextChooserPhaseContent(
                state = state,
                changeMode = changeMode,
                onSelect = onSelect,
                onRetry = onRetry,
                onBack = onBack,
                onClearLocalSession = onClearLocalSession
            )
            if (notice == AccessNotice.ContextSelectionRejected) {
                NexaPrimaryButton(label = stringResource(R.string.context_retry), onClick = onRetry)
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun AccessBrandPlane(modifier: Modifier = Modifier, compact: Boolean = false) {
    NexaAuthCanopy(modifier = modifier) {
        Image(
            painter = painterResource(DesignR.drawable.nexa_wordmark_white),
            contentDescription = stringResource(R.string.access_brand_description),
            modifier = Modifier.align(Alignment.TopStart)
                .padding(start = 24.dp, top = if (compact) 8.dp else 48.dp)
                .width(if (compact) 96.dp else 112.dp)
                .height(if (compact) 30.dp else 35.dp),
            contentScale = ContentScale.Fit
        )
        Text(
            text = stringResource(R.string.access_product_label),
            modifier = Modifier.align(Alignment.TopStart)
                .padding(start = 24.dp, top = if (compact) 44.dp else 82.dp),
            style = MaterialTheme.typography.labelMedium.copy(
                fontWeight = FontWeight.SemiBold
            ),
            color = NexaColors.BrandCeleste
        )
    }
}

@Composable
private fun AccessWelcomeContent(
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    onContinue: () -> Unit
) {
    Column(
        modifier = modifier.padding(top = if (compact) 4.dp else 16.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        AccessWelcomeCopy()
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            val continueLabel = stringResource(R.string.access_welcome_continue)
            TextButton(onClick = onContinue) {
                Text(
                    continueLabel,
                    style = MaterialTheme.typography.labelLarge,
                    color = NexaColors.Primary
                )
            }
            IconButton(
                onClick = onContinue,
                modifier = Modifier.size(NexaSizes.minimumTouchTarget)
                    .background(NexaColors.Primary, CircleShape)
                    .semantics { contentDescription = continueLabel }
            ) {
                Icon(
                    painter = painterResource(DesignR.drawable.ic_chevron_right),
                    contentDescription = null,
                    tint = NexaColors.Surface,
                    modifier = Modifier.size(NexaSizes.icon)
                )
            }
        }
    }
}

@Composable
private fun AccessWelcomeCopy() {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(R.string.access_welcome_title),
            style = MaterialTheme.typography.headlineSmall.copy(
                fontWeight = FontWeight.SemiBold
            ),
            color = NexaColors.TextPrimary
        )
        Text(
            stringResource(R.string.access_welcome_support),
            style = MaterialTheme.typography.bodyLarge,
            color = NexaColors.TextSecondary
        )
    }
}

@Composable
private fun AccessBottomAction(
    modifier: Modifier = Modifier,
    label: String,
    loading: Boolean = false,
    onClick: () -> Unit
) {
    NexaPrimaryButton(
        label = label,
        onClick = onClick,
        loading = loading,
        modifier = modifier
    )
}

@Composable
private fun AccessStageContent(
    state: AccessUiState,
    onIdentifierChanged: (String) -> Unit,
    onPasswordChanged: (String) -> Unit,
    onPasswordVisibilityChanged: () -> Unit,
    onSignIn: () -> Unit,
    onRetry: () -> Unit,
    onClearLocalSession: () -> Unit,
    showRetry: Boolean,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    onBackToWelcome: (() -> Unit)? = null,
    onOpenRecovery: (() -> Unit)? = null
) {
    when (state.stage) {
        AccessStage.RestoringSession -> {
            Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                NexaStatePanel(
                    title = stringResource(R.string.access_session_restoring_title),
                    description = stringResource(R.string.access_session_restoring_body)
                )
                CenteredProgress(stringResource(R.string.access_session_restoring_title))
                if (showRetry) {
                    NexaPrimaryButton(
                        label = stringResource(R.string.access_context_retry),
                        onClick = onRetry
                    )
                }
            }
        }

        AccessStage.Authenticating -> {
            AccessCredentialsForm(
                modifier = modifier,
                state = state,
                onIdentifierChanged = onIdentifierChanged,
                onPasswordChanged = onPasswordChanged,
                onPasswordVisibilityChanged = onPasswordVisibilityChanged,
                loading = true,
                compact = compact,
                onBackToWelcome = null,
                onOpenRecovery = null
            )
        }

        AccessStage.ResolvingContexts -> {
            Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                NexaStatePanel(
                    title = stringResource(R.string.access_context_loading_title),
                    description = stringResource(R.string.access_context_loading_body)
                )
                CenteredProgress(stringResource(R.string.access_context_loading_title))
            }
        }

        AccessStage.NoContexts -> NexaStatePanel(
            title = stringResource(R.string.access_no_context_title),
            description = stringResource(R.string.access_no_context_body),
            actionLabel = stringResource(R.string.access_local_logout),
            onAction = onClearLocalSession,
            modifier = modifier
        )

        AccessStage.LocalProtectionError -> NexaStatePanel(
            title = stringResource(R.string.access_local_error_title),
            description = stringResource(R.string.access_local_error_body),
            actionLabel = stringResource(R.string.access_local_logout),
            onAction = onClearLocalSession,
            modifier = modifier
        )

        AccessStage.IdentityRequired, AccessStage.SessionExpired -> AccessCredentialsForm(
            modifier = modifier,
            state = state,
            onIdentifierChanged = onIdentifierChanged,
            onPasswordChanged = onPasswordChanged,
            onPasswordVisibilityChanged = onPasswordVisibilityChanged,
            compact = compact,
            onBackToWelcome = onBackToWelcome,
            onOpenRecovery = onOpenRecovery,
            onSignIn = onSignIn
        )

        AccessStage.ContextChooser, AccessStage.WorkAuthorized -> AccessCredentialsForm(
            modifier = modifier,
            state = state,
            onIdentifierChanged = onIdentifierChanged,
            onPasswordChanged = onPasswordChanged,
            onPasswordVisibilityChanged = onPasswordVisibilityChanged,
            compact = compact,
            onBackToWelcome = null,
            onOpenRecovery = null
        )
    }
}

@Composable
private fun AccessCredentialsForm(
    modifier: Modifier = Modifier,
    state: AccessUiState,
    onIdentifierChanged: (String) -> Unit,
    onPasswordChanged: (String) -> Unit,
    onPasswordVisibilityChanged: () -> Unit,
    loading: Boolean = false,
    compact: Boolean = false,
    onBackToWelcome: (() -> Unit)? = null,
    onOpenRecovery: (() -> Unit)? = null,
    onSignIn: (() -> Unit)? = null
) {
    val focusManager = LocalFocusManager.current
    Column(
        modifier = modifier.padding(vertical = if (compact) 4.dp else 8.dp),
        verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 12.dp)
    ) {
        if (onBackToWelcome != null && !loading && !compact) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(
                    modifier = Modifier.heightIn(min = NexaSizes.minimumTouchTarget),
                    onClick = onBackToWelcome
                ) {
                    Text(stringResource(R.string.access_back_to_welcome))
                }
            }
        }
        if (!compact || !loading) {
            Column(verticalArrangement = Arrangement.spacedBy(if (compact) 0.dp else 8.dp)) {
                Text(
                    stringResource(R.string.access_sign_in_title),
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontWeight = FontWeight.SemiBold
                    ),
                    color = NexaColors.TextPrimary
                )
                if (!compact) {
                    Text(
                        stringResource(R.string.access_sign_in_support),
                        style = MaterialTheme.typography.bodyMedium,
                        color = NexaColors.TextSecondary
                    )
                }
            }
        }
        AccessOutlinedField(
            value = state.identifier,
            onValueChange = onIdentifierChanged,
            label = stringResource(R.string.access_identifier_label),
            leadingIconRes = DesignR.drawable.ic_identity,
            enabled = !loading,
            compact = compact,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            keyboardActions = KeyboardActions(
                onNext = { focusManager.moveFocus(FocusDirection.Down) }
            ),
            errorText = if (state.identifierError) {
                stringResource(R.string.access_identifier_error)
            } else {
                null
            }
        )
        AccessOutlinedField(
            value = state.password,
            onValueChange = onPasswordChanged,
            label = stringResource(R.string.access_password_label),
            leadingIconRes = DesignR.drawable.ic_lock,
            trailingIcon = {
                AccessVisibilityButton(
                    visible = state.passwordVisible,
                    enabled = !loading,
                    onClick = onPasswordVisibilityChanged,
                    showLabel = stringResource(R.string.access_password_show),
                    hideLabel = stringResource(R.string.access_password_hide)
                )
            },
            enabled = !loading,
            compact = compact,
            visualTransformation = if (state.passwordVisible) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(
                onDone = { onSignIn?.invoke() }
            ),
            errorText = if (state.passwordError) {
                stringResource(R.string.access_password_error)
            } else {
                null
            }
        )
        if (onOpenRecovery != null && onSignIn != null && !loading) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(
                    modifier = Modifier.heightIn(
                        min = if (compact) 36.dp else NexaSizes.minimumTouchTarget
                    ),
                    onClick = onOpenRecovery
                ) {
                    Text(stringResource(R.string.access_recover_account))
                }
            }
        }
        if (loading) {
            Column(verticalArrangement = Arrangement.spacedBy(if (compact) 2.dp else 4.dp)) {
                Text(
                    stringResource(R.string.access_sign_in_loading_title),
                    style = MaterialTheme.typography.labelLarge,
                    color = NexaColors.PrimaryStrong,
                    modifier = Modifier.semantics {
                        liveRegion = LiveRegionMode.Polite
                    }
                )
                Text(
                    stringResource(R.string.access_sign_in_loading_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = NexaColors.TextSecondary
                )
            }
        }
        if (onSignIn != null) {
            NexaPrimaryButton(
                label = stringResource(R.string.access_submit),
                onClick = onSignIn
            )
        }
    }
}

@Composable
private fun AccessRecoveryContent(
    state: AccessUiState,
    onEmailChanged: (String) -> Unit,
    onSubmit: () -> Unit,
    onBack: () -> Unit,
    compact: Boolean,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(vertical = if (compact) 4.dp else 8.dp),
        verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 12.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(
                modifier = Modifier.heightIn(min = NexaSizes.minimumTouchTarget),
                onClick = onBack
            ) {
                Text(stringResource(R.string.access_recovery_back))
            }
        }
        Text(
            stringResource(R.string.access_recovery_title),
            style = MaterialTheme.typography.headlineSmall.copy(
                fontWeight = FontWeight.SemiBold
            ),
            color = NexaColors.TextPrimary
        )
        Text(
            stringResource(R.string.access_recovery_support),
            style = MaterialTheme.typography.bodyMedium,
            color = NexaColors.TextSecondary
        )
        AccessOutlinedField(
            value = state.recoveryEmail,
            onValueChange = onEmailChanged,
            label = stringResource(R.string.access_recovery_email),
            leadingIconRes = DesignR.drawable.ic_identity,
            enabled = state.recoveryStatus != PasswordRecoveryStatus.Sending,
            compact = compact,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Email,
                imeAction = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(onDone = { onSubmit() }),
            errorText = if (state.recoveryEmailError) {
                stringResource(R.string.access_recovery_email_error)
            } else {
                null
            }
        )
        when (state.recoveryStatus) {
            PasswordRecoveryStatus.Idle -> Unit

            PasswordRecoveryStatus.Sending -> RecoveryStatusText(
                stringResource(R.string.access_recovery_sending),
                NexaColors.TextSecondary
            )

            PasswordRecoveryStatus.Sent -> RecoveryStatusText(
                stringResource(R.string.access_recovery_sent),
                NexaColors.Success
            )

            PasswordRecoveryStatus.RateLimited -> RecoveryStatusText(
                stringResource(R.string.access_recovery_rate_limited),
                NexaColors.Danger
            )

            PasswordRecoveryStatus.Failed -> RecoveryStatusText(
                stringResource(R.string.access_recovery_failed),
                NexaColors.Danger
            )
        }
        NexaPrimaryButton(
            label = stringResource(R.string.access_recovery_submit),
            onClick = onSubmit,
            loading = state.recoveryStatus == PasswordRecoveryStatus.Sending
        )
    }
}

@Composable
private fun RecoveryStatusText(message: String, color: androidx.compose.ui.graphics.Color) {
    Text(
        message,
        style = MaterialTheme.typography.bodySmall,
        color = color,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
    )
}

@Composable
private fun AccessOutlinedField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    leadingIconRes: Int,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    compact: Boolean = false,
    errorText: String? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailingIcon: (@Composable () -> Unit)? = null
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(if (compact) 0.dp else 6.dp)
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth().heightIn(min = NexaSizes.controlMinHeight)
                .semantics {
                    contentDescription = label
                    if (errorText != null) error(errorText)
                },
            leadingIcon = {
                Icon(
                    painter = painterResource(leadingIconRes),
                    contentDescription = null,
                    tint = NexaColors.TextSecondary
                )
            },
            trailingIcon = trailingIcon,
            supportingText = errorText?.let { { Text(it) } },
            isError = errorText != null,
            enabled = enabled,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge,
            visualTransformation = visualTransformation,
            shape = NexaShapes.control,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = NexaColors.Primary,
                unfocusedBorderColor = NexaColors.BorderStrong,
                disabledBorderColor = NexaColors.Border,
                errorBorderColor = NexaColors.DangerBorder,
                focusedContainerColor = NexaColors.SurfaceInset,
                unfocusedContainerColor = NexaColors.SurfaceInset,
                disabledContainerColor = NexaColors.SurfaceInset,
                errorContainerColor = NexaColors.SurfaceInset,
                cursorColor = NexaColors.Primary
            ),
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions
        )
    }
}

@Composable
private fun AccessVisibilityButton(
    visible: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    showLabel: String,
    hideLabel: String
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(NexaSizes.minimumTouchTarget).semantics {
            contentDescription = if (visible) hideLabel else showLabel
        }
    ) {
        Canvas(Modifier.size(NexaSizes.icon)) {
            val eye = Path().apply {
                moveTo(2.dp.toPx(), size.height / 2)
                cubicTo(
                    size.width * 0.28f,
                    size.height * 0.12f,
                    size.width * 0.72f,
                    size.height * 0.12f,
                    size.width - 2.dp.toPx(),
                    size.height / 2
                )
                cubicTo(
                    size.width * 0.72f,
                    size.height * 0.88f,
                    size.width * 0.28f,
                    size.height * 0.88f,
                    2.dp.toPx(),
                    size.height / 2
                )
                close()
            }
            val color = NexaColors.TextSecondary
            drawPath(eye, color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
            drawCircle(color, radius = 2.5.dp.toPx())
            if (!visible) {
                drawLine(
                    color,
                    Offset(4.dp.toPx(), 4.dp.toPx()),
                    Offset(size.width - 4.dp.toPx(), size.height - 4.dp.toPx()),
                    strokeWidth = 2.dp.toPx(),
                    cap = StrokeCap.Round
                )
            }
        }
    }
}

@Composable
private fun ContextChooserHeader(changeMode: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(
                if (changeMode) R.string.context_change_title else R.string.context_initial_title
            ),
            style = MaterialTheme.typography.headlineSmall,
            color = NexaColors.TextPrimary
        )
        Text(
            stringResource(
                if (changeMode) {
                    R.string.context_change_support
                } else {
                    R.string.context_initial_support
                }
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = NexaColors.TextSecondary
        )
    }
}

@Composable
private fun ContextChooserPhaseContent(
    state: ContextChooserUiState,
    changeMode: Boolean,
    onSelect: (String) -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    onClearLocalSession: (() -> Unit)?
) {
    when (state.phase) {
        ContextChooserPhase.Loading -> {
            NexaStatePanel(
                title = stringResource(R.string.context_loading_title),
                description = stringResource(R.string.context_loading_body)
            )
            CenteredProgress(stringResource(R.string.context_loading_title))
        }

        ContextChooserPhase.ListUnavailable -> {
            val currentValid =
                state.unavailableReason == ContextUnavailableReason.CurrentContextRemainsValid
            NexaStatePanel(
                title = stringResource(
                    if (currentValid) {
                        R.string.context_current_valid_title
                    } else {
                        R.string.context_no_active_title
                    }
                ),
                description = stringResource(
                    if (currentValid) {
                        R.string.context_current_valid_body
                    } else {
                        R.string.context_no_active_body
                    }
                ),
                actionLabel = stringResource(
                    if (currentValid) R.string.context_back_to_work else R.string.context_retry
                ),
                onAction = if (currentValid) onBack else onRetry
            )
            if (currentValid) {
                state.current?.let { context -> CurrentContextRow(context) }
                NexaPrimaryButton(
                    label = stringResource(R.string.context_retry),
                    onClick = onRetry
                )
            }
            if (!currentValid && onClearLocalSession != null) {
                NexaPrimaryButton(
                    label = stringResource(R.string.access_local_logout),
                    onClick = onClearLocalSession
                )
            }
        }

        ContextChooserPhase.OnlyCurrent -> {
            state.current?.let { context -> CurrentContextRow(context) }
            NexaStatePanel(
                title = stringResource(R.string.context_only_current_title),
                description = stringResource(R.string.context_only_current_body),
                actionLabel = if (changeMode) {
                    stringResource(R.string.context_back_to_work)
                } else {
                    null
                },
                onAction = if (changeMode) onBack else null
            )
        }

        ContextChooserPhase.SelectionPending -> {
            state.choices.forEach { context ->
                NexaContextChoiceRow(
                    companyName = context.companyName,
                    workspaceName = context.workspaceName,
                    current = context.isCurrent,
                    pending = state.pendingKey == context.key,
                    enabled = false,
                    onClick = {}
                )
            }
            NexaStatePanel(
                title = stringResource(R.string.context_loading_title),
                description = stringResource(R.string.context_selection_pending)
            )
            CenteredProgress(stringResource(R.string.context_loading_title))
        }

        ContextChooserPhase.SelectionRejected -> {
            NexaStatePanel(
                title = stringResource(R.string.context_selection_rejected),
                description = stringResource(R.string.context_initial_support)
            )
            state.choices.filterNot { it.isCurrent }.forEach { context ->
                NexaContextChoiceRow(
                    companyName = context.companyName,
                    workspaceName = context.workspaceName,
                    onClick = { onSelect(context.key) }
                )
            }
        }

        ContextChooserPhase.Choices -> {
            state.current?.takeIf { current -> state.choices.none { it.key == current.key } }
                ?.let { CurrentContextRow(it) }
            state.choices.forEach { context ->
                NexaContextChoiceRow(
                    companyName = context.companyName,
                    workspaceName = context.workspaceName,
                    current = context.isCurrent,
                    pending = state.pendingKey == context.key,
                    enabled = state.phase == ContextChooserPhase.Choices,
                    onClick = { onSelect(context.key) }
                )
            }
        }
    }
}

@Composable
private fun AccessNoticeBanner(notice: AccessNotice, modifier: Modifier = Modifier) {
    NexaFeedbackBanner(
        message = stringResource(notice.messageResource()),
        tone = notice.tone(),
        modifier = modifier
    )
}

@Composable
private fun CurrentContextRow(context: WorkforceContextSummary) {
    NexaActiveContextBar(
        companyName = context.companyName,
        workspaceName = context.workspaceName,
        enabled = false,
        onClick = {}
    )
}

@Composable
private fun CenteredProgress(label: String) {
    androidx.compose.foundation.layout.Box(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator(
            Modifier.semantics { contentDescription = label }
        )
    }
}

@Composable
private fun AccessNotice.messageResource(): Int = when (this) {
    AccessNotice.AuthenticationRejected -> R.string.access_notice_auth_rejected
    AccessNotice.NetworkUnavailable -> R.string.access_notice_network
    AccessNotice.ServiceUnavailable -> R.string.access_notice_service
    AccessNotice.IntegrationUnavailable -> R.string.access_notice_integration
    AccessNotice.SessionExpired -> R.string.access_notice_expired
    AccessNotice.ContextListUnavailable -> R.string.access_notice_context_list
    AccessNotice.ContextSelectionRejected -> R.string.access_notice_context_rejected
    AccessNotice.ContextSelectionUnavailable -> R.string.access_notice_context_unavailable
    AccessNotice.UnknownContextOutcome -> R.string.access_notice_context_unknown
    AccessNotice.NoContexts -> R.string.access_notice_no_context
}

private fun AccessNotice.tone(): NexaFeedbackTone = when (this) {
    AccessNotice.AuthenticationRejected,
    AccessNotice.NetworkUnavailable,
    AccessNotice.ServiceUnavailable,
    AccessNotice.ContextListUnavailable,
    AccessNotice.ContextSelectionRejected,
    AccessNotice.ContextSelectionUnavailable,
    AccessNotice.UnknownContextOutcome
    -> NexaFeedbackTone.Warning

    AccessNotice.IntegrationUnavailable,
    AccessNotice.SessionExpired,
    AccessNotice.NoContexts
    -> NexaFeedbackTone.Information
}
