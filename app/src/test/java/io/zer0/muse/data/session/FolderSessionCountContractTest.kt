package io.zer0.muse.data.session

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 文件夹会话计数合同。
 *
 * 回归背景：`folders.sessionCount` 是冗余字段，靠「移动会话」时双写维护。
 * 删除会话的路径长期没有递减，于是「往文件夹加一个会话再删掉」会留下 -1。
 */
class FolderSessionCountContractTest {

    private fun source(relative: String): String {
        val candidates = listOf(File(relative), File("app/$relative"))
        return candidates.firstOrNull { it.isFile }?.readText()
            ?: error("无法定位 $relative")
    }

    private val sessionRepo: String by lazy { source("src/main/java/io/zer0/muse/data/session/SessionRepository.kt") }
    private val folderRepo: String by lazy { source("src/main/java/io/zer0/muse/data/session/FolderRepository.kt") }
    private val sessionDao: String by lazy { source("src/main/java/io/zer0/muse/data/session/SessionDao.kt") }

    @Test
    fun `deleting a session decrements its folder count`() {
        val start = sessionRepo.indexOf("suspend fun deleteSession(")
        require(start >= 0) { "未找到 deleteSession" }
        val end = sessionRepo.indexOf("private suspend fun cleanSessionMemory(", start)
        require(end > start)
        val block = sessionRepo.substring(start, end)
        assertTrue(
            "删除会话必须递减所在文件夹计数",
            block.contains("incrementSessionCount(folderId, -1)"),
        )
        assertTrue(
            "必须在删除前读到归属文件夹",
            block.contains("sessionDao.getById(id)?.folderId"),
        )
        assertTrue(
            "递减必须在同一个事务里（否则进程被杀会留下漂移）",
            block.indexOf("withTransaction") < block.indexOf("incrementSessionCount(folderId, -1)"),
        )
    }

    @Test
    fun `card favorite container is excluded from counting`() {
        // 卡片收藏容器用 folderId 当哨兵值，不是真实文件夹，不能参与计数。
        assertTrue(
            "必须排除哨兵值",
            sessionRepo.contains("folderId != CARD_FAVORITE_SESSION_MARKER"),
        )
    }

    @Test
    fun `reconcile counts only active sessions`() {
        assertTrue("必须提供校准方法", folderRepo.contains("suspend fun reconcileSessionCounts()"))
        assertTrue(
            "校准口径必须排除软删除会话，否则会把「最近删除」里的又算回去",
            sessionDao.contains("deletedAt IS NULL"),
        )
        assertTrue(
            "校准必须按 folderId 分组统计",
            sessionDao.contains("GROUP BY folderId"),
        )
        assertTrue(
            "校准必须幂等：只在发现漂移时写库",
            folderRepo.contains("if (folder.sessionCount != expected)"),
        )
    }

    @Test
    fun `reconcile is wired into startup`() {
        val app = source("src/main/java/io/zer0/muse/MuseApp.kt")
        assertTrue(
            "校准必须接到启动流程，否则存量 -1 不会自愈",
            app.contains("reconcileSessionCounts()"),
        )
        assertFalse(
            "校准失败不得影响启动",
            app.substring(app.indexOf("reconcileSessionCounts()"))
                .substringBefore("\n            }")
                .let { !it.contains("onError") },
        )
    }
}
