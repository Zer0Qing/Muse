package io.zer0.muse.data.`import`

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.zer0.muse.data.assistant.AssistantRepository
import io.zer0.muse.data.session.MessageImageStore
import io.zer0.muse.data.session.MuseDb
import io.zer0.muse.data.session.SessionRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipFile

/**
 * DeepSeek 官方导出（export_format_version 3）的导入。
 *
 * 用真实导出包做端到端验证：只有真实数据能暴露「索引与会话文件标题不一致」
 * 「THINK 与 RESPONSE 是两个 fragment」这类结构性假设错误。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DeepSeekImportTest {

    private lateinit var db: MuseDb
    private lateinit var sessionRepo: SessionRepository
    private lateinit var assistantRepo: AssistantRepository
    private lateinit var workDir: File

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, MuseDb::class.java)
            .allowMainThreadQueries()
            .build()
        sessionRepo = SessionRepository(
            sessionDao = db.sessionDao(),
            messageDao = db.messageDao(),
            database = db,
            context = context,
            messageImageStore = MessageImageStore(File(context.cacheDir, "ds_imgs_test")),
        )
        assistantRepo = AssistantRepository(db.assistantDao(), context)
        runBlocking { assistantRepo.ensureDefaultExists() }
        workDir = File(context.cacheDir, "ds_import").apply { mkdirs() }
    }

    @After
    fun teardown() {
        db.close()
    }

    // 把导出包里的会话 JSON 与索引解到磁盘，模拟 ZIP 解包后的产物。
    private fun extractExport(zipPath: String): Pair<List<File>, File?> {
        val zip = ZipFile(zipPath)
        val sessions = mutableListOf<File>()
        var index: File? = null
        zip.use { z ->
            z.entries().toList().forEach { entry ->
                if (entry.isDirectory) return@forEach
                val name = entry.name
                val isSession = name.startsWith("chat_sessions/") && name.endsWith(".json")
                if (!isSession && name != "chat_session_index.json") return@forEach
                val out = File(workDir, name.substringAfterLast('/'))
                z.getInputStream(entry).use { input -> out.outputStream().use { input.copyTo(it) } }
                if (isSession) sessions.add(out) else index = out
            }
        }
        return sessions to index
    }

    @Test
    fun `real deepseek export imports every conversation`() = runBlocking {
        val zipPath = "C:/Users/21192/AppData/Local/Temp/deepseek_data-2026-11-18.zip"
        val zipFile = File(zipPath)
        if (!zipFile.exists()) {
            println("SKIP: 真实导出包不存在")
            return@runBlocking
        }
        val (sessions, index) = extractExport(zipPath)
        assertTrue("应解出会话文件", sessions.isNotEmpty())

        val result = ThirdPartyImporter.importDeepSeek(
            context = ApplicationProvider.getApplicationContext(),
            sessionFiles = sessions,
            indexFile = index,
            assistantRepo = assistantRepo,
            sessionRepo = sessionRepo,
        )

        println("DEEPSEEK-IMPORT conversations=${result.conversationsImported} messages=${result.messagesImported} errors=${result.errors}")
        assertEquals("会话数应等于会话文件数", sessions.size, result.conversationsImported)
        assertTrue("应导入出消息", result.messagesImported > 0)
    }

    @Test
    fun `search and open fragments are not written into message body`() = runBlocking {
        val zipPath = "C:/Users/21192/AppData/Local/Temp/deepseek_data-2026-11-18.zip"
        if (!File(zipPath).exists()) return@runBlocking
        val (sessions, index) = extractExport(zipPath)

        val result = ThirdPartyImporter.importDeepSeek(
            context = ApplicationProvider.getApplicationContext(),
            sessionFiles = sessions,
            indexFile = index,
            assistantRepo = assistantRepo,
            sessionRepo = sessionRepo,
        )

        // 检索结果混进正文会让历史消息变成一堆 URL 与摘要。
        // 逐会话取消息，确认没有任何一条含检索片段标记。
        val polluted = db.messageDao().getAllForFtsRebuild()
            .filter { m -> m.content.contains("TOOL_SEARCH") || m.content.contains("TOOL_OPEN") }
            .map { it.content.take(80) }
        assertTrue("正文不应包含检索片段标记: ${polluted.take(2)}", polluted.isEmpty())
        assertEquals(0, result.errors.count { it.contains("解析失败") })
    }
}
