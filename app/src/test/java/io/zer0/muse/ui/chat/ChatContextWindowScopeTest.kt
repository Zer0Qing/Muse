package io.zer0.muse.ui.chat

import io.zer0.ai.core.Model
import io.zer0.muse.session.WarmupHistory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * 上下文窗口口径回归测试。
 *
 * 背景：UI 侧 `ChatUiState.contextMaxTokens` 由 `ChatViewModel.refreshContextInfo`
 * 按**全局选中模型**算出，而实际请求可能因会话覆盖 / 助手绑定走另一个模型。
 * 旧实现里 90% 激进截断、1.35 倍硬拦截、预热与压缩预算全部按前者算，
 * 于是小窗口模型被放行（必然 400）、大窗口模型被无谓压缩（白丢上下文）。
 *
 * 这里只测纯逻辑：给定 effectiveModel 窗口小于全局窗口时，阈值必须按小窗口算。
 */
class ChatContextWindowScopeTest {

    @Test
    fun `per turn model window wins over the global fallback`() {
        val smallWindowModel = Model(id = "small-model", providerId = "p", contextWindow = 8_000)

        assertEquals(8_000, effectiveContextWindowTokens(smallWindowModel, 200_000))
    }

    @Test
    fun `declared window wins over registry inference`() {
        val declared = Model(id = "gpt-4o", providerId = "p", contextWindow = 32_768)

        assertEquals(32_768, effectiveContextWindowTokens(declared, 200_000))
    }

    @Test
    fun `fallback applies only when the model window is unknown`() {
        assertEquals(200_000, effectiveContextWindowTokens(null, 200_000))
        assertEquals(
            200_000,
            effectiveContextWindowTokens(Model(id = "no-spec-model", providerId = "p"), 200_000),
        )
    }

    @Test
    fun `zero window declaration falls back instead of disabling the guard`() {
        val zeroWindow = Model(id = "no-spec-model", providerId = "p", contextWindow = 0)

        assertEquals(200_000, effectiveContextWindowTokens(zeroWindow, 200_000))
    }

    @Test
    fun `pre send truncation threshold follows the smaller per turn window`() {
        val perTurnWindow = effectiveContextWindowTokens(
            Model(id = "small-model", providerId = "p", contextWindow = 8_000),
            200_000,
        )

        // 7.5K 占 8K 窗口的 93%（触发），占 200K 全局窗口只有 3.75%（不触发）。
        assertTrue(isNearContextLimit(currentTokens = 7_500, windowTokens = perTurnWindow))
        assertFalse(isNearContextLimit(currentTokens = 7_500, windowTokens = 200_000))
    }

    @Test
    fun `hard limit follows the smaller per turn window`() {
        val perTurnWindow = effectiveContextWindowTokens(
            Model(id = "small-model", providerId = "p", contextWindow = 8_000),
            200_000,
        )

        // 8K 窗口的 1.35 倍是 10_800，而不是 200K 窗口的 270_000。
        assertTrue(exceedsHardContextLimit(payloadTokens = 11_000, windowTokens = perTurnWindow))
        assertFalse(exceedsHardContextLimit(payloadTokens = 11_000, windowTokens = 200_000))
        assertFalse(exceedsHardContextLimit(payloadTokens = 10_000, windowTokens = perTurnWindow))
    }

    @Test
    fun `unknown window keeps guards disabled instead of blocking every send`() {
        assertFalse(isNearContextLimit(currentTokens = 5_000, windowTokens = 0))
        assertFalse(exceedsHardContextLimit(payloadTokens = 5_000, windowTokens = 0))
        assertFalse(isNearContextLimit(currentTokens = 0, windowTokens = 8_000))
    }

    @Test
    fun `warmup and compression budgets follow the per turn window`() {
        val perTurnWindow = effectiveContextWindowTokens(
            Model(id = "small-model", providerId = "p", contextWindow = 8_000),
            200_000,
        )

        // 预热预算 = 窗口的 60%；压缩预算还要再扣输出预留与安全余量。
        // 关键是两者都必须跟着 per-turn 窗口走：按全局 200K 算等于把全量历史硬塞进 8K 模型。
        assertEquals(4_800, WarmupHistory.budgetTokensFor(perTurnWindow))
        // 8000 - 4096 - 1024 = 2880
        assertEquals(2_880, WarmupHistory.compressTokenBudgetFor(perTurnWindow))
        // 200000 - 4096 - 10000(窗口 5%) = 185904
        assertEquals(185_904, WarmupHistory.compressTokenBudgetFor(200_000))
    }

    @Test
    fun `stream coordinator fixes the turn model before using the window`() {
        val source = coordinatorSource()
        val prepareBody = source.substringAfter("internal suspend fun prepareHistory")
            .substringBefore("private suspend fun applySessionCheckpoint")

        assertTrue(
            "准备阶段必须固定本轮模型绑定，窗口才有依据",
            prepareBody.contains("resolveModelBinding(state"),
        )
        assertTrue(
            "预热 token 预算必须按本轮模型窗口",
            prepareBody.contains("effectiveContextWindowTokens(effectiveModel, accessor.snapshot.contextMaxTokens)"),
        )
    }

    @Test
    fun `warmup and compression budgets no longer read the global window directly`() {
        val source = coordinatorSource()

        assertFalse(
            "预热预算不得再用全局 contextMaxTokens 直算",
            source.contains("WarmupHistory.budgetTokensFor(accessor.snapshot.contextMaxTokens)"),
        )
        assertFalse(
            "压缩预算不得再用全局 contextMaxTokens 直算",
            source.contains("WarmupHistory.compressTokenBudgetFor(accessor.snapshot.contextMaxTokens)"),
        )
    }

    @Test
    fun `generation controller truncation and hard limit use the per turn window`() {
        val source = controllerSource()
        val promptBody = source.substringAfter("suspend fun buildSystemPromptForStream")
            .substringBefore("suspend fun recordConversationShadow")

        assertTrue(
            "发送前截断必须按本轮模型窗口",
            promptBody.contains("effectiveContextWindowTokens(effectiveModel, accessor.snapshot.contextMaxTokens)"),
        )
        assertFalse(
            "硬拦截不得再用全局 contextMaxTokens 直算",
            promptBody.contains("val hardMaxTokens = accessor.snapshot.contextMaxTokens"),
        )
        assertFalse(
            "90% 预警不得再用全局 contextMaxTokens 直算",
            promptBody.contains("val maxTokens = accessor.snapshot.contextMaxTokens"),
        )
    }

    private fun coordinatorSource(): String = listOf(
        Path.of("src/main/java/io/zer0/muse/ui/chat/ChatStreamCoordinator.kt"),
        Path.of("app/src/main/java/io/zer0/muse/ui/chat/ChatStreamCoordinator.kt"),
    )
        .firstOrNull(Files::exists)
        ?.let { Files.newBufferedReader(it).use { reader -> reader.readText() } }
        ?: error("ChatStreamCoordinator.kt not found")

    private fun controllerSource(): String = listOf(
        Path.of("src/main/java/io/zer0/muse/ui/chat/ChatGenerationController.kt"),
        Path.of("app/src/main/java/io/zer0/muse/ui/chat/ChatGenerationController.kt"),
    )
        .firstOrNull(Files::exists)
        ?.let { Files.newBufferedReader(it).use { reader -> reader.readText() } }
        ?: error("ChatGenerationController.kt not found")
}
