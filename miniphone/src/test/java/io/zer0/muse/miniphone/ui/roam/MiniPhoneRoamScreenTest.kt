package io.zer0.muse.miniphone.ui.roam

import android.content.Context
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import io.zer0.muse.miniphone.R
import io.zer0.muse.miniphone.api.MpCharacter
import io.zer0.muse.miniphone.data.db.entity.MpRoamNpcEntity
import io.zer0.muse.miniphone.data.db.entity.MpRoamStateEntity
import io.zer0.muse.miniphone.ui.theme.MiniPhoneTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 漫游页：状态由可替换的 Flow 与回调驱动，不碰 Room（避免 ':memory:' 跨用例串库）。 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MiniPhoneRoamScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val locations = MutableStateFlow(emptyList<MpRoamStateEntity>())
    private val departed = mutableListOf<Pair<String, String>>()
    private val encountered = mutableListOf<Triple<String, String, String>>()
    private val cleared = mutableListOf<String>()
    private var backClicks = 0

    private fun res(id: Int): String = ApplicationProvider.getApplicationContext<Context>().getString(id)

    private fun state(characterId: String, locationName: String) = MpRoamStateEntity(
        characterId = characterId,
        locationId = locationName,
        locationName = locationName,
        updatedAt = 1,
    )

    private fun npc(id: String, locationId: String, name: String, persona: String) = MpRoamNpcEntity(
        id = id,
        locationId = locationId,
        name = name,
        persona = persona,
        metAt = 1,
    )

    private fun render(initial: List<MpRoamStateEntity> = emptyList(), npcs: List<MpRoamNpcEntity> = emptyList()) {
        locations.value = initial
        compose.setContent {
            MiniPhoneTheme(darkTheme = false) {
                MiniPhoneRoamScreen(
                    locations = locations,
                    characters = listOf(MpCharacter(id = "c1", name = "阿离"), MpCharacter(id = "c2", name = "小满")),
                    npcsFor = { flowOf(npcs) },
                    onDepart = { characterId, locationName ->
                        departed += characterId to locationName
                        locations.value = locations.value + state(characterId, locationName)
                    },
                    onEncounter = { characterId, locationId, locationName ->
                        encountered += Triple(characterId, locationId, locationName)
                    },
                    onClearNpcs = { cleared += it },
                    onBack = { backClicks += 1 },
                )
            }
        }
    }

    @Test
    fun `空态提示可见且文案来自资源`() {
        render()
        compose.onNodeWithTag(MiniPhoneRoamTags.PAGE).assertIsDisplayed()
        compose.onNodeWithTag(MiniPhoneRoamTags.EMPTY).assertIsDisplayed()
        compose.onNodeWithText(res(R.string.mp_roam_empty)).assertIsDisplayed()
    }

    @Test
    fun `有位置时列出地点且出发按钮文案来自资源`() {
        render(initial = listOf(state("c1", "咖啡馆")))
        compose.onNodeWithTag(MiniPhoneRoamTags.locationRow("c1")).assertIsDisplayed()
        compose.onNodeWithText("咖啡馆").assertIsDisplayed()
        compose.onNodeWithText(res(R.string.mp_roam_depart)).assertIsDisplayed()
    }

    @Test
    fun `选角色输入地点后出发带上参数并异步刷新列表`() {
        render()
        compose.onNodeWithTag(MiniPhoneRoamTags.character("c1")).performClick()
        compose.onNodeWithTag(MiniPhoneRoamTags.LOCATION_INPUT).performTextInput("  咖啡馆  ")
        compose.onNodeWithTag(MiniPhoneRoamTags.DEPART).performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag(MiniPhoneRoamTags.locationRow("c1")).fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(listOf("c1" to "咖啡馆"), departed)
        compose.onNodeWithTag(MiniPhoneRoamTags.locationRow("c1")).assertIsDisplayed()
    }

    @Test
    fun `没选角色或地点空白都不出发`() {
        render()
        compose.onNodeWithTag(MiniPhoneRoamTags.DEPART).performClick()
        compose.onNodeWithTag(MiniPhoneRoamTags.character("c1")).performClick()
        compose.onNodeWithTag(MiniPhoneRoamTags.LOCATION_INPUT).performTextInput("   ")
        compose.onNodeWithTag(MiniPhoneRoamTags.DEPART).performClick()
        assertEquals(0, departed.size)
    }

    @Test
    fun `遇见与清空回调带上所选位置信息`() {
        render(initial = listOf(state("c1", "咖啡馆")))
        compose.onNodeWithTag(MiniPhoneRoamTags.ENCOUNTER).performClick()
        compose.onNodeWithTag(MiniPhoneRoamTags.CLEAR).performClick()
        assertEquals(listOf(Triple("c1", "咖啡馆", "咖啡馆")), encountered)
        assertEquals(listOf("咖啡馆"), cleared)
    }

    @Test
    fun `路人按位置渲染出来`() {
        render(initial = listOf(state("c1", "咖啡馆")), npcs = listOf(npc("n1", "咖啡馆", "阿岚", "性子冷")))
        compose.onNodeWithTag(MiniPhoneRoamTags.npc("n1")).assertIsDisplayed()
        compose.onNodeWithText("阿岚").assertIsDisplayed()
    }

    @Test
    fun `点返回触发回调`() {
        render()
        compose.onNodeWithTag(MiniPhoneRoamTags.BACK).performClick()
        assertEquals(1, backClicks)
    }
}
