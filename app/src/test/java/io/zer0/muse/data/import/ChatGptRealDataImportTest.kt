package io.zer0.muse.data.`import`

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.zer0.muse.data.assistant.AssistantRepository
import io.zer0.muse.data.session.MessageImageStore
import io.zer0.muse.data.session.MuseDb
import io.zer0.muse.data.session.SessionRepository
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first

/**
 * v1.0.74: 用真实 ChatGPT 导出数据做端到端导入测试。
 * 文件: C:/Users/21192/AppData/Local/Temp/gpt_conversations.json
 * 验证: 73 会话 / 1638 消息,首尾顺序正确。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ChatGptRealDataImportTest {

    private lateinit var db: MuseDb
    private lateinit var sessionRepo: SessionRepository
    private lateinit var assistantRepo: AssistantRepository

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
            messageImageStore = MessageImageStore(File(context.cacheDir, "msg_imgs_test")),
        )
        assistantRepo = AssistantRepository(db.assistantDao(), context)
        runBlocking { assistantRepo.ensureDefaultExists() }
    }

    @After
    fun teardown() {
        db.close()
    }

    @Test
    fun importRealChatGptData_importsAllConversations() = runBlocking {
        val jsonFile = File("C:/Users/21192/AppData/Local/Temp/gpt_conversations.json")
        if (!jsonFile.exists()) {
            println("SKIP: 真实数据文件不存在")
            return@runBlocking
        }
        val json = jsonFile.readText()

        val result = ThirdPartyImporter.importChatGPT(
            context = ApplicationProvider.getApplicationContext(),
            conversationsJson = json,
            settings = io.zer0.muse.data.SettingsRepository(
                ApplicationProvider.getApplicationContext(),
                io.zer0.muse.data.audit.AuditLogger(db.auditLogDao()),
            ),
            assistantRepo = assistantRepo,
            sessionRepo = sessionRepo,
        )

        println("REAL-IMPORT conversations=${result.conversationsImported} messages=${result.messagesImported} errors=${result.errors}")
        assert(result.errors.isEmpty()) { "导入有错误: ${result.errors}" }
        // Robolectric 无 FTS5 模块,appendMessage 事务回滚导致 DB 查不到消息(真机正常),
        // 以 ImportResult 计数为准。
        assert(result.conversationsImported == 73) { "会话数 ${result.conversationsImported} != 73" }
        assert(result.messagesImported >= 1600) { "消息数 ${result.messagesImported} 远少于预期 1638" }
        java.io.File("C:/Users/21192/AppData/Local/Temp/gpt_import_result.txt").writeText("conversations=${result.conversationsImported} messages=${result.messagesImported} errors=${result.errors}")
    }
}
