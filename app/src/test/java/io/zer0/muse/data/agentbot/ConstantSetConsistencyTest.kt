package io.zer0.muse.data.agentbot

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 常量声明与集合收录必须一致。
 *
 * 第六十轮发现：BotTaskStatus.NEEDS_USER 被列进 TERMINAL，
 * 但它同时有两条出口事件 —— 声明与集合自相矛盾，而没有任何测试发现。
 *
 * 根因是"改一处没同步另一处"。这个类把每对关系都绑死。
 */
class ConstantSetConsistencyTest {

    private fun source(rel: String) = File("src/main/java/io/zer0/muse/data/agentbot/" + rel).readText()

    /** 从 object Xxx { 到其结束的文本。 */
    private fun objectBody(text: String, objectName: String): String {
        val start = text.indexOf("object " + objectName + " {");
        require(start > 0) { "找不到 object " + objectName };
        val end = text.indexOf("\n}", start);
        require(end > start) { "找不到 object 结束" };
        return text.substring(start, end);
    }

    /** 抽出 const val NAME = 里的 NAME 列表。 */
    private fun declaredNames(body: String): List<String> =
        Regex("const val ([A-Z_]+) =").findAll(body).map { it.groupValues[1] }.toList()

    /** 从某个 setOf(...) 块里抽出名字。 */
    private fun namesInSet(body: String, setPrefix: String): Set<String> {
        val block = body.substringAfter(setPrefix).substringBefore(")");
        return Regex("([A-Z_]{3,})").findAll(block).map { it.groupValues[1] }.toSet()
    }

    @Test
    fun `bot task status terminal only references declared constants`() {
        val body = objectBody(source("BotTaskEntity.kt"), "BotTaskStatus");
        val declared = declaredNames(body).toSet();
        val terminal = namesInSet(body, "val TERMINAL: Set<String> = setOf(");
        val unknown = terminal - declared;
        assertTrue("TERMINAL 引用了未声明的常量：" + unknown, unknown.isEmpty());
        assertTrue("状态常量数应为 12，实际 " + declared.size, declared.size == 12);
    }

    @Test
    fun `task run terminal only references declared constants`() {
        val body = objectBody(source("TaskRunEntity.kt"), "TaskRunStatus");
        val declared = declaredNames(body).toSet();
        val terminal = namesInSet(body, "val TERMINAL: Set<String> = setOf(");
        val unknown = terminal - declared;
        assertTrue("TaskRunStatus.TERMINAL 引用了未声明的常量：" + unknown, unknown.isEmpty());
    }

    @Test
    fun `approval settled only references declared constants`() {
        val body = objectBody(source("ApprovalRequestEntity.kt"), "ApprovalStatus");
        val declared = declaredNames(body).toSet();
        val settled = namesInSet(body, "val SETTLED: Set<String> = setOf(");
        val unknown = settled - declared;
        assertTrue("ApprovalStatus.SETTLED 引用了未声明的常量：" + unknown, unknown.isEmpty());
    }

    @Test
    fun `publication kinds never overlap internal only kinds`() {
        val body = objectBody(source("PublicationOutboxEntity.kt"), "PublicationKind");
        val all = namesInSet(body, "val all: Set<String> = setOf(");
        val internal = namesInSet(body, "val INTERNAL_ONLY: Set<String> = setOf(");
        val overlap = all intersect internal;
        assertTrue("内部类型不能同时是合法发布类型：" + overlap, overlap.isEmpty());
    }

    @Test
    fun `event kinds declared as constants all appear in the all set`() {
        val body = objectBody(source("BotEventEntity.kt"), "BotEventKind");
        val declared = declaredNames(body).toSet();
        val inSet = namesInSet(body, "val all: Set<String> = setOf(");
        val missing = declared - inSet;
        assertTrue("这些事件常量声明了但没进 all 集合：" + missing, missing.isEmpty());
    }

    @Test
    fun `review dispositions declared as constants all appear in the all set`() {
        val body = objectBody(source("BotReviewWorker.kt"), "ReviewDisposition");
        val declared = declaredNames(body).toSet();
        val inSet = namesInSet(body, "val all: Set<String> = setOf(");
        val missing = declared - inSet;
        assertTrue("审核结论常量没进 all 集合：" + missing, missing.isEmpty());
    }
}
