package io.zer0.muse.intent

import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 通知深链解析回归测试。
 *
 * 通知点击经过 MainActivity 后统一由 ShareIntentHandler 解析；这些测试
 * 确保带实体 ID 的通知不会退回到对应功能的首页。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ShareIntentHandlerTest {

    private val handler by lazy {
        ShareIntentHandler(ApplicationProvider.getApplicationContext())
    }

    @Test
    fun `session deep link preserves session id`() = runBlocking {
        val result = handler.handle(deepLink("muse://session/session-20260819"))

        assertEquals(
            ShareIntentHandler.ShareResult.OpenSession("session-20260819"),
            result,
        )
    }

    @Test
    fun `scheduled task deep link preserves task id`() = runBlocking {
        val result = handler.handle(deepLink("muse://scheduled-task/task-20260819"))

        assertEquals(
            ShareIntentHandler.ShareResult.OpenScheduledTask("task-20260819"),
            result,
        )
    }

    @Test
    fun `quick note deep link preserves note id`() = runBlocking {
        val result = handler.handle(deepLink("muse://quick-note/note-20260819"))

        assertEquals(
            ShareIntentHandler.ShareResult.OpenQuickNote("note-20260819"),
            result,
        )
    }

    @Test
    fun `settings and knowledge notification targets resolve to dedicated pages`() = runBlocking {
        assertEquals(
            ShareIntentHandler.ShareResult.OpenSettingsData,
            handler.handle(deepLink("muse://settings-data")),
        )
        assertEquals(
            ShareIntentHandler.ShareResult.OpenKnowledgeBases,
            handler.handle(deepLink("muse://knowledge-bases")),
        )
    }

    @Test
    fun `invalid entity id is rejected instead of opening a generic page`() = runBlocking {
        assertEquals(
            ShareIntentHandler.ShareResult.None,
            handler.handle(deepLink("muse://scheduled-task/../../settings")),
        )
        assertEquals(
            ShareIntentHandler.ShareResult.None,
            handler.handle(deepLink("muse://quick-note/")),
        )
    }

    private fun deepLink(value: String): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse(value))
}
