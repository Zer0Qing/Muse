package io.zer0.muse.data.agentbot

import io.zer0.muse.feature.FeatureFlags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import io.zer0.muse.ui.screen.HomeScreen
import io.zer0.muse.ui.chat.ChatScreen

/**
 * 中枢入口的可达性守卫（计划 V26）。
 *
 * 审查报告里最常见的一类缺陷是"功能写好了但接不上入口"。这里以源码为准
 * 钉住三件事：开关是**写死的显式常量**（不能由构建类型或设备条件推导，否则升级会
 * 悄悄改变用户的心智模型）、页面被真正引用、绑定走 agentSessionId 而不是当前会话。
 */
class AgentBotEntryReachabilityTest {

    private val homeScreen: String by lazy {
        File("src/main/java/io/zer0/muse/ui/screen/HomeScreen.kt").readText()
    }

    private val featureFlags: String by lazy {
        File("src/main/java/io/zer0/muse/feature/FeatureFlags.kt").readText()
    }

    @Test
    fun `feature flag is a literal constant, never derived`() {
        // 中枢改的是主对话的心智模型：开不开必须是一个写死的决定。
        // 一旦写成 "= BuildConfig.DEBUG" 或按机型判断，升级就会悄悄改变用户的心智模型。
        assertTrue(
            "AGENT_BOT 必须声明为显式布尔常量",
            featureFlags.contains("const val AGENT_BOT: Boolean ="),
        )
    }

    @Test
    fun `entry flag is separated from the feature flag`() {
        // 两者分开是为了灰度：功能在包里、路由可达，入口可以先不露。
        assertEquals(FeatureFlags.AGENT_BOT, FeatureFlags.AGENT_BOT_ENTRY)
    }

    @Test
    fun `home tab actually references the central screen`() {
        assertTrue(
            "AgentBotScreen 必须被首页真正引用，否则功能不可达",
            homeScreen.contains("AgentBotScreen("),
        )
    }

    @Test
    fun `central screen is gated by the flag`() {
        assertTrue(
            "中枢页面必须在开关后面，关着时走原来的 Agent 聊天",
            homeScreen.contains("FeatureFlags.AGENT_BOT"),
        )
        // 关闸时必须还保留原路径，不能把旧行为删掉。
        assertTrue(
            "开关关闭时必须仍有原 ChatScreen 分支",
            homeScreen.contains("isAgentMode = true"),
        )
    }

    @Test
    fun `binding uses the agent session not the currently viewed session`() {
        assertTrue(
            "绑定必须走 agentSessionId —— 用 currentSessionId 会把中枢输入写进任务会话",
            homeScreen.contains("state.agentSessionId"),
        )
    }

    @Test
    fun `view model is registered so the screen can be constructed`() {
        val module = File("src/main/java/io/zer0/muse/AppKoinModule.kt").readText()
        assertTrue(
            "AgentBotViewModel 必须在 Koin 里注册，否则页面构造不出来",
            module.contains("AgentBotViewModel("),
        )
    }

    @Test
    fun `every status has a user facing label`() {
        val screen = File("src/main/java/io/zer0/muse/ui/agentbot/AgentBotScreen.kt").readText()
        val statuses = listOf(
            BotTaskStatus.QUEUED, BotTaskStatus.RUNNING, BotTaskStatus.REVIEWING,
            BotTaskStatus.WAITING_APPROVAL, BotTaskStatus.WAITING_RESOURCE,
            BotTaskStatus.CANCEL_REQUESTED, BotTaskStatus.SUCCEEDED, BotTaskStatus.PARTIAL,
            BotTaskStatus.FAILED, BotTaskStatus.CANCELLED, BotTaskStatus.NEEDS_USER,
            BotTaskStatus.UNKNOWN,
        )
        statuses.forEach { status ->
            assertTrue(
                "状态 " + status + " 没有用户可读的文案，内部词会漏到界面上",
                screen.contains(status),
            )
        }
    }
}
