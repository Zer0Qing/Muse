package io.zer0.muse.ui.artifact

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.zer0.muse.R
import io.zer0.muse.data.artifact.ArtifactEntity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Phase 2: ArtifactCardList 超量折叠行为测试。
 *
 * v2.1.0: 折叠阈值随产物卡范式调整(全宽大卡纵列,默认最多展示前 3 张);
 * 折叠文案改为资源字符串,按当前 locale 动态取,保证断言与渲染一致。
 * 屏幕宽度放宽到 1600dp,避免视图被裁切导致的假阴性。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w1600dp-h1000dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ArtifactCardListTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    /** 与渲染侧同一 locale 取折叠文案(不硬编码中文/英文)。 */
    private val context: Context = RuntimeEnvironment.getApplication()

    private fun moreText(hidden: Int): String = context.getString(R.string.artifact_more_count, hidden)

    private fun artifact(index: Int) =
        ArtifactEntity(
            id = "artifact-$index",
            sessionId = "session-1",
            messageId = "message-1",
            title = "产物$index",
            type = "code",
            content = "val x = $index",
            language = "kotlin",
        )

    @Test
    fun `超过三个时尾部显示折叠条`() {
        composeTestRule.setContent {
            MaterialTheme {
                ArtifactCardList(artifacts = (1..7).map(::artifact), onArtifactClick = {})
            }
        }
        composeTestRule.onNodeWithText("产物3").assertExists()
        composeTestRule.onNodeWithText("产物4").assertDoesNotExist()
        composeTestRule.onNodeWithText(moreText(4)).assertExists()
    }

    @Test
    fun `点击折叠条后展开全部产物`() {
        composeTestRule.setContent {
            MaterialTheme {
                ArtifactCardList(artifacts = (1..7).map(::artifact), onArtifactClick = {})
            }
        }
        composeTestRule.onNodeWithText(moreText(4)).performClick()
        composeTestRule.onNodeWithText("产物4").assertExists()
        composeTestRule.onNodeWithText("产物7").assertExists()
        composeTestRule.onNodeWithText(moreText(4)).assertDoesNotExist()
    }

    @Test
    fun `三个及以内不显示折叠卡片`() {
        composeTestRule.setContent {
            MaterialTheme {
                ArtifactCardList(artifacts = (1..3).map(::artifact), onArtifactClick = {})
            }
        }
        composeTestRule.onNodeWithText("产物3").assertExists()
        composeTestRule.onNodeWithText(moreText(1)).assertDoesNotExist()
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
