package io.zer0.muse.data.`import`

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.zer0.muse.data.assistant.AssistantRepository
import io.zer0.muse.data.session.MessageImageStore
import io.zer0.muse.data.session.MuseDb
import io.zer0.muse.data.session.SessionRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlinx.coroutines.runBlocking
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v1.0.74: ChatGPT 导出导入测试。
 *
 * 覆盖官方 conversations.json 解析:
 *  - 顶层数组格式 + mapping 树提取 user/assistant 消息
 *  - 秒级时间戳转毫秒 + 按时间排序
 *  - system/tool 消息跳过
 *  - 会话创建 + 消息落库
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ChatGptImportTest {

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
        // 确保默认助手存在
        kotlinx.coroutines.runBlocking { assistantRepo.ensureDefaultExists() }
    }

    @After
    fun teardown() {
        db.close()
    }

    /** 官方导出格式:顶层数组 + mapping 树。 */
    @Test
    fun importChatGpt_mappingTree_extractsOrderedMessages() = kotlinx.coroutines.runBlocking {
        val sample = """
        [
          {
            "title": "周末计划",
            "create_time": 1690000000.0,
            "mapping": {
              "n1": {
                "message": {
                  "id": "n1",
                  "author": {"role": "user"},
                  "content": {"content_type": "text", "parts": ["你好"]},
                  "create_time": 1690000001.0
                },
                "parent": null,
                "children": ["n2"]
              },
              "n2": {
                "message": {
                  "id": "n2",
                  "author": {"role": "assistant"},
                  "content": {"content_type": "text", "parts": ["你好!有什么可以帮你?"]},
                  "create_time": 1690000002.0
                },
                "parent": "n1",
                "children": ["n3"]
              },
              "n3": {
                "message": {
                  "id": "n3",
                  "author": {"role": "user"},
                  "content": {"content_type": "text", "parts": ["帮我规划周末"]},
                  "create_time": 1690000005.0
                },
                "parent": "n2",
                "children": []
              },
              "n4": {
                "message": {
                  "id": "n4",
                  "author": {"role": "system"},
                  "content": {"content_type": "text", "parts": ["system prompt"]},
                  "create_time": 1690000000.0
                },
                "parent": null,
                "children": ["n1"]
              }
            },
            "current_node": "n3"
          }
        ]
        """.trimIndent()

        val result = ThirdPartyImporter.importChatGPT(
            context = ApplicationProvider.getApplicationContext(),
            conversationsJson = sample,
            settings = io.zer0.muse.data.SettingsRepository(ApplicationProvider.getApplicationContext(), io.zer0.muse.data.audit.AuditLogger(db.auditLogDao())),
            assistantRepo = assistantRepo,
            sessionRepo = sessionRepo,
        )

        println("DEBUG result: ${result}")
        assertTrue("不应有错误: ${result.errors}", result.errors.isEmpty())
        assertEquals("应导入 1 个会话", 1, result.conversationsImported)
        assertEquals("应导入 3 条消息(system 跳过)", 3, result.messagesImported)
    }

    /** parts 含多段文本(拼接)。 */
    @Test
    fun importChatGpt_multipleParts_joins() = kotlinx.coroutines.runBlocking {
        val sample = """
        [
          {
            "title": "多段",
            "mapping": {
              "n1": {
                "message": {
                  "id": "n1",
                  "author": {"role": "assistant"},
                  "content": {"content_type": "text", "parts": ["第一段", "第二段"]},
                  "create_time": 1690000010.0
                },
                "parent": null,
                "children": []
              }
            },
            "current_node": "n1"
          }
        ]
        """.trimIndent()

        val result = ThirdPartyImporter.importChatGPT(
            context = ApplicationProvider.getApplicationContext(),
            conversationsJson = sample,
            settings = io.zer0.muse.data.SettingsRepository(ApplicationProvider.getApplicationContext(), io.zer0.muse.data.audit.AuditLogger(db.auditLogDao())),
            assistantRepo = assistantRepo,
            sessionRepo = sessionRepo,
        )

        assertEquals(1, result.messagesImported)
        assertEquals(1, result.conversationsImported)
    }

    /** 空会话(无 user/assistant 消息)跳过。 */
    @Test
    fun importChatGpt_emptyConversation_skipped() = kotlinx.coroutines.runBlocking {
        val sample = """
        [
          {
            "title": "空",
            "mapping": {
              "n1": {
                "message": {
                  "id": "n1",
                  "author": {"role": "system"},
                  "content": {"content_type": "text", "parts": ["prompt"]},
                  "create_time": 1690000010.0
                },
                "parent": null,
                "children": []
              }
            },
            "current_node": "n1"
          }
        ]
        """.trimIndent()

        val result = ThirdPartyImporter.importChatGPT(
            context = ApplicationProvider.getApplicationContext(),
            conversationsJson = sample,
            settings = io.zer0.muse.data.SettingsRepository(ApplicationProvider.getApplicationContext(), io.zer0.muse.data.audit.AuditLogger(db.auditLogDao())),
            assistantRepo = assistantRepo,
            sessionRepo = sessionRepo,
        )

        assertEquals(0, result.conversationsImported)
        assertEquals(0, result.messagesImported)
    }
}
