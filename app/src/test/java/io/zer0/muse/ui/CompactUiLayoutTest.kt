package io.zer0.muse.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.zer0.muse.ui.common.feedback.MUSE_DIALOG_CARD_TAG
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.common.form.MuseBottomSheet
import io.zer0.muse.ui.common.surface.CardGroup
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CompactUiLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun shortDialogActionsShareARowAndCardIsBounded() {
        compose.setContent {
            MaterialTheme {
                MuseDialog(
                    title = "Delete conversation",
                    onDismissRequest = {},
                    content = { Text("Cannot undo") },
                    dismissText = "Cancel",
                    confirmText = "Delete",
                    onConfirm = {},
                )
            }
        }
        val cancel = compose.onNodeWithText("Cancel").fetchSemanticsNode().boundsInRoot
        val confirm = compose.onNodeWithText("Delete").fetchSemanticsNode().boundsInRoot
        assertEquals(cancel.center.y, confirm.center.y, 1f)
        assertTrue(cancel.center.x < confirm.center.x)
        val card = compose.onNodeWithTag(MUSE_DIALOG_CARD_TAG).fetchSemanticsNode().boundsInRoot
        val density = compose.density.density
        assertTrue(card.width <= 320f * density + 1f)
    }

    @Test
    fun enlargedFontFallsBackToVerticalActions() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                MaterialTheme {
                    MuseDialog(
                        onDismissRequest = {},
                        content = { Text("Body") },
                        dismissText = "Cancel",
                        confirmText = "Confirm",
                        onConfirm = {},
                    )
                }
            }
        }
        val cancel = compose.onNodeWithText("Cancel").fetchSemanticsNode().boundsInRoot
        val confirm = compose.onNodeWithText("Confirm").fetchSemanticsNode().boundsInRoot
        assertTrue(cancel.bottom < confirm.top)
    }

    @Test
    fun providerDetectionStatusDoesNotCollapseTitle() {
        compose.setContent {
            MaterialTheme {
                Box(Modifier.width(300.dp)) {
                    io.zer0.muse.ui.settings.ProviderRow(
                        config = io.zer0.ai.core.ProviderConfig("relay", "Test relay provider"),
                        isActive = false,
                        onEdit = {},
                        onActivate = {},
                        onDelete = {},
                        testStatus = io.zer0.muse.ui.settings.ProviderTestStatus.Success(123456, 100),
                    )
                }
            }
        }
        val name = compose.onNodeWithText("Test relay provider").fetchSemanticsNode().boundsInRoot
        assertTrue("title must retain space with health result visible", name.width > 150f * compose.density.density)
    }

    @Test
    fun groupTitleAlignsWithNoIconHeadline() {
        compose.setContent {
            MaterialTheme {
                Box(Modifier.width(320.dp)) {
                    CardGroup(title = { Text("Section") }) {
                        item(headlineContent = { Text("Assistant name") })
                    }
                }
            }
        }
        val title = compose.onNodeWithText("Section").fetchSemanticsNode().boundsInRoot
        val headline = compose.onNodeWithText("Assistant name").fetchSemanticsNode().boundsInRoot
        assertEquals(title.left, headline.left, 1f)
    }

    @Test
    fun longSheetContentHasFiniteScrollableViewport() {
        compose.setContent {
            MaterialTheme {
                MuseBottomSheet(onDismissRequest = {}, maxHeightFraction = 0.6f) {
                    Box(Modifier.height(900.dp).testTag("sheet-long-content")) { Text("Sheet body") }
                }
            }
        }
        compose.onNodeWithText("Sheet body").assertIsDisplayed()
        // 长内容由同一窗口的有限高度滚动视口处理；测量过程不得出现无限约束崩溃。
        val body = compose.onNodeWithTag("sheet-long-content", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue(body.height > 0f)
    }

    @Test
    fun sheetIsNotDismissedDuringEntrance() {
        val dismissed = AtomicBoolean(false)
        compose.setContent {
            MaterialTheme {
                MuseBottomSheet(onDismissRequest = { dismissed.set(true) }) { Text("Short sheet") }
            }
        }
        compose.onNodeWithText("Short sheet").assertIsDisplayed()
        assertFalse(dismissed.get())
    }

    @Test
    fun sheetDismissesAfterOutsideTap() {
        val dismissed = AtomicBoolean(false)
        compose.setContent {
            MaterialTheme {
                MuseBottomSheet(onDismissRequest = { dismissed.set(true) }) { Text("Dismissible sheet") }
            }
        }
        compose.onNodeWithText("Dismissible sheet").assertIsDisplayed()
        compose.onNodeWithTag("muse-sheet-scrim").performTouchInput { click(Offset(5f, 5f)) }
        compose.waitForIdle()
        assertTrue(dismissed.get())
    }

    @Test
    fun multiImageTilesDoNotReserveFullSizeImageHeight() {
        compose.setContent {
            MaterialTheme {
                Column(Modifier.width(320.dp)) {
                    Row(Modifier.fillMaxWidth()) {
                        repeat(2) {
                            GeneratedImageCard(
                                imageUri = "data:image/jpeg;base64,",
                                onPreview = {},
                                onSave = {},
                                compact = true,
                                modifier = Modifier.weight(1f).testTag("tile-$it"),
                            )
                        }
                    }
                }
            }
        }
        val tile = compose.onNodeWithTag("tile-0").fetchSemanticsNode().boundsInRoot
        assertTrue("grid tile must fit a square preview plus controls", tile.height <= tile.width + 64f * compose.density.density)
    }
}
