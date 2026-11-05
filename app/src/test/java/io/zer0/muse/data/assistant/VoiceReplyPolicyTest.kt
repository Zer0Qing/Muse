package io.zer0.muse.data.assistant

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

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
}
