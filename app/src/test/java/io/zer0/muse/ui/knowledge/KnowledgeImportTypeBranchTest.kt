package io.zer0.muse.ui.knowledge

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 导入分流：是不是压缩包决定「要不要先问导入到哪个库」。
 *
 * 用户报的现象是「导入压缩包时也让我选库」—— 压缩包自带一套完整资料，
 * 直接解压成它自己的新库才对。判定错了就会把这个问题带回来。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class KnowledgeImportTypeBranchTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `压缩包后缀认成压缩包`() {
        listOf("资料包.zip", "bundle.ZIP", "老资料.7z", "文档.RAR").forEach { name ->
            assertTrue("$name 应认成压缩包", isArchiveUri(context, Uri.parse("content://x/$name")))
        }
    }

    @Test
    fun `普通文档不认成压缩包`() {
        listOf("说明.txt", "手册.pdf", "报告.docx", "笔记.md", "无后缀").forEach { name ->
            assertFalse("$name 不该认成压缩包", isArchiveUri(context, Uri.parse("content://x/$name")))
        }
    }

    @Test
    fun `大小写混写也认`() {
        assertTrue(isArchiveUri(context, Uri.parse("content://x/MyBundle.Zip")))
    }
}
