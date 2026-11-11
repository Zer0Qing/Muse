package io.zer0.muse.knowledge

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.zer0.muse.data.knowledge.KnowledgeJobEntity
import io.zer0.muse.data.knowledge.KnowledgeJobItemEntity
import io.zer0.muse.data.session.MuseDb
import io.zer0.muse.knowledge.domain.JobStepResult
import io.zer0.muse.knowledge.domain.KnowledgeAvailability
import io.zer0.muse.knowledge.domain.KnowledgeJobStage
import io.zer0.muse.knowledge.domain.KnowledgeJobState
import io.zer0.muse.knowledge.jobs.JobRunner
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * R10 进程恢复设备测试(计划 R10「导入→退出页面/杀进程→恢复」)。
 *
 * 真实 Android Room 文件库上验证:作业项在「进程重启」(新建 JobRunner / 重新打开 DB)
 * 后 resume 幂等、不重复创建文档、终态不被重复执行。
 */
@RunWith(AndroidJUnit4::class)
class KnowledgeProcessRecoveryTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private var db: MuseDb? = null

    @After
    fun tearDown() {
        db?.close()
        context.deleteDatabase("kb-recovery.db")
        db = null
    }

    private fun openDb(): MuseDb {
        context.deleteDatabase("kb-recovery.db")
        val file = context.getDatabasePath("kb-recovery.db")
        file.parentFile?.mkdirs()
        return Room.databaseBuilder(context, MuseDb::class.java, "kb-recovery.db")
            .allowMainThreadQueries()
            .build()
    }

    @Test
    fun jobResumesIdempotentlyAfterReopen() = runBlocking {
        var database = openDb()
        db = database
        database.knowledgeJobDao().insertJob(
            KnowledgeJobEntity(jobId = "job-1", ownerType = "UserLibrary", ownerId = "kb-1", state = KnowledgeJobState.QUEUED.name),
        )
        database.knowledgeJobDao().insertJobItem(
            KnowledgeJobItemEntity(
                jobItemId = "item-1", jobId = "job-1", docId = "doc-1",
                state = KnowledgeJobState.QUEUED.name, stage = KnowledgeJobStage.ACQUIRE.name,
            ),
        )
        val firstRun = JobRunner(database.knowledgeJobDao())
        val result1 = firstRun.run("item-1") {
            advance(KnowledgeJobStage.PARSE)
            advance(KnowledgeJobStage.CHUNK)
            JobStepResult.Succeeded("item-1", KnowledgeAvailability.KEYWORD_READY)
        }
        assertTrue(result1 is JobStepResult.Succeeded)
        assertEquals(KnowledgeJobState.SUCCEEDED.name, database.knowledgeJobDao().getJobItem("item-1")!!.state)

        // 模拟进程重启:关闭并重开同一个文件库。
        database.close()
        database = Room.databaseBuilder(context, MuseDb::class.java, "kb-recovery.db")
            .allowMainThreadQueries()
            .build()
        db = database

        var executedAgain = false
        val secondRun = JobRunner(database.knowledgeJobDao())
        val result2 = secondRun.run("item-1") {
            executedAgain = true
            JobStepResult.Succeeded("item-1", KnowledgeAvailability.KEYWORD_READY)
        }
        assertTrue("重开后应直接返回终态", result2 is JobStepResult.Succeeded)
        assertEquals("终态不得重复执行", false, executedAgain)
    }
}
