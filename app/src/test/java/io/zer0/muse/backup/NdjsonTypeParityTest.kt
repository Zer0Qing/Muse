package io.zer0.muse.backup;

import org.junit.Assert.assertEquals;
import org.junit.Assert.assertTrue;
import org.junit.Test;
import java.io.File;

/**
 * NDJSON 的导出类型清单与恢复分发必须完全一致。

 * 审查 P2-17：AGENT_BOT_NDJSON_TYPES 定义后全仓无引用，注释却写
 * 「两处必须用同一份清单，否则导出有、恢复无会静默丢数据」。

 * 不用集合判断替掉 when：那个 when 里的字面类型串是恢复覆盖门禁的检测基础，
 * 改成集合判断会让「导出了但导不回」重新变成不可检测的缺口。
 * 正确做法是用测试比对两处。
 */
class NdjsonTypeParityTest {

    private val source: String by lazy {
        File("src/main/java/io/zer0/muse/backup/BackupService.kt").readText();
    }

    private val quote = Character.toString(34.toChar());
    private val quoteClass = Character.toString(91.toChar()) + quote + Character.toString(93.toChar());

    private fun typesInSet(): Set<String> {
        val at = source.indexOf("private val AGENT_BOT_NDJSON_TYPES: Set<String> = setOf(");
        assertTrue("常量必须存在", at > 0);
        val block = source.substring(at, source.indexOf(")", at));
        val re = Regex(quoteClass + "([a-zA-Z]+)" + quoteClass);
        return re.findAll(block).map { it.groupValues[1] }.toSet();
    }

    private fun typesInDispatch(): Set<String> {
        val at = source.indexOf("suspend fun dispatchAgentBotRecord(");
        assertTrue("分发函数必须存在", at > 0);
        val end = source.indexOf("else -> return false", at);
        val block = source.substring(at, if (end > at) end else source.length);
        val newline = Character.toString(10.toChar());
        val re = Regex(newline + "[ ]+" + quoteClass + "([a-zA-Z]+)" + quoteClass + " -> restoreAgentBotRecord");
        return re.findAll(block).map { it.groupValues[1] }.toSet();
    }

    @Test
    fun `set and dispatch cover exactly the same types`() {
        val declared = typesInSet();
        val dispatched = typesInDispatch();
        assertEquals("导出清单与恢复分发不一致 —— 差额会导致静默丢数据", declared, dispatched);
        assertEquals("必须是八张表", 8, declared.size);
    }

    @Test
    fun `every type has an export line too`() {
        // 导出调用有两种写法：类型串与 writeTypedLines 同行，或单独占一行。
        // 两种都合法 —— 多行写法是为了让长参数列表可读。
        typesInSet().forEach { type ->
            val inline = source.contains("writeTypedLines(writer, " + quote + type + quote);
            val multiline = source.contains(Character.toString(10.toChar()) + "            " + quote + type + quote);
            assertTrue(
                "类型 " + type + " 在导出侧找不到 writeTypedLines 调用",
                inline || multiline,
            );
        };
    }
}