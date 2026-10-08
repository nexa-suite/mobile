package com.nexa.mobile.operations

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.view.inputmethod.InputMethodManager
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nexa.mobile.operations.core.auth.session.SessionState
import com.nexa.mobile.operations.fulfillmentdelivery.presentation.R as FulfillmentR
import com.nexa.mobile.operations.tenantaccessgovernance.presentation.access.AccessStage
import com.nexa.mobile.operations.tenantaccessgovernance.presentation.access.AccessViewModel
import java.io.File
import java.io.FileOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoleWireflowCaptureTest {
    @get:Rule val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun captureConfiguredRoleHomeAndEntries() {
        val arguments = InstrumentationRegistry.getArguments()
        val identifier = arguments.getString("nexaCaptureIdentifier")
        val password = arguments.getString("nexaCapturePassword")
        val roleSlug = arguments.getString("nexaCaptureRole")
        val entry1 = arguments.getString("nexaCaptureEntry1")
        val entry2 = arguments.getString("nexaCaptureEntry2")?.takeIf(String::isNotBlank)
        val captureEnabled = roleWireflowCaptureEnabled(
            arguments.getString(CAPTURE_ARGUMENT)
        )

        assumeTrue(
            "capture needs configured role credentials and first entry label",
            !identifier.isNullOrBlank() && !password.isNullOrBlank() &&
                !roleSlug.isNullOrBlank() && !entry1.isNullOrBlank()
        )
        assertTrue("role slug must be safe for output filenames", roleSlug!!.matches(ROLE_SLUG))
        assertTrue("configured entries must be distinct", entry2 == null || entry1 != entry2)

        grantLocalNetworkOnlyWhenExplicit(
            arguments.getString("nexaCaptureGrantLocalNetwork") == "true"
        )
        signIn(identifier!!, password!!)
        waitForAuthorizedHome()
        captureScreenWhenEnabled(captureEnabled, "$roleSlug-home.png")

        openEntryAndCapture(
            entry1!!,
            "$roleSlug-entry-1.png",
            roleSlug == BOM_ROLE_SLUG,
            captureEnabled
        )
        if (entry2 != null) {
            returnToAuthorizedHome(entry2)
            openEntryAndCapture(entry2, "$roleSlug-entry-2.png", captureEnabled = captureEnabled)
        } else if (roleSlug == BOM_ROLE_SLUG) {
            returnViaBusinessExceptionsBack(entry1)
        }
    }

    @Test
    fun captureFlagDefaultsToEnabledAndOnlyExplicitFalseDisables() {
        assertTrue(roleWireflowCaptureEnabled(null))
        assertTrue(roleWireflowCaptureEnabled(""))
        assertTrue(roleWireflowCaptureEnabled("true"))
        assertTrue(roleWireflowCaptureEnabled("TRUE"))
        assertEquals(false, roleWireflowCaptureEnabled("false"))
    }

    private fun signIn(identifier: String, password: String) {
        composeRule.waitUntil(timeoutMillis = WAIT_MILLIS) {
            composeRule.onAllNodes(hasSetTextAction(), useUnmergedTree = true)
                .fetchSemanticsNodes().size == 2
        }
        val fields = composeRule.onAllNodes(hasSetTextAction(), useUnmergedTree = true)
        fields.assertCountEquals(2)
        composeRule.onNodeWithText("Identificador").assertIsDisplayed()
        composeRule.onNodeWithText("Contraseña").assertIsDisplayed()
        fields[0].performTextInput(identifier)
        fields[1].performTextInput(password)
        dismissKeyboard()
        composeRule.onNodeWithText("Iniciar sesión")
            .performScrollTo()
            .performClick()
    }

    private fun waitForAuthorizedHome() {
        composeRule.waitUntil(timeoutMillis = WAIT_MILLIS) {
            hasCurrentProtectedContext()
        }
        composeRule.onNode(CURRENT_CONTEXT).assertIsDisplayed()
    }

    private fun returnToAuthorizedHome(nextEntry: String) {
        composeRule.runOnIdle {
            composeRule.activity.onBackPressedDispatcher.onBackPressed()
        }
        composeRule.waitUntil(timeoutMillis = WAIT_MILLIS) {
            hasCurrentProtectedContext() &&
                composeRule.onAllNodes(entryAction(nextEntry)).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNode(CURRENT_CONTEXT).assertIsDisplayed()
        composeRule.onNode(entryAction(nextEntry)).performScrollTo().assertIsDisplayed()
    }

    private fun openEntryAndCapture(
        label: String,
        filename: String,
        businessExceptionsRoute: Boolean = false,
        captureEnabled: Boolean = true
    ) {
        val entry = entryAction(label)
        if (businessExceptionsRoute) {
            assertTrue(
                "current server-authorized context must include delivery.exception.read",
                hasBOMReadPermission()
            )
        }
        composeRule.onNode(entry).performScrollTo().assertIsDisplayed().performClick()
        composeRule.waitUntil(timeoutMillis = WAIT_MILLIS) {
            hasCurrentProtectedContext() && if (businessExceptionsRoute) {
                hasBusinessExceptionsTitle()
            } else {
                hasConnectedBackAction()
            }
        }
        assertTrue(
            "current server-authorized context must remain active",
            hasCurrentProtectedContext()
        )
        if (businessExceptionsRoute) {
            composeRule.onNodeWithText(businessExceptionsTitle()).assertIsDisplayed()
        }
        captureScreenWhenEnabled(captureEnabled, filename)
    }

    private fun returnViaBusinessExceptionsBack(entry: String) {
        composeRule.onNodeWithText(businessExceptionsBackLabel())
            .performScrollTo()
            .assertIsDisplayed()
            .assertHasClickAction()
            .performClick()
        composeRule.waitUntil(timeoutMillis = WAIT_MILLIS) {
            hasCurrentProtectedContext() && hasEntry(entry)
        }
        assertTrue(
            "server-authorized context must remain active after returning",
            hasCurrentProtectedContext()
        )
        composeRule.onNode(CURRENT_CONTEXT).assertIsDisplayed()
        composeRule.onNode(entryAction(entry)).performScrollTo().assertIsDisplayed()
    }

    private fun hasCurrentProtectedContext(): Boolean {
        val access = ViewModelProvider(composeRule.activity)[AccessViewModel::class.java]
            .state.value
        val active = access.activeContext
        return isSessionActive() && access.stage == AccessStage.WorkAuthorized &&
            active != null && active.isCurrent && active.verifiedAuthority != null
    }

    private fun hasBOMReadPermission(): Boolean =
        ViewModelProvider(composeRule.activity)[AccessViewModel::class.java]
            .state.value.activeContext?.verifiedAuthority?.permissions
            ?.contains(BOM_READ_PERMISSION) == true

    private fun hasEntry(label: String): Boolean =
        composeRule.onAllNodes(entryAction(label)).fetchSemanticsNodes().isNotEmpty()

    private fun hasBusinessExceptionsTitle(): Boolean =
        composeRule.onAllNodes(hasText(businessExceptionsTitle()))
            .fetchSemanticsNodes().isNotEmpty()

    private fun businessExceptionsTitle(): String =
        composeRule.activity.getString(FulfillmentR.string.bom_exceptions_title)

    private fun businessExceptionsBackLabel(): String =
        composeRule.activity.getString(FulfillmentR.string.bom_exceptions_back)

    private fun hasConnectedBackAction(): Boolean {
        val backIcon = composeRule.onAllNodes(
            hasClickAction() and hasContentDescription("Volver"),
            useUnmergedTree = true
        ).fetchSemanticsNodes().isNotEmpty()
        val backText = composeRule.onAllNodes(hasClickAction() and hasText("Volver"))
            .fetchSemanticsNodes().isNotEmpty()
        return backIcon || backText
    }

    private fun isSessionActive(): Boolean =
        ViewModelProvider(composeRule.activity)[RootViewModel::class.java].state.value ==
            SessionState.Active

    private fun dismissKeyboard() {
        composeRule.runOnIdle {
            val activity = composeRule.activity
            val focused = activity.currentFocus ?: activity.window.decorView
            activity.getSystemService(InputMethodManager::class.java)
                .hideSoftInputFromWindow(focused.windowToken, 0)
            focused.clearFocus()
        }
        composeRule.waitForIdle()
    }

    private fun grantLocalNetworkOnlyWhenExplicit(explicitGrant: Boolean) {
        val activity = composeRule.activity
        if (!requiresLocalNetworkPermission(
                Build.VERSION.SDK_INT,
                BuildConfig.DEBUG,
                BuildConfig.API_BASE_URL
            ) || activity.checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        assumeTrue(
            "local API permission must be pregranted or explicitly enabled for this capture",
            explicitGrant
        )
        InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(
            activity.packageName,
            Manifest.permission.ACCESS_LOCAL_NETWORK
        )
        assertEquals(
            PackageManager.PERMISSION_GRANTED,
            activity.checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK)
        )
    }

    private fun captureScreenWhenEnabled(enabled: Boolean, filename: String) {
        if (!enabled) return
        composeRule.waitForIdle()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        val bitmap = composeRule.onRoot().captureToImage().asAndroidBitmap()
        val externalFiles = requireNotNull(composeRule.activity.getExternalFilesDir(null))
        val directory = File(externalFiles, CAPTURE_DIRECTORY)
        assertTrue(
            "capture directory must be available",
            directory.isDirectory || directory.mkdirs()
        )
        val output = File(directory, filename)
        FileOutputStream(output).use { stream ->
            assertTrue(
                "screen bitmap must encode as PNG",
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
            )
        }
        assertTrue("PNG capture must be nonempty", output.isFile && output.length() > 0)
    }

    private fun entryAction(label: String) = hasText(label, substring = false) and hasClickAction()

    private fun roleWireflowCaptureEnabled(argument: String?): Boolean = argument != "false"

    private companion object {
        const val WAIT_MILLIS = 15_000L
        const val CAPTURE_DIRECTORY = "wireflow-captures"
        const val CAPTURE_ARGUMENT = "nexaLiveCapture"
        const val BOM_READ_PERMISSION = "delivery.exception.read"
        const val BOM_ROLE_SLUG = "bom"
        val CURRENT_CONTEXT = hasContentDescription("Contexto actual:", substring = true)
        val ROLE_SLUG = Regex("[a-z-]+")
    }
}
