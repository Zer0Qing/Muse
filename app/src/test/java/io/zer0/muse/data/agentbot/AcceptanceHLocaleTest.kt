package io.zer0.muse.data.agentbot;

import org.junit.Assert.assertEquals;
import org.junit.Assert.assertTrue;
import org.junit.Test;
import java.io.File;

/**
 * 验收清单 H 组（多语言）的可静态验证部分。

 * H1/H2「切英文/日文无中文残留」—— 视觉部分要眼看，但**文案完整性可以静态验**：
 * 每个 locale 都要有同一套 key，且非中日 locale 不能含简体中文。
 *
 * 判据细节：日文用汉字是正常的（"承認" "完了"），不能按 CJK 范围判；
 * 只查简体中文特有词（这/个/们/为/说/请…）。
 */
class AcceptanceHLocaleTest {

    private val locales = listOf(
        "values", "values-en", "values-es", "values-ja", "values-ko", "values-pt-rBR", "values-ru",
    );

    private fun stringsOf(locale: String): Map<String, String> {
        val f = File("src/main/res/" + locale + "/strings_ui_misc.xml");
        assertTrue("缺少 locale: " + locale, f.isFile());
        val text = f.readText();
        // 用正则而不是 XML 解析：只要 key 与文本，不需要 DOM 开销。
        val q = Character.toString(34.toChar());
        val re = Regex("<string name=" + q + "(agent_bot_[a-z_]+)" + q + ">([^<]*)</string>");
        return re.findAll(text).associate { it.groupValues[1] to it.groupValues[2] };
    }

    @Test
    fun `H1 all seven locales carry the same agent_bot keys`() {
        val base = stringsOf("values").keys;
        assertTrue("中文基线不能为空", base.isNotEmpty());
        locales.drop(1).forEach { locale ->
            val keys = stringsOf(locale).keys;
            assertEquals(
                locale + " 的 key 集合与中文基线不一致 —— 缺的会 fallback 成中文",
                base,
                keys,
            );
        };
    }

    @Test
    fun `H2 non-Chinese non-Japanese locales have no simplified Chinese`() {
        // 日文用汉字是正常的，所以排除 values-ja。
        val simplifiedOnly = Regex("[这个们么为说请还没对时过经从会发现让给吗呢吧]");
        listOf("values-en", "values-es", "values-ko", "values-pt-rBR", "values-ru").forEach { locale ->
            stringsOf(locale).forEach { (key, value) ->
                assertTrue(
                    locale + " 的 " + key + " 含简体中文：" + value,
                    !simplifiedOnly.containsMatchIn(value),
                );
            };
        };
    }

    @Test
    fun `no agent_bot string is empty`() {
        locales.forEach { locale ->
            stringsOf(locale).forEach { (key, value) ->
                assertTrue(
                    locale + " 的 " + key + " 是空的",
                    value.isNotBlank(),
                );
            };
        };
    }

    @Test
    fun `H4 every action string exists for TalkBack`() {
        // TalkBack 读的是文案。关键动作缺文案，读屏就只剩图标。
        val required = listOf(
            "agent_bot_approve", "agent_bot_deny", "agent_bot_revoke",
            "agent_bot_discard", "agent_bot_continue", "agent_bot_stop_reply",
        );
        val base = stringsOf("values").keys;
        required.forEach { key ->
            assertTrue("缺少关键动作文案: " + key, key in base);
        };
    }
}