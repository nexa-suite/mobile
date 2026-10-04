package com.nexa.mobile.operations

import android.os.Debug
import android.util.Log
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToIndex
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nexa.mobile.operations.core.designsystem.CatalogImageRegistry
import com.nexa.mobile.operations.core.designsystem.NexaCatalogImage
import com.nexa.mobile.operations.core.designsystem.NexaSizes
import com.nexa.mobile.operations.core.designsystem.OperationsTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CatalogImageMemoryTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun scrollsAllCanonicalImagesWithoutUnboundedDecodedBitmapGrowth() {
        val fileNames = CatalogImageRegistry.canonicalFileNames
        assertEquals(102, fileNames.size)
        val beforePssKb = Debug.getPss()

        composeRule.setContent {
            OperationsTheme {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("catalog-image-memory-list")
                ) {
                    items(fileNames, key = { it }) { fileName ->
                        NexaCatalogImage(
                            fileName = fileName,
                            modifier = Modifier.size(NexaSizes.catalogImage)
                        )
                    }
                }
            }
        }

        fileNames.indices.forEach { index ->
            composeRule.onNodeWithTag("catalog-image-memory-list").performScrollToIndex(index)
            composeRule.waitForIdle()
        }

        composeRule.onNodeWithTag("catalog-image-memory-list").assertIsDisplayed()
        val afterPssKb = Debug.getPss()
        Log.i(
            "CatalogImageMemoryTest",
            "canonicalImages=${fileNames.size} beforePssKb=$beforePssKb " +
                "afterPssKb=$afterPssKb deltaPssKb=${afterPssKb - beforePssKb} " +
                "cacheBudgetBytes=${8 * 1024 * 1024}"
        )
        assertTrue(
            "catalog image scroll increased process PSS by more than 160 MiB",
            afterPssKb - beforePssKb < 160 * 1024
        )
    }
}
