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
import com.nexa.mobile.operations.salescommitment.application.commercial.DirectOrderGateway
import com.nexa.mobile.operations.salescommitment.application.commercial.DirectOrderRead
import com.nexa.mobile.operations.salescommitment.application.commercial.DirectOrderReview
import com.nexa.mobile.operations.salescommitment.application.commercial.DirectOrderStore
import com.nexa.mobile.operations.salescommitment.application.commercial.DirectOrderSubmission
import com.nexa.mobile.operations.salescommitment.application.model.commercial.DirectOrderIntent
import com.nexa.mobile.operations.salescommitment.application.model.commercial.DirectOrderRecord
import com.nexa.mobile.operations.salescommitment.domain.model.commercial.DirectOrderDraft
import com.nexa.mobile.operations.salescommitment.domain.model.commercial.DirectOrderLine
import com.nexa.mobile.operations.salescommitment.presentation.R
import com.nexa.mobile.operations.tenantaccessgovernance.application.publicapi.CommercialAuthority
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

class DirectOrderScreenRecoveryTest {
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
            "PermissionDenied" to DirectOrderStatus.PermissionDenied,
            "Unavailable" to DirectOrderStatus.Unavailable
        )
        val firstTerminal = terminalCases.first()
        val store = Store(
            DirectOrderRecord(
                intent = DirectOrderIntent(
                    key = "key-${firstTerminal.first}",
                    exactBody = "body-${firstTerminal.first}",
                    outcome = firstTerminal.first
                )
            )
        )
        val gateway = Gateway()
        val viewModel = DirectOrderViewModel(gateway, store)
        val baseContext = InstrumentationRegistry.getInstrumentation().targetContext
        val configuration = Configuration(baseContext.resources.configuration).apply {
            setLocale(Locale.ENGLISH)
        }
        val localizedContext = baseContext.createConfigurationContext(configuration)
        val newDecisionLabel = localizedContext.getString(R.string.direct_order_new_decision)
        val retryLabel = localizedContext.getString(SharedR.string.same_submission_retry)

        compose.setContent {
            val state by viewModel.state.collectAsState()
            CompositionLocalProvider(
                LocalContext provides localizedContext,
                LocalConfiguration provides configuration
            ) {
                DirectOrderScreen(state = state, onBack = {}, viewModel = viewModel)
            }
        }

        terminalCases.forEachIndexed { index, (outcome, expectedStatus) ->
            compose.runOnIdle {
                if (index > 0) {
                    store.record = DirectOrderRecord(
                        intent = DirectOrderIntent(
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
                viewModel.state.value.status == DirectOrderStatus.Draft &&
                    store.record.intent == null
            }
            assertNull(store.record.intent)
        }

        val unknownIntent = DirectOrderIntent(
            key = "unknown-key",
            exactBody = "unknown-original-body",
            outcome = "UnknownOutcome"
        )
        compose.runOnIdle {
            store.record = DirectOrderRecord(intent = unknownIntent)
            viewModel.activate(authority)
        }
        compose.waitUntil(timeoutMillis = 5_000) {
            viewModel.state.value.status == DirectOrderStatus.UnknownOutcome
        }

        compose.onNodeWithText(newDecisionLabel).assertDoesNotExist()
        compose.onNodeWithText(retryLabel).performScrollTo().assertIsDisplayed().performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            gateway.sent.size == 1 &&
                viewModel.state.value.status == DirectOrderStatus.UnknownOutcome
        }

        assertEquals(unknownIntent.key, gateway.sent.single().key)
        assertEquals(unknownIntent.exactBody, gateway.sent.single().exactBody)
        assertEquals(unknownIntent.key, store.record.intent?.key)
        assertEquals(unknownIntent.exactBody, store.record.intent?.exactBody)
    }

    private class Store(var record: DirectOrderRecord) : DirectOrderStore {
        override suspend fun load(authority: CommercialAuthority): DirectOrderRead =
            DirectOrderRead.Available(record)

        override suspend fun save(
            authority: CommercialAuthority,
            record: DirectOrderRecord
        ): Boolean {
            this.record = record
            return true
        }
    }

    private class Gateway : DirectOrderGateway {
        val sent = mutableListOf<DirectOrderIntent>()

        override suspend fun quote(
            authority: CommercialAuthority,
            customerId: String,
            productId: String,
            quantity: String
        ): DirectOrderLine? = null

        override suspend fun review(
            authority: CommercialAuthority,
            draft: DirectOrderDraft
        ): DirectOrderReview = DirectOrderReview.Unavailable

        override fun freeze(draft: DirectOrderDraft): String = "{}"

        override suspend fun submit(
            authority: CommercialAuthority,
            intent: DirectOrderIntent
        ): DirectOrderSubmission {
            sent += intent
            return DirectOrderSubmission.UnknownOutcome
        }
    }
}
