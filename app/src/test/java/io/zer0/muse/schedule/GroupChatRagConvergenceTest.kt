package io.zer0.muse.schedule

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * 群聊 RAG 收敛守卫:群聊必须复用主聊的范围解析与注入收口,而不是自己拼一套。
 */
class GroupChatRagConvergenceTest {

    private fun source(): String {
        val candidates = listOf(
            Path.of("src/main/java/io/zer0/muse/schedule/GroupChatScheduler.kt"),
            Path.of("app/src/main/java/io/zer0/muse/schedule/GroupChatScheduler.kt"),
        )
        val path = candidates.firstOrNull(Files::exists)
            ?: error("Unable to locate GroupChatScheduler.kt from ${System.getProperty("user.dir")}")
        return Files.newBufferedReader(path).use { it.readText() }
    }

    @Test
    fun `group chat rag merges the assistant override instead of using the global config`() {
        val text = source()
        assertTrue(
            "群聊必须按 assistant 合并 RAG 覆盖",
            text.contains("mergeRagConfigOverride(assistant, baseConfig)"),
        )
    }

    @Test
    fun `group chat rag resolves binding scope through the shared contract`() {
        val text = source()
        assertTrue(
            "群聊必须走 resolveBindingScope 解析 KB/文件夹绑定",
            text.contains("ragService.resolveBindingScope("),
        )
        assertTrue(
            "群聊必须用 mergeMentionWithPermit 合并 @mention 与许可",
            text.contains("mergeMentionWithPermit("),
        )
        assertTrue(
            "KnowledgeScope.Failed 必须按零结果处理",
            text.contains("KnowledgeScope.Failed"),
        )
        assertTrue(
            "KnowledgeScope.Invalid/None 必须按零结果处理",
            text.contains("KnowledgeScope.Invalid") && text.contains("KnowledgeScope.None"),
        )
    }

    @Test
    fun `group chat rag uses the shared clamp and citation helper`() {
        val text = source()
        assertTrue(
            "群聊必须用 RagInjectionBudget 做预算截断 + 引用裁剪",
            text.contains("RagInjectionBudget.clampAndTrim("),
        )
        assertFalse(
            "群聊不得只 clamp 文本、把未截断的 citations 带下去",
            text.contains("buildInjectionContextWithCitations(ragQuery, ragConfig, scopeDocIds)"),
        )
    }
}
