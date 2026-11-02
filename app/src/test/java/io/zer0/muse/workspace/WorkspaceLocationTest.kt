package io.zer0.muse.workspace

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * 工作区放在哪儿（默认私有目录，用户主动选择后才去公共下载目录）。
 *
 * 这里守的是"看不见"这件事的底线：**任何异常都不能让工作区打不开**。
 * 所以每个"选过但不可用"的分支都必须退回私有目录。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class WorkspaceLocationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    @After
    fun resetPreference() {
        WorkspaceLocation.setPrefersDownloads(context, false)
    }

    @Test
    fun `the default workspace is the private folder`() {
        assertFalse(WorkspaceLocation.prefersDownloads(context))
        assertEquals(
            File(context.filesDir, WorkspaceLocation.INTERNAL_DIR_NAME).absolutePath,
            WorkspaceLocation.resolve(context).absolutePath,
        )
    }

    @Test
    fun `choosing downloads without the permission still resolves to the private folder`() {
        WorkspaceLocation.setPrefersDownloads(context, true)

        // Robolectric 默认没有「所有文件访问」，所以必须退回私有目录而不是抛异常
        assertEquals(
            "没有权限时必须退回私有目录",
            File(context.filesDir, WorkspaceLocation.INTERNAL_DIR_NAME).absolutePath,
            WorkspaceLocation.resolve(context).absolutePath,
        )
    }

    @Test
    fun `migrating without the permission fails with an actionable reason`() {
        val result = WorkspaceLocation.migrateToDownloads(context)

        assertTrue("应当是失败: $result", result is WorkspaceLocation.Migration.Failed)
        assertTrue(
            "理由要指出缺哪项权限: ${(result as WorkspaceLocation.Migration.Failed).reason}",
            result.reason.contains("所有文件访问"),
        )
        assertFalse("失败后不能把偏好留成 true", WorkspaceLocation.prefersDownloads(context))
    }

    @Test
    fun `the two candidate paths are where users expect them`() {
        assertTrue(
            "私有目录要在 filesDir 下",
            WorkspaceLocation.internalRoot(context).absolutePath.contains(context.filesDir.absolutePath),
        )
        assertTrue(
            "公共目录要用 Download/Muse",
            WorkspaceLocation.downloadsRoot().absolutePath
                .replace('\\', '/')
                .endsWith("/Download/${WorkspaceLocation.DOWNLOAD_DIR_NAME}"),
        )
    }

    @Test
    fun `moving back clears the preference`() {
        WorkspaceLocation.setPrefersDownloads(context, true)

        val result = WorkspaceLocation.migrateBackInternal(context)

        assertFalse("搬回私有目录后偏好必须清掉", WorkspaceLocation.prefersDownloads(context))
        assertTrue(
            "私有目录里没东西时也应当给出可用路径: $result",
            result is WorkspaceLocation.Migration.AlreadyThere ||
                result is WorkspaceLocation.Migration.Moved,
        )
    }
}
