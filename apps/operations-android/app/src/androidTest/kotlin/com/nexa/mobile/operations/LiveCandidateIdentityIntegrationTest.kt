package com.nexa.mobile.operations

import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nexa.mobile.operations.feature.access.SignInResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LiveCandidateIdentityIntegrationTest {
    @get:Rule val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun nativeIdentityGatewayEstablishesOneScopedWorkContextAgainstCandidateApi() {
        val arguments = InstrumentationRegistry.getArguments()
        val identifier = arguments.getString("nexaLiveIdentifier")
        val password = arguments.getString("nexaLivePassword")
        assumeTrue(
            "candidate credentials are needed for the opt-in live test",
            !identifier.isNullOrBlank() && !password.isNullOrBlank()
        )

        val result = runBlocking {
            composeRule.activity.operationsGateway.signIn(identifier!!, password!!)
        }
        assertTrue(
            "native identity gateway result was ${result::class.simpleName}",
            result is SignInResult.Authenticated
        )

        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodes(
                hasContentDescription("Contexto actual:", substring = true),
                useUnmergedTree = true
            ).fetchSemanticsNodes().isNotEmpty()
        }
    }
}
