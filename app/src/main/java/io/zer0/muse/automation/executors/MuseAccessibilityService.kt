package io.zer0.muse.automation.executors

import android.view.accessibility.AccessibilityEvent
import io.zer0.common.Logger
import io.zer0.muse.data.agentbot.AgentTeachRecorder
import io.zer0.muse.data.agentbot.TeachEventSanitizer
import org.koin.core.context.GlobalContext

/**
 * Muse 无障碍服务的主 App 组件。
 *
 * 组件名保持在 [io.zer0.muse.automation.executors.MuseAccessibilityService],
 * 这样已经在系统设置中开启过旧版本服务的用户可以继续沿用同一个授权项。
 * 具体读屏、手势和输入实现统一继承自 :accessibility 模块,避免主 App 与库模块
 * 各注册一份服务导致“系统已开启但应用检查的是另一份实例”。
 */
class MuseAccessibilityService : io.zer0.muse.accessibility.MuseAccessibilityService() {

    /**
     * 示范录制器（Koin 单例）。
     *
     * 服务与 ViewModel 必须拿到同一个实例，否则界面上的录制状态永远不变。
     */
    @Volatile
    private var teachRecorder: AgentTeachRecorder? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        teachRecorder = runCatching { GlobalContext.getOrNull()?.get<AgentTeachRecorder>() }
            .onFailure { error ->
                Logger.w(TAG, "示范录制器获取失败，Teach Mode 事件捕获关闭: ${error.message}", error)
            }
            .getOrNull()
    }

    /**
     * 示范录制只在 recording == true 时工作；空闲路径只做一次引用与状态读取，不创建事件对象。
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        super.onAccessibilityEvent(event)
        if (event == null) return
        val recorder = teachRecorder ?: return
        val state = recorder.state.value
        if (!state.recording) return
        val decision = TeachEventSanitizer.decide(
            event = event.toTeachRawEvent(),
            recordedSteps = state.steps.size,
            startedAt = state.startedAt,
            now = System.currentTimeMillis(),
        )
        when (decision) {
            is TeachEventSanitizer.Decision.Record -> recorder.recordStep(decision.action, decision.targetApp)
            is TeachEventSanitizer.Decision.Blocked -> Unit
            TeachEventSanitizer.Decision.Ignored -> Unit
        }
    }

    private fun AccessibilityEvent.toTeachRawEvent(): TeachEventSanitizer.RawEvent {
        val sourceNode = source
        return try {
            TeachEventSanitizer.RawEvent(
                eventType = eventType,
                packageName = packageName?.toString().orEmpty(),
                className = className?.toString().orEmpty(),
                viewId = sourceNode?.viewIdResourceName.orEmpty(),
                text = text?.joinToString(separator = "") { chunk -> chunk?.toString().orEmpty() },
                contentDescription = contentDescription?.toString(),
                isPassword = isPassword || sourceNode?.isPassword == true,
                timestamp = System.currentTimeMillis(),
            )
        } finally {
            sourceNode?.recycle()
        }
    }

    companion object {
        private const val TAG = "MuseA11yService"

        /** 当前主 App 无障碍服务实例;基类实例由系统生命周期负责维护。 */
        val instance: MuseAccessibilityService?
            get() = io.zer0.muse.accessibility.MuseAccessibilityService.instance as? MuseAccessibilityService

        /** 服务是否已经完成系统连接。 */
        fun isConnected(): Boolean = instance != null
    }
}
