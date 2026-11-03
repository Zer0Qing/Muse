package io.zer0.muse.ui.agentdm

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.zer0.muse.R
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Agent 私信界面的真机渲染测试（细化路线 P1 的最后一个面）。
 *
 * `AgentDmScreen`(254 行) 此前在测试里一次没出现过。它的数据来自 Koin（仓库与助手名单），
 * 所以这里**不塞假数据**：只钉两件与数据无关、却最容易出事的事 ——
 * 页面能不能 compose 出来、返回键是不是真的回到调用方。
 *
 * 为什么不顺手断言"暂无私信"：那取决于这台设备此刻有没有私信，
 * 把数据状态写进断言会让测试在真机上随机红，那种测试比没有测试更糟。
 */
@RunWith(AndroidJUnit4::class)
class AgentDmScreenDeviceTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Application get() = ApplicationProvider.getApplicationContext()

    @Test
    fun screenComposesAndBackGoesToTheCaller() {
        var backed = 0

        compose.setContent { AgentDmScreen(onBack = { backed++ }) }

        compose.onNodeWithText(context.getString(R.string.agent_dm_title)).assertExists()
        compose.onNodeWithContentDescription(context.getString(R.string.action_back)).performClick()

        assertEquals("返回键必须把控制权交回调用方，而不是自己吞掉", 1, backed)
    }
}
