package io.zer0.muse.ui.assist

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import io.zer0.common.Logger

/**
 * 系统语音交互入口（对比清单 P1-04）。
 *
 * 这是 Android 的**标准**数字助理接入点：用户在系统里把 Muse 设为默认助理后，
 * 长按 Home / 电源键就会走到这里，并把当前界面（Assist 结构 + AssistContent）交给我们。
 *
 * 刻意不做的两件事：
 *  1. **不自动发送**：拿到的上下文只投进 [AssistInbox]，由界面填进输入框，
 *     用户看一眼再决定发不发 —— 助手替用户按下发送键等于替用户说话；
 *  2. **不谎报截图**：Assist 没有稳定的截图通道（各 ROM 差异大），
 *     [AssistSnapshot.hasScreenshot] 恒为 false，提示里也不会出现"我已看到屏幕"这类话。
 */
class MuseVoiceInteractionService : VoiceInteractionService()

/** 会话工厂：系统每次唤起助理都会要一个新会话。 */
class MuseVoiceInteractionSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = MuseVoiceInteractionSession(this)
}

class MuseVoiceInteractionSession(context: Context) : VoiceInteractionSession(context) {

    override fun onHandleAssist(state: AssistState) {
        val snapshot = runCatching {
            // activityComponent 来自 AssistStructure：比 AssistState.activityId 更稳定可用
            val component = state.assistStructure?.activityComponent
            AssistSnapshot(
                packageName = component?.packageName,
                activityName = component?.className,
                visibleText = collectStructureText(state.assistStructure),
                contentText = state.assistContent?.let { content ->
                    content.webUri?.toString()?.takeIf { it.isNotBlank() }
                        ?: content.intent?.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
                },
            )
        }.onFailure { Logger.w(TAG, "读取 Assist 上下文失败: ${it.message}") }
            .getOrDefault(AssistSnapshot())

        val prompt = AssistHandoff.buildPrompt(snapshot)
        AssistInbox.publish(prompt)
        openApp()
        hide()
    }

    /** 把应用带到前台，让用户看到刚填好的输入框。 */
    private fun openApp() {
        runCatching {
            val intent = Intent(context, Class.forName(MAIN_ACTIVITY)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            context.startActivity(intent)
        }.onFailure { Logger.w(TAG, "唤起主界面失败: ${it.message}") }
    }

    /**
     * 从 AssistStructure 里按顺序收集可见文字。
     *
     * 有节点上限：某些列表页的节点数能到几千，全遍历会拖慢助理唤起。
     */
    private fun collectStructureText(structure: android.app.assist.AssistStructure?): String {
        if (structure == null) return ""
        val builder = StringBuilder()
        var visited = 0
        for (index in 0 until structure.windowNodeCount) {
            visited = walk(structure.getWindowNodeAt(index).rootViewNode, builder, visited)
        }
        return builder.toString().trim()
    }

    private fun walk(node: android.app.assist.AssistStructure.ViewNode?, builder: StringBuilder, visited: Int): Int {
        if (node == null || visited >= MAX_NODES) return visited
        var count = visited + 1
        node.text?.toString()?.takeIf { it.isNotBlank() }?.let { builder.appendLine(it) }
        node.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { builder.appendLine(it) }
        for (index in 0 until node.childCount) {
            count = walk(node.getChildAt(index), builder, count)
            if (count >= MAX_NODES) break
        }
        return count
    }

    private companion object {
        const val TAG = "MuseAssist"
        const val MAX_NODES = 2_000
        const val MAIN_ACTIVITY = "io.zer0.muse.MainActivity"
    }
}
