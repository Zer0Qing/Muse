package io.zer0.muse.util

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v2.7 回归：[LocaleContextWrapper] 必须让非 Activity context 启动 Activity 不崩。
 *
 * ## 复现的崩溃
 *
 * RuntimeLocaleProvider 用 `applicationContext.createConfigurationContext()` 覆盖
 * Compose 的 LocalContext（为语言热切换）。该 context 非 Activity；Compose 文本选择
 * 菜单的「处理文字」项（ProcessText）从它 `startActivity` 且不带
 * FLAG_ACTIVITY_NEW_TASK → AndroidRuntimeException 崩溃（用户实测）。
 *
 * ## 锁定的行为
 *
 * 包装后 `startActivity` 会自动补 FLAG_ACTIVITY_NEW_TASK（非 Activity 场景）；
 * Activity 场景保持原样；幂等包装不重复嵌套；resources 仍透传 base。
 *
 * 注：不用 `Robolectric.buildActivity()`——它会走 Activity 完整生命周期，
 * 在 Windows 上触发 Robolectric 原生 SQLite 库崩溃（与本次逻辑无关）。
 * 这里用「记录式 base」直接观察 wrapper 实际下发的 Intent。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LocaleContextWrapperTest {

    private val appContext: Context get() = ApplicationProvider.getApplicationContext()

    /** 记录型 base context：捕获 wrapper 实际下发的 Intent。 */
    private class RecordingContext(base: Context) : ContextWrapper(base) {
        var captured: Intent? = null

        override fun startActivity(intent: Intent) {
            captured = intent
        }

        override fun startActivity(intent: Intent, options: Bundle?) {
            captured = intent
        }
    }

    /**
     * 记录型 Activity base：类型上是 Activity，但捕获而非真正启动。
     *
     * 用 mockk 而非 `class X : Activity()`——后者的构造会触发 Activity 初始化，
     * 在 Robolectric/Windows 上会撞到原生 SQLite 库崩溃（与本次逻辑无关）。
     */
    private fun recordingActivity(): Activity {
        val captured = arrayOfNulls<Intent>(1)
        val activity = mockk<Activity>(relaxed = true)
        every { activity.startActivity(any()) } answers {
            captured[0] = firstArg()
        }
        every { activity.startActivity(any(), any()) } answers {
            captured[0] = firstArg()
        }
        lastCaptured = captured
        return activity
    }

    private var lastCaptured: Array<Intent?>? = null

    @Test
    fun `非 Activity context 启动 Activity 时补 NEW_TASK`() {
        val base = RecordingContext(appContext)
        val wrapped = LocaleContextWrapper.wrap(base)

        wrapped.startActivity(Intent(Intent.ACTION_VIEW))

        val flags = base.captured?.flags ?: 0
        assertTrue(
            "非 Activity context 启动必须带 FLAG_ACTIVITY_NEW_TASK",
            flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0,
        )
    }

    @Test
    fun `已带 NEW_TASK 的 intent 不被改写`() {
        val base = RecordingContext(appContext)
        val wrapped = LocaleContextWrapper.wrap(base)

        wrapped.startActivity(Intent(Intent.ACTION_VIEW).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

        assertEquals(
            "已带 flag 的 intent 应原样传递",
            Intent.FLAG_ACTIVITY_NEW_TASK,
            (base.captured?.flags ?: 0) and Intent.FLAG_ACTIVITY_NEW_TASK,
        )
    }

    @Test
    fun `Activity 场景不额外补 flag（保持既有跳转语义）`() {
        val activity = recordingActivity()
        val wrapped = LocaleContextWrapper.wrap(activity)

        wrapped.startActivity(Intent(Intent.ACTION_VIEW))

        assertEquals(
            "Activity context 不应被补 NEW_TASK",
            0,
            (lastCaptured?.get(0)?.flags ?: 0) and Intent.FLAG_ACTIVITY_NEW_TASK,
        )
    }

    @Test
    fun `重复包装是幂等的`() {
        val once = LocaleContextWrapper.wrap(appContext)
        val twice = LocaleContextWrapper.wrap(once)
        assertTrue("重复 wrap 应返回同一实例", once === twice)
    }

    @Test
    fun `resources 仍来自被包装的 locale context`() {
        // 语言能力不受包装影响：getResources 应透传到 base
        val wrapped = LocaleContextWrapper.wrap(appContext)
        assertEquals(
            "resources 应透传 base 的配置",
            appContext.resources.configuration.locales,
            wrapped.resources.configuration.locales,
        )
        assertNotEquals("包装后仍是 ContextWrapper 实例", appContext, wrapped)
    }
}
