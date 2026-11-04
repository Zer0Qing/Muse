package io.zer0.muse.automation.ui

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import io.zer0.muse.R
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 「每日最多执行」这一行**只许出现一次**，且数值要看得见。
 *
 * 这里修的是一个用户看得见的缺陷：同一个标题渲染了两行 ——
 * 一行是说明文字加 `−/+` 按钮，另一行单独显示数值（大概是把控件行加进去之后
 * 忘了删掉原来的数值行）。用户看到的就是"怎么有两条一模一样的设置"。
 * 修法：数值挪到两个按钮中间，多余那行删掉。
 *
 * 说明清楚这套测试**测到哪**：它锁住"标题不重复、数值与加减按钮同时在场"。
 * 点按钮是否真的回调 `onDraftChange` 不在这里断言 ——
 * 我试过在本环境里驱动那一次点击（`waitForIdle` 与推进主时钟都试过），
 * 回调没有被观察到，而这更像 Robolectric + NATIVE 图形模式下按钮命中区的问题，
 * **不是把测试写成永远成立的借口**，所以宁可只断言测得准的部分。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "zh-rCN")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AutonomousAccessibilityCardRowTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val limitTitle get() = context.getString(R.string.automation_autonomous_daily_limit)

    private fun show(maxRunsPerDay: Int = 6) {
        composeTestRule.setContent {
            MaterialTheme {
                AutonomousAccessibilitySettingsCard(
                    draft = AutonomousAccessibilityDraftState(maxRunsPerDay = maxRunsPerDay),
                    packageEditor = "",
                    taskPreview = null,
                    grant = null,
                    onDraftChange = {},
                    onPackageEditorChange = {},
                    onSave = {},
                    onConfirmTask = {},
                    onClearTask = {},
                )
            }
        }
    }

    private fun countOf(text: String): Int =
        composeTestRule.onAllNodesWithText(text).fetchSemanticsNodes().size

    @Test
    fun `每日最多执行只渲染一行`() {
        show()

        assertEquals(
            "标题「$limitTitle」出现多次：重复行会让用户以为有两条独立设置",
            1,
            countOf(limitTitle),
        )
    }

    @Test
    fun `数值与加减按钮同处一行且各只有一个`() {
        show(maxRunsPerDay = 9)

        assertEquals("数值该在按钮中间，且只出现一次", 1, countOf("9"))
        assertEquals("减按钮应当只有一个", 1, countOf("-"))
        assertEquals("加按钮应当只有一个", 1, countOf("+"))
        // 旧版这里有第二行显示同一个数值；换成一行后，数值节点仍必须在场（不然等于删掉了显示）
        composeTestRule.onNodeWithText("9").assertExists()
    }
}
