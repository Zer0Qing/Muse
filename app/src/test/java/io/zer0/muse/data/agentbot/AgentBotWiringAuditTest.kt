package io.zer0.muse.data.agentbot

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 全量接线审计。
 *
 * 这个类存在的理由：本项目已经五次出现"组件完整、测试通过、但没有生产调用方"。
 * 单测全绿发现不了这类问题 —— 它们验的是"函数被调用时对不对"，
 * 而缺陷是"根本没人调用"。
 *
 * 这里逐个断言每个中枢组件都至少有一个**非自身、非 Koin 注册**的调用方。
 */
class AgentBotWiringAuditTest {

    private val mainDir = File("src/main/java")

    /** 读某个源文件全文。 */
    private fun source(relative: String): String = File(mainDir, relative).readText()

    /**
     * 扫描全仓，返回引用某个类名的**生产**文件名集合。
     *
     * Koin 注册本身**不算**调用方 —— 注册一个 never-called 的 singleton 是
     * 最容易制造"看起来接好了"假象的方式。但 Koin 里的 **lambda 体**算：
     * 那些 lambda 会在运行时真的执行（例如注入给调度器的 keepAlive）。
     *
     * 判定：除自身与 Koin 外有引用，**或** Koin 里出现了真实调用（`ClassName.`）。
     */
    private fun productionCallers(className: String): Set<String> {
        val hits = mutableSetOf<String>()
        mainDir.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
            if (file.name == className + ".kt") return@forEach
            val text = file.readText()
            if (!text.contains(className)) return@forEach
            if (file.name != "AppKoinModule.kt") {
                hits += file.name
                return@forEach
            }
            // Koin 文件里：只有"真的被使用"才算，纯 `single { X(...) }` 注册不算。
            // 判据是出现 `X.method(` 或 `<X>()`（后者是 get<T>() 解析，也是真实使用）。
            val used = text.contains(className + ".") || text.contains("<" + className + ">()") ||
                text.contains(className + "::class") ||
                // 形如 io.pkg.X.read(...) —— 全限定名直接调用方法
                Regex(Regex.escape(className) + "\\s*\\(").containsMatchIn(text)
            if (used) hits += file.name
        }
        return hits
    }

    @Test
    fun `every central component has a production caller`() {
        val components = listOf(
            "AgentBotRepository",
            "BotTaskScheduler",
            "AgentConversationFacade",
            "BotInboxProcessor",
            "PublicationService",
            "BotReviewWorker",
            "ApprovalService",
            "ConversationToolAllowanceStore",
            "BotResourceGovernor",
            "ForegroundCoordinator",
            "StagedArtifactStore",
            "BotResultApplier",
            "WorkspaceArtifactIO",
            "AgentBotModeReconciler",
            "AgentBotKeepAliveService",
        )
        val orphans = components.filter { productionCallers(it).isEmpty() }
        assertTrue(
            "这些组件没有任何生产调用方（只在 Koin 里注册）：" + orphans,
            orphans.isEmpty(),
        )
    }

    @Test
    fun `publication is actually invoked not just injected`() {
        // ForegroundCoordinator 注入了 PublicationService 却没用它 —— 曾经就是这样。
        val coordinator = source("io/zer0/muse/data/agentbot/ForegroundCoordinator.kt")
        assertTrue(
            "注入了不等于使用了：必须有真实的 publish 调用",
            coordinator.contains("publication.publish("),
        )
    }

    @Test
    fun `mode reconciler is called at startup`() {
        val app = source("io/zer0/muse/MuseApp.kt")
        assertTrue(
            "关开对账必须在启动时跑，否则死租约永远留着",
            app.contains("reconcileOnEnable("),
        )
    }

    @Test
    fun `reconciliation only runs when the feature flag is on`() {
        val app = source("io/zer0/muse/MuseApp.kt")
        val flagAt = app.indexOf("FeatureFlags.AGENT_BOT")
        val callAt = app.indexOf("reconcileOnEnable(")
        assertTrue(
            "关着的时候中枢不该有任何后台动作",
            flagAt in 0 until callAt,
        )
    }
}
