package io.zer0.muse.ui.speech

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import java.util.Locale

/**
 * Phase 5-D: 语音输入 helper(system Intent fallback)。
 *
 * v1.49: Vosk 离线识别已移除(减小 APK 体积),本文件升级为无 API Key 时的
 * 主 fallback 路径。ChatScreen 在 SYSTEM 模式下长按麦克风松手后调用
 * [createIntent] + [parseResult] 启动系统语音识别。
 *
 * 设计选择: 用 ACTION_RECOGNIZE_SPEECH Intent 调用系统语音识别器,
 * 无需第三方 SDK,无需自己管 SpeechRecognizer 回调生命周期,
 * Compose 侧用 rememberLauncherForActivityResult 启动即可。
 *
 * 局限:
 *  - 依赖系统/Google 应用的语音识别服务(国产 ROM 可能缺失)
 *  - 在线识别(网络依赖)
 *  - 一次识别一段(不适合连续听写)
 *
 * 返回值: 识别到的文本(取第一条候选),null 表示无结果或被取消。
 */
object SpeechInput {

    /** 构造语音识别 Intent。prompt 为识别对话框提示文案,由调用方传入本地化字符串。 */
    fun createIntent(prompt: String = ""): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        // L-SP4 修复: 原先硬编码 "zh-CN",现跟随系统语言(Locale.getDefault().toLanguageTag 返回 BCP-47 标签如 zh-CN/en-US)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
        if (prompt.isNotEmpty()) {
            putExtra(RecognizerIntent.EXTRA_PROMPT, prompt)
        }
    }

    /**
     * 检查设备是否有 Activity 能处理语音识别 Intent。
     *
     * 国产 ROM 或未安装 Google 应用的设备可能没有可用的语音识别器,
     * 直接 launch 会抛 ActivityNotFoundException。启动前应调用本方法判断,
     * 不可用时给用户 Toast 提示,避免崩溃。
     */
    fun isAvailable(context: Context): Boolean =
        createIntent().resolveActivity(context.packageManager) != null

    /** 从 Activity result 取出识别文本。 */
    fun parseResult(resultCode: Int, data: Bundle?): String? {
        if (resultCode != android.app.Activity.RESULT_OK) return null
        val list = data?.getStringArrayList(RecognizerIntent.EXTRA_RESULTS) ?: return null
        return list.firstOrNull()?.takeIf { it.isNotBlank() }
    }
}
