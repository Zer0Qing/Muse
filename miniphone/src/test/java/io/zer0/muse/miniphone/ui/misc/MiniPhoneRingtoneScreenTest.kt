package io.zer0.muse.miniphone.ui.misc

import android.content.Context
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import io.zer0.muse.miniphone.R
import io.zer0.muse.miniphone.api.MpCharacter
import io.zer0.muse.miniphone.data.db.entity.MpRingtoneEntity
import io.zer0.muse.miniphone.data.misc.MpRingtoneStore
import io.zer0.muse.miniphone.ui.theme.MiniPhoneTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 铃声页：状态由可替换的 Flow 与回调驱动，不碰 Room。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@OptIn(ExperimentalTestApi::class)
class MiniPhoneRingtoneScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val rows = MutableStateFlow(emptyList<MpRingtoneEntity>())
    private val added = mutableListOf<List<String>>()
    private val deleted = mutableListOf<String>()
    private var backClicks = 0
    private var nextId = 0

    private fun res(id: Int): String = ApplicationProvider.getApplicationContext<Context>().getString(id)

    private fun item(id: String, characterId: String, name: String, kind: String) = MpRingtoneEntity(
        id = id,
        characterId = characterId,
        name = name,
        uri = "uri",
        kind = kind,
        createdAt = 1,
    )

    private fun render() {
        compose.setContent {
            MiniPhoneTheme(darkTheme = false) {
                MiniPhoneRingtoneScreen(
                    ringtonesFor = { _, _ -> rows },
                    characters = listOf(MpCharacter(id = "c1", name = "阿离")),
                    onAdd = { cid, name, uri, kind ->
                        added += listOf(cid, name, uri, kind)
                        rows.value = rows.value + item("r-${nextId++}", cid, name, kind)
                    },
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
        compose.onNodeWithTag(MiniPhoneMiscTags.Ringtone.EMPTY).assertIsDisplayed()
        compose.onNodeWithTag(MiniPhoneMiscTags.Ringtone.PAGE).assertIsDisplayed()
        compose.onNodeWithText(res(R.string.mp_ringtone_empty)).assertIsDisplayed()
    }

    @Test
    fun `默认通用角色添加带空角色与语音种类`() {
        render()
        compose.onNodeWithTag(MiniPhoneMiscTags.Ringtone.NAME_INPUT).performTextInput("通用音")
        compose.onNodeWithTag(MiniPhoneMiscTags.Ringtone.URI_INPUT).performTextInput("file://a")
        compose.onNodeWithTag(MiniPhoneMiscTags.Ringtone.ADD).performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag(MiniPhoneMiscTags.Ringtone.item("r-0")).fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(listOf(listOf("", "通用音", "file://a", MpRingtoneStore.KIND_VOICE)), added)
    }

    @Test
    fun `选角色后添加带角色 id`() {
        render()
        compose.onNodeWithTag(MiniPhoneMiscTags.Ringtone.character("c1")).performClick()
        compose.onNodeWithTag(MiniPhoneMiscTags.Ringtone.NAME_INPUT).performTextInput("专属音")
        compose.onNodeWithTag(MiniPhoneMiscTags.Ringtone.URI_INPUT).performTextInput("file://b")
        compose.onNodeWithTag(MiniPhoneMiscTags.Ringtone.ADD).performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag(MiniPhoneMiscTags.Ringtone.item("r-0")).fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals("c1", added.first().first())
    }

    @Test
    fun `切到视频种类后添加带视频`() {
        render()
        compose.onNodeWithTag(MiniPhoneMiscTags.Ringtone.kind(MpRingtoneStore.KIND_VIDEO)).performClick()
        compose.onNodeWithTag(MiniPhoneMiscTags.Ringtone.NAME_INPUT).performTextInput("视频音")
        compose.onNodeWithTag(MiniPhoneMiscTags.Ringtone.URI_INPUT).performTextInput("file://c")
        compose.onNodeWithTag(MiniPhoneMiscTags.Ringtone.ADD).performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag(MiniPhoneMiscTags.Ringtone.item("r-0")).fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(MpRingtoneStore.KIND_VIDEO, added.first()[3])
    }

    @Test
    fun `通用铃声徽标可见`() {
        rows.value = listOf(item("r1", "", "通用音", MpRingtoneStore.KIND_VOICE))
        render()
        compose.onNodeWithTag(MiniPhoneMiscTags.Ringtone.item("r1")).assertIsDisplayed()
        assertTrue(compose.onAllNodesWithText(res(R.string.mp_ringtone_general)).fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun `点返回触发回调`() {
        render()
        compose.onNodeWithTag(MiniPhoneMiscTags.Ringtone.BACK).performClick()
        assertEquals(1, backClicks)
    }
}
