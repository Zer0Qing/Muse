package io.zer0.muse.miniphone.ui.relation

import android.content.Context
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import io.zer0.muse.miniphone.R
import io.zer0.muse.miniphone.api.MpCharacter
import io.zer0.muse.miniphone.data.db.entity.MpRelationEdgeEntity
import io.zer0.muse.miniphone.ui.theme.MiniPhoneTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 关系网页：状态由可替换的 Flow 与回调驱动，不碰 Room；两个方向必须分得清。 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MiniPhoneRelationScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val edges = MutableStateFlow(emptyList<MpRelationEdgeEntity>())
    private val saved = mutableListOf<Triple<String, String, String>>()
    private val described = mutableListOf<Pair<String, String>>()
    private val deleted = mutableListOf<String>()
    private var nextId = 0
    private var backClicks = 0

    private fun edge(id: String, fromId: String, toId: String, label: String) =
        MpRelationEdgeEntity(id = id, fromId = fromId, toId = toId, label = label, updatedAt = 1)

    private fun text(res: Int): String = ApplicationProvider.getApplicationContext<Context>().getString(res)

    private fun render(characters: List<MpCharacter> = listOf(MpCharacter(id = "c1", name = "阿离"), MpCharacter(id = "c2", name = "小满"))) {
        compose.setContent {
            MiniPhoneTheme(darkTheme = false) {
                MiniPhoneRelationScreen(
                    edges = edges,
                    characters = characters,
                    onSet = { fromId, toId, label ->
                        saved += Triple(fromId, toId, label)
                        edges.value = edges.value + edge("edge-${nextId++}", fromId, toId, label)
                    },
                    onDescribe = { fromId, toId -> described += fromId to toId },
                    onDelete = { id ->
                        deleted += id
                        edges.value = edges.value.filterNot { it.id == id }
                    },
                    onBack = { backClicks += 1 },
                )
            }
        }
    }

    @Test
    fun `没有角色时展示空态`() {
        render(characters = emptyList())
        compose.onNodeWithTag(MiniPhoneRelationTags.PAGE).assertIsDisplayed()
        compose.onNodeWithTag(MiniPhoneRelationTags.EMPTY).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.mp_relation_no_characters)).assertIsDisplayed()
    }

    @Test
    fun `两个方向的边在各自主区同时可见`() {
        edges.value = listOf(edge("e1", "c1", "c2", "挚友"), edge("e2", "c2", "c1", "宿敌"))
        render()
        // 首屏放不下两个分区：各自主区先滚进可见区，再断言用户真的看得见
        compose.onNodeWithTag(MiniPhoneRelationTags.FROM_SECTION).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.mp_relation_from_section)).assertIsDisplayed()
        compose.onNodeWithTag(MiniPhoneRelationTags.edgeRow("e1")).assertIsDisplayed()
        compose.onNodeWithTag(MiniPhoneRelationTags.LIST).performScrollToIndex(1)
        compose.onNodeWithTag(MiniPhoneRelationTags.TO_SECTION).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.mp_relation_to_section)).assertIsDisplayed()
        compose.onNodeWithTag(MiniPhoneRelationTags.edgeRow("e2")).assertIsDisplayed()
    }

    @Test
    fun `单向边不混进反向 补上反向后才出现`() {
        edges.value = listOf(edge("e1", "c1", "c2", "挚友"))
        render()
        compose.onNodeWithTag(MiniPhoneRelationTags.edgeRow("e1")).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(MiniPhoneRelationTags.FROM_EMPTY).assertDoesNotExist()
        compose.onNodeWithTag(MiniPhoneRelationTags.LIST).performScrollToIndex(1)
        compose.onNodeWithTag(MiniPhoneRelationTags.TO_EMPTY).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.mp_relation_to_empty)).assertIsDisplayed()
        edges.value = edges.value + edge("e2", "c2", "c1", "宿敌")
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag(MiniPhoneRelationTags.edgeRow("e2")).fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(1, compose.onAllNodesWithTag(MiniPhoneRelationTags.edgeRow("e2")).fetchSemanticsNodes().size)
        compose.onNodeWithTag(MiniPhoneRelationTags.edgeRow("e2")).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(MiniPhoneRelationTags.TO_EMPTY).assertDoesNotExist()
        compose.onNodeWithTag(MiniPhoneRelationTags.FROM_EMPTY).assertDoesNotExist()
    }

    @Test
    fun `选对象输入称呼后记下带上所选参数`() {
        render()
        compose.onNodeWithTag(MiniPhoneRelationTags.target("c2")).performClick()
        compose.onNodeWithTag(MiniPhoneRelationTags.LABEL_INPUT).performTextInput("挚友")
        compose.onNodeWithTag(MiniPhoneRelationTags.SET).performClick()
        assertEquals(listOf(Triple("c1", "c2", "挚友")), saved)
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag(MiniPhoneRelationTags.edgeRow("edge-0")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag(MiniPhoneRelationTags.edgeRow("edge-0")).assertIsDisplayed()
    }

    @Test
    fun `空白称呼不会记下`() {
        render()
        compose.onNodeWithTag(MiniPhoneRelationTags.target("c2")).performClick()
        compose.onNodeWithTag(MiniPhoneRelationTags.LABEL_INPUT).performTextInput("   ")
        compose.onNodeWithTag(MiniPhoneRelationTags.SET).performClick()
        assertEquals(0, saved.size)
    }

    @Test
    fun `删除边触发回调且分区回到空态`() {
        edges.value = listOf(edge("e1", "c1", "c2", "挚友"))
        render()
        compose.onNodeWithTag(MiniPhoneRelationTags.delete("e1")).performClick()
        assertEquals(listOf("e1"), deleted)
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag(MiniPhoneRelationTags.FROM_EMPTY).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(text(R.string.mp_relation_from_empty)).assertIsDisplayed()
    }

    @Test
    fun `让他自己说带上正确的方向`() {
        render()
        compose.onNodeWithTag(MiniPhoneRelationTags.target("c2")).performClick()
        compose.onNodeWithTag(MiniPhoneRelationTags.DESCRIBE).performClick()
        assertEquals(listOf("c1" to "c2"), described)
    }

    @Test
    fun `点返回触发回调`() {
        render()
        compose.onNodeWithTag(MiniPhoneRelationTags.BACK).performClick()
        assertEquals(1, backClicks)
    }
}
