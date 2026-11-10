package io.zer0.muse.miniphone.ui.misc

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
import io.zer0.muse.miniphone.data.db.entity.MpWardrobeItemEntity
import io.zer0.muse.miniphone.data.misc.MpWardrobeStore
import io.zer0.muse.miniphone.ui.theme.MiniPhoneTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 衣橱页：状态由可替换的 Flow 与回调驱动，不碰 Room。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@OptIn(ExperimentalTestApi::class)
class MiniPhoneWardrobeScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val rows = MutableStateFlow(emptyList<MpWardrobeItemEntity>())
    private val added = mutableListOf<List<String>>()
    private val equipped = mutableListOf<Pair<String, String>>()
    private val deleted = mutableListOf<String>()
    private var backClicks = 0
    private var nextId = 0

    private fun res(id: Int): String = ApplicationProvider.getApplicationContext<Context>().getString(id)

    private fun item(id: String, name: String, kind: String, equipped: Int) = MpWardrobeItemEntity(
        id = id,
        characterId = "c1",
        name = name,
        kind = kind,
        uri = "",
        equipped = equipped,
        updatedAt = 1,
    )

    private fun render() {
        compose.setContent {
            MiniPhoneTheme(darkTheme = false) {
                MiniPhoneWardrobeScreen(
                    itemsFor = { rows },
                    characters = listOf(MpCharacter(id = "c1", name = "阿离")),
                    onAdd = { cid, name, kind, uri ->
                        added += listOf(cid, name, kind, uri)
                        rows.value = rows.value + item("w-${nextId++}", name, kind, 0)
                    },
                    onEquip = { cid, id -> equipped += cid to id },
                    onDelete = { id ->
                        deleted += id
                        rows.value = rows.value.filterNot { it.id == id }
                    },
                    onBack = { backClicks += 1 },
                )
            }
        }
    }

    @Test
    fun `空态提示可见`() {
        render()
        compose.onNodeWithTag(MiniPhoneMiscTags.Wardrobe.EMPTY).assertIsDisplayed()
        compose.onNodeWithTag(MiniPhoneMiscTags.Wardrobe.PAGE).assertIsDisplayed()
        compose.onNodeWithText(res(R.string.mp_wardrobe_empty)).assertIsDisplayed()
    }

    @Test
    fun `选角色与种类后添加会带上所选参数`() {
        render()
        compose.onNodeWithTag(MiniPhoneMiscTags.Wardrobe.character("c1")).performClick()
        compose.onNodeWithTag(MiniPhoneMiscTags.Wardrobe.kind(MpWardrobeStore.KIND_DRESS)).performClick()
        compose.onNodeWithTag(MiniPhoneMiscTags.Wardrobe.NAME_INPUT).performTextInput("裙子")
        compose.onNodeWithTag(MiniPhoneMiscTags.Wardrobe.ADD).performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag(MiniPhoneMiscTags.Wardrobe.item("w-0")).fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(listOf(listOf("c1", "裙子", MpWardrobeStore.KIND_DRESS, "")), added)
    }

    @Test
    fun `空白名字不触发添加`() {
        render()
        compose.onNodeWithTag(MiniPhoneMiscTags.Wardrobe.ADD).performClick()
        compose.waitForIdle()
        assertEquals(0, added.size)
        compose.onNodeWithTag(MiniPhoneMiscTags.Wardrobe.EMPTY).assertIsDisplayed()
    }

    @Test
    fun `穿着中徽标可见且能触发换装`() {
        rows.value = listOf(item("w1", "已穿", MpWardrobeStore.KIND_TOP, 1), item("w2", "未穿", MpWardrobeStore.KIND_TOP, 0))
        render()
        compose.onNodeWithText(res(R.string.mp_wardrobe_equipped)).performScrollTo().assertIsDisplayed()
        // 「未穿」整行落在首屏之外、还没进组合：先把第一行的动作滚进可见区，列表会顺带补齐下一行
        compose.onNodeWithTag(MiniPhoneMiscTags.Wardrobe.equip("w1")).performScrollTo()
        compose.onNodeWithTag(MiniPhoneMiscTags.Wardrobe.LIST).performScrollToIndex(1)
        compose.onNodeWithTag(MiniPhoneMiscTags.Wardrobe.equip("w2")).performScrollTo().performClick()
        assertEquals(listOf("c1" to "w2"), equipped)
    }

    @Test
    fun `点返回触发回调`() {
        render()
        compose.onNodeWithTag(MiniPhoneMiscTags.Wardrobe.BACK).performClick()
        assertEquals(1, backClicks)
    }
}
