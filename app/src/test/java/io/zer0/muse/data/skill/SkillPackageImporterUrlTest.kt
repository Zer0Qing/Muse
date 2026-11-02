package io.zer0.muse.data.skill

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.mockk.mockk
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
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 从 GitHub 地址安装技能包（对比清单 P1-09）。
 *
 * 下载器是可注入的，所以整条链路（解析 → 下载 → 解包 → 落库）都能在这里跑通，
 * 只有真实 HTTP 那一跳需要真机验证 —— 这比"写完等真机碰运气"实在得多。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SkillPackageImporterUrlTest {

    private lateinit var context: Context
    private lateinit var importer: SkillPackageImporter

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        importer = SkillPackageImporter(context, mockk(relaxed = true))
    }

    @After
    fun tearDown() {
        File(context.filesDir, "workspace/skills").deleteRecursively()
    }

    /** 造一个最小可用的技能包 ZIP。 */
    private fun writeZip(target: File, name: String = "远端技能") {
        target.parentFile?.mkdirs()
        ZipOutputStream(target.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("SKILL.md"))
            zip.write("---\nname: $name\ndescription: 从仓库装来的\n---\n按这个做".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("references/note.md"))
            zip.write("参考内容".toByteArray())
            zip.closeEntry()
        }
    }

    @Test
    fun `a successful download installs the package and keeps its resources`() = runBlocking {
        val downloader = SkillPackageImporter.ArchiveDownloader { _, target ->
            writeZip(target)
            null
        }

        val outcome = importer.importUrl("https://github.com/owner/repo", downloader)

        assertTrue("应安装成功: $outcome", outcome is SkillPackageImporter.Outcome.Installed)
        val installed = outcome as SkillPackageImporter.Outcome.Installed
        assertEquals("远端技能", installed.skill.name)
        assertTrue("资源文件应随包落盘", File(installed.packageDir, "references/note.md").exists())
    }

    @Test
    fun `a download failure surfaces its reason instead of a generic error`() = runBlocking {
        val downloader = SkillPackageImporter.ArchiveDownloader { _, _ -> "下载失败：HTTP 404" }

        val outcome = importer.importUrl("https://github.com/owner/repo", downloader)

        assertEquals("下载失败：HTTP 404", (outcome as SkillPackageImporter.Outcome.Failed).reason)
    }

    @Test
    fun `an invalid url never reaches the downloader`() = runBlocking {
        var calls = 0
        val downloader = SkillPackageImporter.ArchiveDownloader { _, _ ->
            calls++
            null
        }

        val outcome = importer.importUrl("https://example.com/owner/repo", downloader)

        assertTrue(outcome is SkillPackageImporter.Outcome.Failed)
        assertEquals("来源不合法就不该发请求（否则等于内网探测器）", 0, calls)
    }

    @Test
    fun `the temporary archive is cleaned up after import`() = runBlocking {
        val downloader = SkillPackageImporter.ArchiveDownloader { _, target ->
            writeZip(target)
            null
        }

        importer.importUrl("https://github.com/owner/repo", downloader)

        val leftovers = context.cacheDir.listFiles { file -> file.name.startsWith("skill-url-") }
        assertTrue("缓存里的临时包必须清掉: ${leftovers?.map { it.name }}", leftovers.isNullOrEmpty())
    }
}
