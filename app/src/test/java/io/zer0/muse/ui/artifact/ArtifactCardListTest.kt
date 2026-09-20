package io.zer0.muse.ui.artifact

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.zer0.muse.data.artifact.ArtifactEntity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Phase 2: ArtifactCardList 超量折叠 "+N" 行为测试。
 *
 * 屏幕宽度放宽到 1600dp,LazyRow 会把全部卡片纳入组合,避免懒加载视口导致的假阴性。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w1600dp-h1000dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ArtifactCardListTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun artifact(index: Int) = ArtifactEntity(
        id = "artifact-$index",
        sessionId = "session-1",
        messageId = "message-1",
        title = "产物$index",
        type = "code",
        content = "val x = $index",
        language = "kotlin",
    )

    @Test
    fun `超过五个时尾部显示加 N 折叠卡片`() {
        composeTestRule.setContent {
            MaterialTheme {
                ArtifactCardList(artifacts = (1..7).map(::artifact), onArtifactClick = {})
            }
        }
        composeTestRule.onNodeWithText("产物5").assertExists()
        composeTestRule.onNodeWithText("产物6").assertDoesNotExist()
        composeTestRule.onNodeWithText("+2").assertExists()
    }

    @Test
    fun `点击加 N 后展开全部产物`() {
        composeTestRule.setContent {
            MaterialTheme {
                ArtifactCardList(artifacts = (1..7).map(::artifact), onArtifactClick = {})
            }
        }
        composeTestRule.onNodeWithText("+2").performClick()
        composeTestRule.onNodeWithText("产物6").assertExists()
        composeTestRule.onNodeWithText("产物7").assertExists()
        composeTestRule.onNodeWithText("+2").assertDoesNotExist()
    }

    @Test
    fun `五个及以内不显示折叠卡片`() {
        composeTestRule.setContent {
            MaterialTheme {
                ArtifactCardList(artifacts = (1..5).map(::artifact), onArtifactClick = {})
            }
        }
        composeTestRule.onNodeWithText("产物5").assertExists()
        composeTestRule.onNodeWithText("+0").assertDoesNotExist()
    }

    @Test
    fun `点击卡片回调对应产物`() {
        var clicked: ArtifactEntity? = null
        composeTestRule.setContent {
            MaterialTheme {
                ArtifactCardList(
                    artifacts = listOf(artifact(1)),
                    onArtifactClick = { clicked = it },
                )
            }
        }
        composeTestRule.onNodeWithText("产物1").performClick()
        org.junit.Assert.assertEquals("artifact-1", clicked?.id)
    }
}
