package io.zer0.muse.ui.settings

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.test.core.app.ApplicationProvider
import io.zer0.muse.R
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.sticker.StickerLibraryRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.mp.KoinPlatform
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.io.FileOutputStream

/**
 * 表情包管理界面的真机 Compose 测试（表情包重做 P4/P3 的界面部分）。
 *
 * 为什么必须存在：这一整片界面（预览网格、重命名/导出 chip、长按操作面板里的
 * 前移后移）**只在 JVM 单测里存在过** —— Robolectric 测的是数据层，
 * composable 从来没有真机上 compose 过。少一个字符串、写错一个 contentDescription，
 * 单测全绿而用户一点就崩，就是这么发生的。
 *
 * 这里刻意**不走 SAF**：系统文件选择器不该由单测去顶。
 * 走的是"仓库里已有图 → 界面能管它们"这条真实路径。
 */
@RunWith(AndroidJUnit4::class)
class StickerLibrarySectionUiTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Application get() = ApplicationProvider.getApplicationContext()

    private val settings: SettingsRepository get() = KoinPlatform.getKoin().get()

    private val repository: StickerLibraryRepository get() = KoinPlatform.getKoin().get()

    /** 画一张真的 PNG（200x200 带边框）：尺寸判定要能解出宽高，假字节在这儿会被正确地拒。 */
    private fun makePng(name: String, color: Int): File {
        val bitmap = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint().apply { isAntiAlias = true }
        paint.color = -0x111112 // 白底
        canvas.drawRect(0f, 0f, 200f, 200f, paint)
        paint.color = color
        canvas.drawRect(20f, 20f, 180f, 180f, paint)
        paint.color = -0x1000000
        canvas.drawRect(60f, 120f, 140f, 165f, paint) // 一条"配文"形状
        val file = File(context.cacheDir, name)
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return file
    }

    @Before
    fun seedLibrary() {
        runBlocking {
            repository.clearAll()
            repository.importImages(
                listOf(
                    Uri.fromFile(makePng("第一张.png", 0xFFFF6B6B.toInt())),
                    Uri.fromFile(makePng("第二张.png", 0xFF4ECDC4.toInt())),
                ),
                "界面测试包",
            )
        }
    }

    private fun showSection() {
        compose.setContent {
            StickerLibrarySection(settings = settings, scope = androidx.compose.runtime.rememberCoroutineScope())
        }
    }

    @Test
    fun managementEntriesRender() {
        showSection()

        compose.onNodeWithText(context.getString(R.string.settings_sticker_upload)).assertExists()
        compose.onNodeWithText(context.getString(R.string.settings_sticker_add_from_gallery)).assertExists()
    }

    @Test
    fun selectingAPackOffersRenameAndExport() {
        showSection()

        compose.onNodeWithText("界面测试包").performClick()

        compose.onNodeWithText(context.getString(R.string.settings_sticker_rename_pack)).assertExists()
        compose.onNodeWithText(context.getString(R.string.settings_sticker_export_pack)).assertExists()
    }

    @Test
    fun longPressOpensPanelWithCaptionAndMoveControls() {
        val firstName = runBlocking { repository.listStickers("界面测试包").first().fileName }
        showSection()

        compose.onNodeWithContentDescription(firstName).performTouchInput { longClick() }

        compose.onNodeWithText(context.getString(R.string.settings_sticker_caption_label)).assertExists()
        compose.onNodeWithText(context.getString(R.string.settings_sticker_move_up)).assertExists()
        compose.onNodeWithText(context.getString(R.string.settings_sticker_move_down)).assertExists()
    }

    @Test
    fun pressingMoveDownActuallyPersistsNewOrder() {
        val before = runBlocking { repository.listStickers("界面测试包").map { it.id } }
        assertEquals(2, before.size)
        showSection()

        // 长按第一张，点"后移一位"，然后到仓库里核对顺序真的换了
        compose.onNodeWithContentDescription(runBlocking { repository.listStickers("界面测试包").first().fileName })
            .performTouchInput { longClick() }
        compose.onNodeWithText(context.getString(R.string.settings_sticker_move_down)).performClick()

        val after = runBlocking { repository.listStickers("界面测试包").map { it.id } }
        assertEquals("后移一位应让原来的第一变成第二", before.reversed(), after)
    }
}
