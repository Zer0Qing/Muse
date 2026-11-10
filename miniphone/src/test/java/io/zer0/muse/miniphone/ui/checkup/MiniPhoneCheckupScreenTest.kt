package io.zer0.muse.miniphone.ui.checkup

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import io.zer0.muse.miniphone.api.MpCharacter
import io.zer0.muse.miniphone.data.checkup.MpCheckupDirection
import io.zer0.muse.miniphone.data.checkup.MpCheckupIntensity
import io.zer0.muse.miniphone.data.checkup.MpCheckupStore
import io.zer0.muse.miniphone.data.db.entity.MpCheckupCaseEntity
import io.zer0.muse.miniphone.data.db.entity.MpCheckupEvidenceEntity
import io.zer0.muse.miniphone.ui.theme.MiniPhoneTheme
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 查岗页：状态由可替换的 Flow 与回调驱动，不碰 Room。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MiniPhoneCheckupScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val started = mutableListOf<Triple<String, MpCheckupDirection, MpCheckupIntensity>>()
    private val interpreted = mutableListOf<String>()
    private val deleted = mutableListOf<String>()
    private var backClicks = 0

    private fun case(id: String, status: String, summary: String) = MpCheckupCaseEntity(
        id = id,
        characterId = "c1",
        direction = "outgoing",
        intensity = "standard",
        status = status,
        summary = summary,
        createdAt = 1,
    )

    private fun render(rows: List<MpCheckupCaseEntity> = emptyList(), evidence: List<MpCheckupEvidenceEntity> = emptyList()) {
        compose.setContent {
            MiniPhoneTheme(darkTheme = false) {
                MiniPhoneCheckupScreen(
                    cases = flowOf(rows),
                    characters = listOf(MpCharacter(id = "c1", name = "阿离")),
                    evidenceFor = { flowOf(evidence) },
                    onStart = { id, dir, intensity -> started += Triple(id, dir, intensity) },
                    onInterpret = { interpreted += it },
                    onDelete = { deleted += it },
                    onBack = { backClicks += 1 },
                )
            }
        }
    }

    @Test
    fun `空态提示可见`() {
        render()
        compose.onNodeWithTag(MiniPhoneCheckupTags.EMPTY).assertIsDisplayed()
        compose.onNodeWithTag(MiniPhoneCheckupTags.PAGE).assertIsDisplayed()
    }

    @Test
    fun `没选角色时点发起不会立案`() {
        render()
        compose.onNodeWithTag(MiniPhoneCheckupTags.START).performClick()
        assertEquals(0, started.size)
    }

    @Test
    fun `选角色与强度后发起会带上所选参数`() {
        render()
        compose.onNodeWithTag(MiniPhoneCheckupTags.character("c1")).performClick()
        compose.onNodeWithTag(MiniPhoneCheckupTags.INTENSITY_DEEP).performClick()
        compose.onNodeWithTag(MiniPhoneCheckupTags.DIRECTION_IN).performClick()
        compose.onNodeWithTag(MiniPhoneCheckupTags.START).performClick()
        assertEquals(
            listOf(Triple("c1", MpCheckupDirection.INCOMING, MpCheckupIntensity.DEEP)),
            started,
        )
    }

    @Test
    fun `案卷行可见且能触发解读与删除`() {
        render(rows = listOf(case("k1", MpCheckupStore.STATUS_DONE, "结论")))
        compose.onNodeWithTag(MiniPhoneCheckupTags.caseRow("k1")).assertIsDisplayed()
        compose.onNodeWithTag(MiniPhoneCheckupTags.interpret("k1")).performClick()
        compose.onNodeWithTag(MiniPhoneCheckupTags.delete("k1")).performClick()
        assertEquals(listOf("k1"), interpreted)
        assertEquals(listOf("k1"), deleted)
    }

    @Test
    fun `点返回触发回调`() {
        render()
        compose.onNodeWithTag(MiniPhoneCheckupTags.BACK).performClick()
        assertEquals(1, backClicks)
    }
}
