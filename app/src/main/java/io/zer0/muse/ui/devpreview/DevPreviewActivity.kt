package io.zer0.muse.ui.devpreview

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import io.zer0.muse.ui.theme.MuseTheme

/**
 * 开发预览用的独立 Activity。
 *
 * 存在的唯一目的：在没有配置供应商、也没有真实会话的情况下，
 * 也能看到消息气泡的实际排版（MOOD 分节、思考卡摘要行等）。
 *
 * 独立成 Activity 而不是挂在主入口里，是为了让 [io.zer0.muse.MainActivity]
 * 的启动流程完全不受影响 —— 预览代码不进主链路，也不会增加主入口的分支。
 *
 * 用法：
 * ```
 * adb shell am start -n io.zer0.muse/.ui.devpreview.DevPreviewActivity
 * ```
 *
 * 未在桌面注册，正常使用不会看到入口。
 */
class DevPreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MuseTheme(darkTheme = isSystemInDarkTheme()) {
                DevPreviewScreen()
            }
        }
    }
}
