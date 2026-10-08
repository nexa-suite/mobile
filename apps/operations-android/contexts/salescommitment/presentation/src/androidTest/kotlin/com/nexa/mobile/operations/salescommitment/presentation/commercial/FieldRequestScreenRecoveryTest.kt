package com.nexa.mobile.operations.salescommitment.presentation.commercial

import android.content.res.Configuration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import com.nexa.mobile.operations.core.designsystem.R as SharedR
import com.nexa.mobile.operations.salescommitment.application.commercial.FieldRequestGateway
import com.nexa.mobile.operations.salescommitment.application.commercial.FieldRequestRead
import com.nexa.mobile.operations.salescommitment.application.commercial.FieldRequestReview
import com.nexa.mobile.operations.salescommitment.application.commercial.FieldRequestStore
import com.nexa.mobile.operations.salescommitment.application.commercial.FieldRequestSubmission
import com.nexa.mobile.operations.salescommitment.application.model.commercial.FieldRequestIntent
import com.nexa.mobile.operations.salescommitment.application.model.commercial.FieldRequestRecord
import com.nexa.mobile.operations.salescommitment.domain.model.commercial.FieldRequestDraft
import com.nexa.mobile.operations.salescommitment.domain.model.commercial.FieldRequestLine
import com.nexa.mobile.operations.salescommitment.presentation.R
import com.nexa.mobile.operations.tenantaccessgovernance.application.publicapi.CommercialAuthority
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

class FieldRequestScreenRecoveryTest {
    @get:Rule val compose = createComposeRule()

    private val authority = CommercialAuthority(
        "user",
        "tenant",
        "workspace",
        "membership",
        setOf("sales:write"),
        7
    )

    @Test
    fun recoveredTerminalIntentsStartNewDecisionWhileUnknownOutcomeOnlyReplays() {
        val terminalCases = listOf(
            "PermissionDenied" to FieldRequestStatus.PermissionDenied,
            "Unavailable" to FieldRequestStatus.Unavailable
        )
        val firstTerminal = terminalCases.first()
        val store = Store(
            FieldRequestRecord(
                intent = FieldRequestIntent(
                    key = "key-${firstTerminal.first}",
                    exactBody = "body-${firstTerminal.first}",
                    outcome = firstTerminal.first
                )
            )
        )
        val gateway = Gateway()
        val viewModel = FieldRequestViewModel(gateway, store)
        val baseContext = InstrumentationRegistry.getInstrumentation().targetContext
        val configuration = Configuration(baseContext.resources.configuration).apply {
            setLocale(Locale.ENGLISH)
        }
        val localizedContext = baseContext.createConfigurationContext(configuration)
        val newDecisionLabel = localizedContext.getString(R.string.field_request_new_decision)
        val retryLabel = localizedContext.getString(SharedR.string.field_request_retry)

        compose.setContent {
            val state by viewModel.state.collectAsState()
            CompositionLocalProvider(
                LocalContext provides localizedContext,
                LocalConfiguration provides configuration
            ) {
                FieldRequestScreen(state = state, onBack = {}, viewModel = viewModel)
            }
        }

        terminalCases.forEachIndexed { index, (outcome, expectedStatus) ->
            compose.runOnIdle {
                if (index > 0) {
                    store.record = FieldRequestRecord(
                        intent = FieldRequestIntent(
                            key = "key-$outcome",
                            exactBody = "body-$outcome",
                            outcome = outcome
                        )
                    )
                }
                viewModel.activate(authority)
            }
            compose.waitUntil(timeoutMillis = 5_000) {
                viewModel.state.value.status == expectedStatus
            }

            compose.onNodeWithText(newDecisionLabel).performScrollTo().assertIsDisplayed()
                .performClick()
            compose.waitUntil(timeoutMillis = 5_000) {
                viewModel.state.value.status == FieldRequestStatus.Draft &&
                    store.record.intent == null
            }
            assertNull(store.record.intent)
        }

        val unknownIntent = FieldRequestIntent(
            key = "unknown-key",
            exactBody = "unknown-original-body",
            outcome = "UnknownOutcome"
        )
        compose.runOnIdle {
            store.record = FieldRequestRecord(intent = unknownIntent)
            viewModel.activate(authority)
        }
        compose.waitUntil(timeoutMillis = 5_000) {
            viewModel.state.value.status == FieldRequestStatus.UnknownOutcome
        }

        compose.onNodeWithText(newDecisionLabel).assertDoesNotExist()
        compose.onNodeWithText(retryLabel).performScrollTo().assertIsDisplayed().performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            gateway.sent.size == 1 &&
                viewModel.state.value.status == FieldRequestStatus.UnknownOutcome
        }

        assertEquals(unknownIntent.key, gateway.sent.single().key)
        assertEquals(unknownIntent.exactBody, gateway.sent.single().exactBody)
        assertEquals(unknownIntent.key, store.record.intent?.key)
        assertEquals(unknownIntent.exactBody, store.record.intent?.exactBody)
    }

    private class Store(var record: FieldRequestRecord) : FieldRequestStore {
        override suspend fun load(authority: CommercialAuthority): FieldRequestRead =
            FieldRequestRead.Available(record)

        override suspend fun save(
            authority: CommercialAuthority,
            record: FieldRequestRecord
        ): Boolean {
            this.record = record
            return true
        }
    }

    private class Gateway : FieldRequestGateway {
        val sent = mutableListOf<FieldRequestIntent>()

        override suspend fun quote(
            authority: CommercialAuthority,
            customerId: String,
            productId: String,
            quantity: String
        ): FieldRequestLine? = null

        override suspend fun review(
            authority: CommercialAuthority,
            draft: FieldRequestDraft
        ): FieldRequestReview = FieldRequestReview.Unavailable

        override fun freeze(draft: FieldRequestDraft): String = "{}"

        override suspend fun submit(
            authority: CommercialAuthority,
            intent: FieldRequestIntent
        ): FieldRequestSubmission {
            sent += intent
            return FieldRequestSubmission.UnknownOutcome
        }
    }
}
