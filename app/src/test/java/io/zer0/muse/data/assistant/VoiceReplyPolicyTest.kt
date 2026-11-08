package io.zer0.muse.data.assistant

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * 「多常用语音回你」的档位政策与存储（整改计划 第 4 条④）。
 *
 * 最要紧的一条是**默认档必须什么都不注入**：这个功能上线时，没动过设置的用户
 * 系统提示应当逐字不变。写成断言而不是靠我检查代码，是因为这类"默认值偷偷加了一段话"
 * 的改动在 review 时最容易看漏（本项目已因默认值变化踩过几次）。
 */
@RunWith(RobolectricTestRunner::class)
class VoiceReplyPolicyTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    @After
    fun reset() {
        VoiceReplyPolicyStore.resetForTests()
    }

    private fun freshStore(): VoiceReplyPolicyStore {
        VoiceReplyPolicyStore.resetForTests()
        File(context.filesDir, VoiceReplyPolicyStore.FILE_NAME).delete()
        return VoiceReplyPolicyStore.of(context)
    }

    @Test
    fun `默认档不注入任何文案`() {
        assertEquals(
            "RARELY 是出厂默认；只要它非空，功能一上线就等于改了所有用户的系统提示",
            "",
            VoiceReplyPolicy.instruction(VoiceReplyFrequency.RARELY),
        )
    }

    @Test
    fun `其余三档各自非空且互不相同`() {
        val texts = listOf(
            VoiceReplyFrequency.NEVER,
            VoiceReplyFrequency.OFTEN,
            VoiceReplyFrequency.ALWAYS,
        ).map { VoiceReplyPolicy.instruction(it) }
        texts.forEach { assertTrue("档位文案不该为空", it.isNotBlank()) }
        assertEquals("三档要能区分开", 3, texts.distinct().size)
    }

    @Test
    fun `不做硬拦截——never 档仍允许用户当场要求`() {
        val never = VoiceReplyPolicy.instruction(VoiceReplyFrequency.NEVER)
        assertTrue("「从不」要留出用户主动要求的余地，否则变成黑箱拦截", never.contains("当场明确要求"))
    }

    @Test
    fun `未知档位名回落默认而不是抛异常`() {
        assertEquals(VoiceReplyFrequency.RARELY, VoiceReplyFrequency.fromName("SOMETIMES"))
        assertEquals(VoiceReplyFrequency.RARELY, VoiceReplyFrequency.fromName(null))
        assertEquals(VoiceReplyFrequency.RARELY, VoiceReplyFrequency.fromName(""))
        assertEquals(VoiceReplyFrequency.ALWAYS, VoiceReplyFrequency.fromName("always"))
    }

    @Test
    fun `档位落盘后可读回且按助手分开`() {
        val store = freshStore()
        runTest {
            assertEquals(VoiceReplyFrequency.RARELY, store.frequencyFor("a1"))
            assertTrue(store.set("a1", VoiceReplyFrequency.OFTEN).isSuccess)
            assertEquals(VoiceReplyFrequency.OFTEN, store.frequencyFor("a1"))
            assertEquals("别的助手不该跟着变", VoiceReplyFrequency.RARELY, store.frequencyFor("a2"))
        }
    }

    @Test
    fun `坏文件当默认处理而不是崩`() {
        val store = freshStore()
        runTest {
            store.set("a1", VoiceReplyFrequency.ALWAYS)
        }
        File(context.filesDir, VoiceReplyPolicyStore.FILE_NAME).writeText("{坏掉的 JSON")
        VoiceReplyPolicyStore.resetForTests()
        val reopened = VoiceReplyPolicyStore.of(context)
        runTest {
            assertEquals(
                "读不出就回落默认档（不注入文案），比崩掉或当成 ALWAYS 都安全",
                VoiceReplyFrequency.RARELY,
                reopened.frequencyFor("a1"),
            )
        }
    }

    @Test
    fun `政策文案与存储是同一档而不是各说各话`() {
        val store = freshStore()
        runTest {
            store.set("a1", VoiceReplyFrequency.ALWAYS)
            val stored = store.frequencyFor("a1")
            val injected = VoiceReplyPolicy.instruction(stored)
            assertNotEquals("装配用的是存储里那一档", "", injected)
            assertTrue(injected.contains(VoiceReplyPolicy.instruction(VoiceReplyFrequency.ALWAYS)))
        }
    }

    @Test
    fun `默认档拼接后与传入的提示词逐字相同`() {
        val base = "人格设定…\n\n---\n\n主动消息约定…"
        assertEquals(
            "默认档必须一个字都不加：拼接处多出空行或分隔符，就等于上线时改了所有人提示词",
            base,
            VoiceReplyPolicy.inject(base, VoiceReplyFrequency.RARELY),
        )
        // 非默认档才加分隔符，且不能把原文改掉
        val withPolicy = VoiceReplyPolicy.inject(base, VoiceReplyFrequency.OFTEN)
        assertTrue("原文要完整保留在前", withPolicy.startsWith(base))
        assertTrue("要用统一分隔符接上去", withPolicy.contains(VOICE_POLICY_SEPARATOR))
        assertEquals(
            "空白基底时不该留下一串分隔符",
            VoiceReplyPolicy.instruction(VoiceReplyFrequency.OFTEN),
            VoiceReplyPolicy.inject("", VoiceReplyFrequency.OFTEN),
        )
    }

    @Test
    fun `全局默认档对未单独设置的助手生效`() {
        val store = freshStore()
        runTest {
            assertEquals(VoiceReplyFrequency.RARELY, store.globalFrequency())
            assertTrue(store.setGlobal(VoiceReplyFrequency.OFTEN).isSuccess)
            assertEquals(VoiceReplyFrequency.OFTEN, store.globalFrequency())
            assertEquals("未单独设置的助手跟随全局默认", VoiceReplyFrequency.OFTEN, store.frequencyFor("a1"))
        }
    }

    @Test
    fun `助手覆盖优先于全局默认`() {
        val store = freshStore()
        runTest {
            store.setGlobal(VoiceReplyFrequency.OFTEN)
            store.set("a1", VoiceReplyFrequency.NEVER)
            assertEquals(VoiceReplyFrequency.NEVER, store.frequencyFor("a1"))
            assertEquals("别的助手仍跟随全局", VoiceReplyFrequency.OFTEN, store.frequencyFor("a2"))
        }
    }

    @Test
    fun `跟随全局默认即清除助手覆盖`() {
        val store = freshStore()
        runTest {
            store.setGlobal(VoiceReplyFrequency.ALWAYS)
            store.set("a1", VoiceReplyFrequency.NEVER)
            assertEquals(VoiceReplyFrequency.NEVER, store.frequencyFor("a1"))
            assertTrue(store.clearOverride("a1").isSuccess)
            assertEquals("清除覆盖后回到全局默认", VoiceReplyFrequency.ALWAYS, store.frequencyFor("a1"))
        }
    }

    @Test
    fun `旧版存量 RARELY 迁移为跟随全局而其它档保留`() {
        freshStore()
        // 旧 schema（无 schema 字段）：a1=RARELY（旧默认等同未设）、a2=OFTEN（显式覆盖）
        File(context.filesDir, VoiceReplyPolicyStore.FILE_NAME)
            .writeText("""{"byAgent":{"a1":"RARELY","a2":"OFTEN"}}""")
        VoiceReplyPolicyStore.resetForTests()
        val reopened = VoiceReplyPolicyStore.of(context)
        runTest {
            reopened.setGlobal(VoiceReplyFrequency.ALWAYS)
            assertEquals("存量 RARELY 当作未设→跟随全局", VoiceReplyFrequency.ALWAYS, reopened.frequencyFor("a1"))
            assertEquals("显式 OFTEN 保留为覆盖", VoiceReplyFrequency.OFTEN, reopened.frequencyFor("a2"))
        }
    }

    @Test
    fun `迁移后显式设的 RARELY 能钉住不被当作未设`() {
        val store = freshStore()
        runTest {
            store.setGlobal(VoiceReplyFrequency.OFTEN)
            store.set("a1", VoiceReplyFrequency.RARELY)
            assertEquals(VoiceReplyFrequency.RARELY, store.frequencyFor("a1"))
        }
        VoiceReplyPolicyStore.resetForTests()
        val reopened = VoiceReplyPolicyStore.of(context)
        runTest {
            assertEquals("落盘重开仍是显式 RARELY（schema 已升级，不再迁移）", VoiceReplyFrequency.RARELY, reopened.frequencyFor("a1"))
        }
    }

    /**
     * 装配侧的接线不变式（读源码断言）。
     *
     * 整体构造 `SystemPromptAssembler` 要六个依赖，测不动；本仓对这类情况已有先例
     * （`PromptInvariantTest` / `ExperienceTextCacheTest` 都是读源文件断言）。
     * 这里只钉两件真会错事：政策是否真被调用、子代理是否被排除。
     */
    @Test
    fun `装配器确实调用了政策并且子代理不注入`() {
        val src = java.io.File("src/main/java/io/zer0/muse/transformer/SystemPromptAssembler.kt").readText()
        assertTrue("build() 里必须真的拼接政策", src.contains("VoiceReplyPolicy.inject(combined, voiceFrequencyFor(assistant))"))
        assertTrue("子代理不注入（另有人设简报，多一段会稀释）", src.contains("if (forSubagent) combined else"))
        assertContainsSafely(src)
    }

    private fun assertContainsSafely(src: String) {
        // 政策段不能同时存在两条拼接路径（旧的 buildString 内拼接必须已经删掉）
        val appends = Regex("append\\(voicePolicy\\)").findAll(src).count() +
            Regex("buildVoicePolicySection").findAll(src).count()
        assertEquals("拼接路径必须只有一条：$appends", 0, appends)
    }
}
