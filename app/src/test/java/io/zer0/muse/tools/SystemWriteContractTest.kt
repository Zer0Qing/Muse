package io.zer0.muse.tools

import android.content.Context
import android.media.AudioManager
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 系统写操作的**结果合同**（对比清单 P1-01）。
 *
 * 原来的三种假成功：亮度忽略 `putInt` 的返回值、音量回的是"请求值"、手电只记内存。
 * 这里守住的规则只有一句：**没确认生效就不能说设置成功**。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "zh-rCN")
class SystemWriteContractTest {

    private lateinit var context: Context
    private lateinit var tools: SystemToolsImpl

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        tools = SystemToolsImpl(context)
    }

    private fun brightness(): Int = Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, -1)

    @Test
    fun `brightness write is verified by reading back and shows before and after`() = runBlocking {
        Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 100)

        val text = tools.execSetBrightness(mapOf("value" to "200"))

        assertEquals("写入必须真的落到系统设置里", 200, brightness())
        assertTrue("要说清已确认生效: $text", text.contains("已确认生效"))
        assertTrue("要给出原值与现值: $text", text.contains("100") && text.contains("200"))
    }

    @Test
    fun `auto brightness confirms the mode actually switched`() = runBlocking {
        val text = tools.execSetBrightness(mapOf("auto" to "true"))

        val mode = Settings.System.getInt(
            context.contentResolver,
            Settings.System.SCREEN_BRIGHTNESS_MODE,
            -1,
        )
        assertEquals(Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC, mode)
        assertTrue("要说明已确认: $text", text.contains("已确认生效"))
    }

    @Test
    fun `brightness without value is a parameter error not a success`() = runBlocking {
        val text = tools.execSetBrightness(emptyMap())

        assertTrue("缺参数要明确报错: $text", text.contains("缺少参数"))
        assertTrue("不能出现成功措辞: $text", !text.contains("已确认生效"))
    }

    @Test
    fun `volume reports the read back value rather than the requested index`() = runBlocking {
        val text = tools.execSetVolume(mapOf("stream" to "music", "value" to "5"))

        val am = context.getSystemService(AudioManager::class.java)
        val actual = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        // 关键：说"已确认生效"就必须真的等于目标值；否则必须说未生效
        if (text.contains("已确认生效")) {
            assertEquals("声称生效时读回值必须等于目标", 5, actual)
        } else {
            assertTrue("不能既不确认也不说明: $text", text.contains("未生效"))
        }
    }

    @Test
    fun `volume percent is converted then verified`() = runBlocking {
        val text = tools.execSetVolume(mapOf("stream" to "music", "value" to "50", "percent" to "true"))

        val am = context.getSystemService(AudioManager::class.java)
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (text.contains("已确认生效")) {
            assertEquals("50% 应换算成 max 的一半并真的生效", max / 2, am.getStreamVolume(AudioManager.STREAM_MUSIC))
        } else {
            assertTrue(text.contains("未生效"))
        }
    }

    @Test
    fun `volume without value is a parameter error`() = runBlocking {
        val text = tools.execSetVolume(mapOf("stream" to "music"))

        assertTrue(text.contains("缺少参数"))
    }

    @Test
    fun `flashlight never claims a state it could not observe`() = runBlocking {
        // Robolectric 没有相机（返回"无可用相机"），这本身就是"没观察到"的一种；
        // 关键是任何分支都不能凭空说"已确认生效"。
        val status = tools.execToggleFlashlight(mapOf("action" to "status"))

        assertTrue(
            "状态要么来自系统，要么明确不可知: $status",
            status.contains("未知") || status.contains("无可用相机") || status.contains("不支持") ||
                status.contains("是") || status.contains("否"),
        )
        if (status.contains("无可用相机") || status.contains("不支持")) {
            assertTrue("不能在没有相机时声称状态: $status", !status.contains("已确认生效"))
        }
    }

    @Test
    fun `flashlight on does not claim confirmation without a system report`() = runBlocking {
        val text = tools.execToggleFlashlight(mapOf("action" to "on"))

        assertTrue(
            "要么确认生效，要么明确未确认（或环境不可用）: $text",
            text.contains("已确认生效") || text.contains("未确认生效") ||
                text.contains("无可用相机") || text.contains("不支持"),
        )
    }

    @Test
    fun `torch suffix only says verified when the system state matches`() {
        // 这条是纯规则：没有相机也能验，避免"只能靠人读代码相信"
        assertEquals(
            io.zer0.muse.R.string.tool_write_verified,
            tools.torchResultSuffixRes(desired = true, actual = true),
        )
        assertEquals(
            io.zer0.muse.R.string.tool_write_unverified,
            tools.torchResultSuffixRes(desired = true, actual = false),
        )
        assertEquals(
            "系统没回报时不能说已确认",
            io.zer0.muse.R.string.tool_write_unverified,
            tools.torchResultSuffixRes(desired = true, actual = null),
        )
        assertEquals(
            io.zer0.muse.R.string.tool_write_verified,
            tools.torchResultSuffixRes(desired = false, actual = false),
        )
    }
}
