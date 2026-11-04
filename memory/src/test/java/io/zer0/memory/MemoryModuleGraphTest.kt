package io.zer0.memory

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.zer0.ai.core.Model
import io.zer0.memory.fact.FactDbProvider
import io.zer0.memory.fact.FactDedupJudge
import io.zer0.memory.fact.FactStore
import io.zer0.memory.fact.NoopFactDedupJudge
import io.zer0.memory.llm.MemoryLlmClient
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * memory 模块的装配图（`:memory` 体检第十二处）。
 *
 * 为什么必须测装配：`MuseApp` 里 `startKoin` 失败会被 `resultOf` 捕获，`MainActivity`
 * 随后**静默进安全模式** —— 装配写错不崩、也不会让任何业务测试变红，用户看到的只是
 * "功能都打不开"。所以改 `MemoryModule.kt` 不能裸改。
 *
 * 顺带钉住刚修的那条：默认助手的 `FactStore` 只能有一份，且必须是 [FactDbProvider]
 * 缓存的那一份（两份会让写后对账节流与去重锁各自为政）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MemoryModuleGraphTest {

    /** 装配测试里绝不该真调 LLM：调了就说明有依赖被提前执行。 */
    private object NoLlm : MemoryLlmClient {
        override suspend fun callText(
            systemPrompt: String,
            userContent: String,
            model: Model?,
            temperature: Float,
            maxTokens: Int,
            timeoutMs: Long,
        ): String = error("装配图测试不该调用 LLM")
    }

    @After
    fun tearDown() {
        runCatching { stopKoin() }
    }

    @Test
    fun `模块可装配且默认 FactStore 只有一份`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val koin = startKoin {
            androidContext(context)
            modules(
                module {
                    single<MemoryLlmClient> { NoLlm }
                    single<FactDedupJudge> { NoopFactDedupJudge }
                },
                memoryModule,
            )
        }.koin

        val store = koin.get<FactStore>()
        assertNotNull("默认 FactStore 要解析得动", store)
        val provider = koin.get<FactDbProvider>()
        assertSame(
            "装配里的默认 FactStore 必须就是 provider 缓存的那一份（两份会让节流与去重锁各自为政）",
            provider.getFactStore("default"),
            store,
        )
        assertSame("空白助手 id 也要回到同一份", store, provider.getFactStore(""))

        // 关键下游都要解析得动：少一个绑定，应用就是静默进安全模式而不是崩
        assertNotNull(koin.get<io.zer0.memory.compile.MemoryCompiler>())
        assertNotNull(koin.get<io.zer0.memory.summary.SessionSummaryManager>())
        assertNotNull(koin.get<io.zer0.memory.summary.ContextCheckpointReader>())
        assertNotNull(koin.get<io.zer0.memory.space.MemorySpaceRepository>())
        assertNotNull(koin.get<io.zer0.memory.reflection.MemoryContradictionStore>())
        assertNotNull(koin.get<io.zer0.memory.reflection.MemoryReflectionRunner>())
        assertNotNull(koin.get<io.zer0.memory.pin.PinnedMemoryStore>())
        assertNotNull(koin.get<io.zer0.memory.deep.DeepMemoryProcessor>())
        assertNotNull(koin.get<io.zer0.memory.ai.MemoryEdgeBuilder>())
        assertNotNull(koin.get<io.zer0.memory.fact.FactDb>())
    }
}
