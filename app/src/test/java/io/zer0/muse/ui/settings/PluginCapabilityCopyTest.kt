package io.zer0.muse.ui.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.zer0.muse.data.plugin.PluginCapabilityGateway
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 能力人话表的覆盖测试。
 *
 * 最关键的一条是 [every known capability has a human sentence]：它**从网关的权威集合反查**，
 * 而不是照抄我列的清单。这样以后往 `CAPABILITY_ACTIONS` 加一个能力，
 * 忘了配文案就会在这里变红 —— 而不是在用户的权限面板里悄悄变成 `fs.read` 这种标识符。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PluginCapabilityCopyTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    /** 权威词表 = 映射表的键 + 同义拼写 + 不在映射表里的特殊档（Python、交互界面）。 */
    private fun vocabulary(): Set<String> = PluginCapabilityGateway.CAPABILITY_ACTIONS.keys +
        PluginCapabilityGateway.CAPABILITY_ALIASES.keys +
        setOf(PluginCapabilityGateway.RUNTIME_PYTHON, PluginCapabilityGateway.UI_INTERACTIVE, "ui.skin")

    @Test
    fun `every known capability has a human sentence`() {
        val missing = vocabulary().filterNot { PluginCapabilityCopy.hasCopy(it) }

        assertTrue("这些能力还会显示原始标识符: $missing", missing.isEmpty())
    }

    @Test
    fun `vocabulary is not empty so the guard above cannot pass by accident`() {
        // 上一例是"遍历为空也绿"的典型假通过形状，这里把集合本身钉住。
        assertTrue("词表至少该有 15 项，实际: ${vocabulary().size}", vocabulary().size >= 15)
        assertTrue(vocabulary().contains("chat.read"))
        assertTrue(vocabulary().contains("host.model.call"))
    }

    @Test
    fun `privacy and cost capabilities are not rendered at the same weight as file access`() {
        listOf("chat.read", "host.model.call", PluginCapabilityGateway.RUNTIME_PYTHON).forEach {
            assertTrue("该标醒目: $it", PluginCapabilityCopy.isSensitive(it))
        }
        assertFalse("读文件不该被当成同级隐私: fs", PluginCapabilityCopy.isSensitive("resource.read"))
    }

    @Test
    @Config(qualifiers = "zh-rCN")
    fun `rendered text is a sentence, not the identifier`() {
        val chat = PluginCapabilityCopy.text(context, "chat.read")
        val model = PluginCapabilityCopy.text(context, "host.model.call")

        assertNotEquals("显示文本不能还是键名", "chat.read", chat)
        assertTrue("要说清读的是对话内容: $chat", chat.contains("对话"))
        assertTrue("要说清会计入用量: $model", model.contains("用量"))
    }

    @Test
    @Config(qualifiers = "zh-rCN")
    fun `alias spellings describe file access, not key-value storage`() {
        val canonical = PluginCapabilityCopy.text(context, "resource.write")
        val alias = PluginCapabilityCopy.text(context, "storage.write")

        assertEquals("两种拼写在用户面前该是同一句话", canonical, alias)
        // 这一条是真会骗人的地方：storage.write 是"写文件"的别名，
        // 若显示成"保存插件自己的记录"，用户批准的是记状态，拿到的却是读写目录。
        assertTrue("别名要说清是文件写入: $alias", alias.contains("文件"))
        assertFalse("别名不该被描述成 KV 记录: $alias", alias.contains("记录"))
    }

    @Test
    fun `unknown capability falls back to the raw identifier instead of a blank or a lie`() {
        // 显示空白或"未知权限"会让人以为没授权 —— 回落原标识符至少可查、可问。
        assertEquals("brand.new.capability", PluginCapabilityCopy.text(context, "brand.new.capability"))
    }

    @Test
    fun `ordinary and unknown capabilities are not flagged as sensitive`() {
        // 醒目标记一旦滥用就等于没有：全都红，用户就全都不读。
        assertFalse(PluginCapabilityCopy.isSensitive("clipboard"))
        assertFalse(PluginCapabilityCopy.isSensitive("notify"))
        assertFalse(PluginCapabilityCopy.isSensitive("brand.new.capability"))
    }
}
