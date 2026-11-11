package io.zer0.muse.knowledge

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.zer0.muse.data.knowledge.KnowledgeV2DocEntity
import io.zer0.muse.data.session.MuseDb
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * R10 步骤「会话附件隔离」(计划 R10):真实 Android 文件库上的持久 owner 隔离。
 */
@RunWith(AndroidJUnit4::class)
class KnowledgeSessionAttachmentDeviceTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private var db: MuseDb? = null

    @After
    fun tearDown() {
        db?.close()
        context.deleteDatabase("kb-attachment.db")
    }

    private fun open(): MuseDb {
        context.deleteDatabase("kb-attachment.db")
        return Room.databaseBuilder(context, MuseDb::class.java, "kb-attachment.db")
            .allowMainThreadQueries()
            .build()
            .also { db = it }
    }

    @Test
    fun sessionAttachmentIsolationSurvivesDeleteAndTransfer() = runBlocking {
        val database = open()
        val dao = database.knowledgeV2Dao()
        dao.insertDoc(
            KnowledgeV2DocEntity(docId = "att-1", ownerType = "SessionAttachment", ownerId = "session-1", createdAt = 1L, updatedAt = 1L),
        )
        dao.insertDoc(
            KnowledgeV2DocEntity(docId = "att-2", ownerType = "SessionAttachment", ownerId = "session-1", createdAt = 1L, updatedAt = 1L),
        )
        dao.insertDoc(
            KnowledgeV2DocEntity(docId = "att-other", ownerType = "SessionAttachment", ownerId = "session-2", createdAt = 1L, updatedAt = 1L),
        )

        assertEquals(
            "会话 1 只能看到自己的附件",
            setOf("att-1", "att-2"),
            dao.getByOwner("SessionAttachment", "session-1").map { it.docId }.toSet(),
        )

        // 转存到知识库:owner 改变,不随原会话删除。
        dao.reassignOwner("att-2", "UserLibrary", "kb-1", updatedAt = 2L)
        val deleted = dao.deleteByOwner("SessionAttachment", "session-1")
        assertEquals(1, deleted)
        assertNull("未转存附件随会话删除", dao.getDoc("att-1"))
        assertNotNull("转存附件独立存活", dao.getDoc("att-2"))
        assertEquals("UserLibrary", dao.getDoc("att-2")!!.ownerType)
        assertNotNull("其它会话附件不受影响", dao.getDoc("att-other"))
    }
}
