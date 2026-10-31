package io.zer0.muse.diagnostic

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipFile

/**
 * 诊断导出包的打包内容验证。
 *
 * 重点不是 zip 格式,而是「用户点一下导出,后台唤醒状态也会一起过来」这条链路:
 * [DiagnosticExporter.buildDiagnosticZip] 必须把 background-work.txt 放进包里,
 * 且内容是 [io.zer0.muse.schedule.BackgroundWakeDiagnostics.formatSnapshot] 的快照文本。
 *
 * 采集本身在测试环境必然失败(没有 Koin、WorkManager 未初始化),这正好覆盖
 * 「采集失败不影响整包导出」这条降级路径。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DiagnosticExporterZipTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun entriesOf(zip: File): Map<String, String> = ZipFile(zip).use { zipFile ->
        zipFile.entries().asSequence()
            .filter { entry -> !entry.isDirectory }
            .associate { entry -> entry.name to zipFile.getInputStream(entry).bufferedReader().readText() }
    }

    @Test
    fun `diagnostic zip carries the background work snapshot`() = runBlocking {
        val zip = DiagnosticExporter.buildDiagnosticZip(context)
        assertNotNull("诊断 zip 打包失败", zip)
        val entries = entriesOf(zip!!)
        try {
            assertTrue("缺少设备信息", entries.containsKey("device-info.txt"))
            assertTrue("缺少后台唤醒快照", entries.containsKey("background-work.txt"))
            val snapshot = entries.getValue("background-work.txt")
            assertTrue("快照缺少表头", snapshot.startsWith("# Muse 后台唤醒快照"))
            assertTrue("快照缺少任务计数", snapshot.contains("work_count="))
            // 采集失败时仍然要有一行说明,而不是一个空文件
            assertTrue(
                "采集失败时快照应有 work_manager_error 或任务行",
                snapshot.contains("work_manager_error=") || snapshot.contains(" | kind="),
            )
        } finally {
            zip.delete()
        }
    }
}
