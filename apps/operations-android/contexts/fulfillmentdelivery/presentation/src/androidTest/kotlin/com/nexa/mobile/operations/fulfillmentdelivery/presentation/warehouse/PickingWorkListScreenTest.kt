package com.nexa.mobile.operations.fulfillmentdelivery.presentation.warehouse

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nexa.mobile.operations.fulfillmentdelivery.application.model.warehouse.PickingWorkListCacheItem
import com.nexa.mobile.operations.fulfillmentdelivery.application.model.warehouse.PickingWorkListCacheProjection
import java.time.Instant
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PickingWorkListScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun cachedWorkItemDoesNotExposeSelectionAction() {
        var selectedFulfillment: String? = null
        composeRule.setContent {
            MaterialTheme {
                PickingWorkListScreen(
                    state = PickingWorkListUiState(
                        status = PickingWorkListStatus.Cached,
                        totalItems = 1,
                        cachedProjection = cachedProjection()
                    ),
                    onBack = {},
                    onReload = {},
                    onPreviousPage = {},
                    onNextPage = {},
                    onSelectFulfillment = { selectedFulfillment = it }
                )
            }
        }

        composeRule.onNodeWithText("ORDER-2048")
            .assertHasNoClickAction()
        composeRule.runOnIdle { assertNull(selectedFulfillment) }
    }

    private fun cachedProjection() = PickingWorkListCacheProjection(
        items = listOf(
            PickingWorkListCacheItem(
                fulfillmentId = "b8c24a46-57d9-4f64-8fa7-6a641b413005",
                salesOrderId = "b8c24a46-57d9-4f64-8fa7-6a641b413006",
                orderNumber = "ORDER-2048",
                status = "PICKED",
                version = 12,
                lineCount = 1
            )
        ),
        page = 0,
        size = 25,
        totalItems = 1,
        asOf = Instant.parse("2026-11-16T12:00:00Z")
    )
}
