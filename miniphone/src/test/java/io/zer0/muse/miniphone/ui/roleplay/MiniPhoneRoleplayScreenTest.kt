package io.zer0.muse.miniphone.ui.roleplay

import android.content.Context
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import io.zer0.muse.miniphone.R
import io.zer0.muse.miniphone.api.MpCharacter
import io.zer0.muse.miniphone.data.db.entity.MpRpEndingEntity
import io.zer0.muse.miniphone.data.db.entity.MpRpSaveEntity
import io.zer0.muse.miniphone.data.db.entity.MpRpScriptEntity
import io.zer0.muse.miniphone.data.roleplay.MpRoleplayState
import io.zer0.muse.miniphone.ui.theme.MiniPhoneTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 跑团页：剧本、存档与结局全部由 Flow 与回调驱动，不碰 Room。 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MiniPhoneRoleplayScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val scripts = MutableStateFlow(emptyList<MpRpScriptEntity>())
    private val saves = MutableStateFlow(emptyList<MpRpSaveEntity>())
    private val endings = MutableStateFlow(emptyList<MpRpEndingEntity>())
    private val created = mutableListOf<Pair<String, String>>()
    private val updated = mutableListOf<Triple<String, String, String>>()
    private val deletedScripts = mutableListOf<String>()
    private val started = mutableListOf<Triple<String, String, String>>()
    private val advanced = mutableListOf<Pair<String, String>>()
    private val concluded = mutableListOf<String>()
    private val deletedSaves = mutableListOf<String>()
    private var backClicks = 0

    private fun text(res: Int): String = ApplicationProvider.getApplicationContext<Context>().getString(res)

    private fun script(id: String = "s1", title: String = "雾港", body: String = "午夜抵达") = MpRpScriptEntity(
        id = id,
        title = title,
        body = body,
        createdAt = 1,
        updatedAt = 1,
    )

    private fun save(id: String = "v1", scriptId: String = "s1") = MpRpSaveEntity(
        id = id,
        scriptId = scriptId,
        title = "第一次冒险",
        stateJson = MpRoleplayState("c1", "你抵达车站。", emptyList()).toJson(),
        turn = 2,
        updatedAt = 1,
    )

    private fun ending(id: String = "e1", scriptId: String = "s1", saveId: String = "v1") = MpRpEndingEntity(
        id = id,
        scriptId = scriptId,
        saveId = saveId,
        title = "末班车",
        text = "雾散了",
        createdAt = 1,
    )

    private fun render() {
        compose.setContent {
            MiniPhoneTheme(darkTheme = false) {
                MiniPhoneRoleplayScreen(
                    scripts = scripts,
                    characters = listOf(MpCharacter(id = "c1", name = "阿离")),
                    savesFor = { saves },
                    endingsFor = { endings },
                    onCreateScript = { title, body -> created += title to body },
                    onUpdateScript = { id, title, body -> updated += Triple(id, title, body) },
                    onDeleteScript = { deletedScripts += it },
                    onStartRun = { scriptId, characterId, title -> started += Triple(scriptId, characterId, title) },
                    onAdvance = { saveId, action -> advanced += saveId to action },
                    onConclude = { concluded += it },
                    onDeleteSave = { deletedSaves += it },
                    onBack = { backClicks += 1 },
                )
            }
        }
    }

    private fun waitUntilTag(tag: String) {
        compose.waitUntil(timeoutMillis = 5_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun scrollToTag(tag: String) {
        compose.onNodeWithTag(tag).performScrollTo()
    }

    /** 屏幕外的节点点下去会落在可见区之外：先滚进可见区再点，点的才是用户真能点到的东西。 */
    private fun clickTag(tag: String) {
        compose.onNodeWithTag(tag).performScrollTo().performClick()
    }

    @Test
    fun `没有剧本时显示空态与页面骨架`() {
        render()
        waitUntilTag(MiniPhoneRoleplayTags.PAGE)
        compose.onNodeWithTag(MiniPhoneRoleplayTags.EMPTY).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.mp_rp_empty)).assertIsDisplayed()
    }

    @Test
    fun `空白草稿不提交且完整草稿会原样交给门面`() {
        render()
        compose.onNodeWithTag(MiniPhoneRoleplayTags.COMMIT_SCRIPT).performClick()
        compose.waitForIdle()
        assertEquals(0, created.size)

        compose.onNodeWithTag(MiniPhoneRoleplayTags.TITLE_INPUT).performTextInput("雨夜列车")
        compose.onNodeWithTag(MiniPhoneRoleplayTags.BODY_INPUT).performTextInput("车上没有人回答广播。")
        compose.onNodeWithTag(MiniPhoneRoleplayTags.COMMIT_SCRIPT).performClick()
        compose.waitUntil(timeoutMillis = 5_000) { created.isNotEmpty() }
        assertEquals(listOf("雨夜列车" to "车上没有人回答广播。"), created)
    }

    @Test
    fun `编辑既有剧本会回填并使用更新回调`() {
        scripts.value = listOf(script())
        render()
        waitUntilTag(MiniPhoneRoleplayTags.scriptRow("s1"))
        scrollToTag(MiniPhoneRoleplayTags.scriptRow("s1"))
        clickTag(MiniPhoneRoleplayTags.editScript("s1"))
        scrollToTag(MiniPhoneRoleplayTags.TITLE_INPUT)
        compose.onNodeWithTag(MiniPhoneRoleplayTags.TITLE_INPUT).assertTextEquals("雾港")
        compose.onNodeWithTag(MiniPhoneRoleplayTags.BODY_INPUT).assertTextEquals("午夜抵达")
        compose.onNodeWithTag(MiniPhoneRoleplayTags.TITLE_INPUT).performTextClearance()
        compose.onNodeWithTag(MiniPhoneRoleplayTags.TITLE_INPUT).performTextInput("雾港车站")
        scrollToTag(MiniPhoneRoleplayTags.COMMIT_SCRIPT)
        compose.onNodeWithTag(MiniPhoneRoleplayTags.COMMIT_SCRIPT).performClick()
        compose.waitUntil(timeoutMillis = 5_000) { updated.isNotEmpty() }
        assertEquals(listOf(Triple("s1", "雾港车站", "午夜抵达")), updated)
    }

    @Test
    fun `选择角色后可以开一局并删除剧本`() {
        scripts.value = listOf(script())
        render()
        waitUntilTag(MiniPhoneRoleplayTags.character("s1", "c1"))
        clickTag(MiniPhoneRoleplayTags.character("s1", "c1"))
        clickTag(MiniPhoneRoleplayTags.startRun("s1"))
        compose.waitUntil(timeoutMillis = 5_000) { started.isNotEmpty() }
        assertEquals(listOf(Triple("s1", "c1", "雾港")), started)

        clickTag(MiniPhoneRoleplayTags.deleteScript("s1"))
        compose.waitUntil(timeoutMillis = 5_000) { deletedScripts.isNotEmpty() }
        assertEquals(listOf("s1"), deletedScripts)
    }

    @Test
    fun `展开剧本后能看到存档动作与结局册`() {
        scripts.value = listOf(script())
        saves.value = listOf(save())
        endings.value = listOf(ending())
        render()
        waitUntilTag(MiniPhoneRoleplayTags.scriptRow("s1"))
        clickTag(MiniPhoneRoleplayTags.scriptRow("s1"))
        waitUntilTag(MiniPhoneRoleplayTags.saveRow("v1"))
        scrollToTag(MiniPhoneRoleplayTags.saveRow("v1"))
        compose.onNodeWithTag(MiniPhoneRoleplayTags.endingRow("e1")).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.mp_rp_endings)).performScrollTo().assertIsDisplayed()
        scrollToTag(MiniPhoneRoleplayTags.actionInput("v1"))

        compose.onNodeWithTag(MiniPhoneRoleplayTags.actionInput("v1")).performTextInput("走向月台")
        clickTag(MiniPhoneRoleplayTags.advance("v1"))
        clickTag(MiniPhoneRoleplayTags.conclude("v1"))
        clickTag(MiniPhoneRoleplayTags.deleteSave("v1"))
        compose.waitUntil(timeoutMillis = 5_000) {
            advanced.isNotEmpty() && concluded.isNotEmpty() && deletedSaves.isNotEmpty()
        }
        assertEquals(listOf("v1" to "走向月台"), advanced)
        assertEquals(listOf("v1"), concluded)
        assertEquals(listOf("v1"), deletedSaves)
    }

    @Test
    fun `点返回触发回调`() {
        render()
        waitUntilTag(MiniPhoneRoleplayTags.BACK)
        compose.onNodeWithTag(MiniPhoneRoleplayTags.BACK).performClick()
        assertEquals(1, backClicks)
    }
}
