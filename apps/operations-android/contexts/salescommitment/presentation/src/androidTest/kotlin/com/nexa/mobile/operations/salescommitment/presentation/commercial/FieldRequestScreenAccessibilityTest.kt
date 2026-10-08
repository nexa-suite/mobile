package com.nexa.mobile.operations.salescommitment.presentation.commercial

import android.content.res.Configuration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.platform.app.InstrumentationRegistry
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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class FieldRequestScreenAccessibilityTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun languageChangeLocalizesRequestAndErrorWhileLabelsAndTraversalRemainAvailable() {
        var locale by mutableStateOf(Locale.forLanguageTag("es"))
        val viewModel = FieldRequestViewModel(Gateway(), Store())
        val state = FieldRequestState(status = FieldRequestStatus.Unavailable)
        val baseContext = InstrumentationRegistry.getInstrumentation().targetContext
        val spanishConfiguration = Configuration(baseContext.resources.configuration).apply {
            setLocale(Locale.forLanguageTag("es"))
        }
        val spanishContext = baseContext.createConfigurationContext(spanishConfiguration)
        val englishConfiguration = Configuration(baseContext.resources.configuration).apply {
            setLocale(Locale.ENGLISH)
        }
        val englishContext = baseContext.createConfigurationContext(englishConfiguration)

        compose.setContent {
            val configuration = Configuration(baseContext.resources.configuration).apply {
                setLocale(locale)
            }
            val localizedContext = baseContext.createConfigurationContext(configuration)
            CompositionLocalProvider(
                LocalContext provides localizedContext,
                LocalConfiguration provides configuration
            ) {
                FieldRequestScreen(state = state, onBack = {}, viewModel = viewModel)
            }
        }

        val spanishStatus =
            "Información vigente no disponible. El borrador sigue sin confirmar."
        compose.onNodeWithText(spanishContext.getString(R.string.field_request_title))
            .assertIsDisplayed()
        compose.onNodeWithText(spanishStatus).assertIsDisplayed()
        compose.onNodeWithText("Referencia del cliente").assertIsDisplayed()
        assertTrue(compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty())
        assertTrue(
            compose.onAllNodes(
                SemanticsMatcher.expectValue(SemanticsProperties.IsTraversalGroup, true)
            ).fetchSemanticsNodes().isNotEmpty()
        )
        assertEquals(
            LiveRegionMode.Polite,
            compose.onNodeWithText(spanishStatus).fetchSemanticsNode().config[
                SemanticsProperties.LiveRegion
            ]
        )

        compose.runOnIdle { locale = Locale.ENGLISH }

        compose.onNodeWithText(englishContext.getString(R.string.field_request_title))
            .assertIsDisplayed()
        compose.onNodeWithText(
            "Current information is unavailable. The draft remains unconfirmed."
        )
            .assertIsDisplayed()
        compose.onNodeWithText("Customer reference").assertIsDisplayed()
    }

    private class Store : FieldRequestStore {
        override suspend fun load(authority: CommercialAuthority): FieldRequestRead =
            FieldRequestRead.Unavailable

        override suspend fun save(
            authority: CommercialAuthority,
            record: FieldRequestRecord
        ): Boolean = false
    }

    private class Gateway : FieldRequestGateway {
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
        ): FieldRequestSubmission = FieldRequestSubmission.UnknownOutcome
    }
}
