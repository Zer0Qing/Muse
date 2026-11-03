package io.zer0.muse.ui.common.form

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LinearScrollGestureIdleSnapshotTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `message map reads list layout only when long press activates navigation`() {
        val listState = LazyListState()
        var layoutReads = 0
        val snapshot =
            LinearScrollSnapshot(
                visibleItems = emptyList(),
                totalItemsCount = 0,
                viewportSizePx = 300,
                viewportEndOffsetPx = 300,
                firstVisibleItemIndex = 0,
                firstVisibleItemScrollOffset = 0,
            )

        compose.setContent {
            MaterialTheme {
                Box(Modifier.size(300.dp)) {
                    LinearScrollGesture(
                        listState = listState,
                        modifier = Modifier.testTag("linear-scroll"),
                        snapshotSource = {
                            layoutReads += 1
                            snapshot
                        },
                    )
                }
            }
        }

        compose.waitForIdle()
        assertEquals("idle composition must not observe LazyListState.layoutInfo", 0, layoutReads)

        compose.onNodeWithTag("linear-scroll").performTouchInput { longClick() }
        compose.waitForIdle()

        assertEquals("long press must snapshot the list once before navigation", 1, layoutReads)
    }
}
