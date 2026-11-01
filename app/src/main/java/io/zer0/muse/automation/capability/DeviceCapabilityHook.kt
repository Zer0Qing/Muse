// 能力探测是"尽力而为"语义：任何异常都必须降级为"该项不可用"，而不是让整段 system prompt
// 注入失败（那样模型会完全失去设备认知）。detekt 的通用捕获告警在此为刻意选择，原因随栈入日志。
@file:Suppress("TooGenericExceptionCaught")

package io.zer0.muse.automation.capability

import io.zer0.common.Logger
import io.zer0.muse.automation.core.AutomationManager
import io.zer0.muse.hook.PromptContext
import io.zer0.muse.hook.SystemPromptComposeHook
import kotlinx.coroutines.CancellationException

/**
 * 设备能力档案注入 Hook。
 *
 * 在系统提示组装完成后，把 [DeviceCapabilityProfile]（当前真实可用通道 + 能力边界）
 * 与操作决策规程追加到 system prompt 末尾。
 *
 * ## 设计要点
 *
 * - **探测优先、缓存兜底**：每次组装提示词时尝试刷新一次通道状态（`refreshPermissions()`
 *   本身带 Mutex 且各通道探测失败会降级为不可用，不会击穿对话主链路）。刷新失败时退回
 *   [AutomationManager.permissionState] 的最近一次结果，绝不因为探测失败而丢失整段档案。
 * - **任何探测都是尽力而为**：单个能力探测抛异常只把该项记为不可用，不影响其余项，
 *   也不向上抛（见 [probe]）。取消异常照常抛出，不吞协程取消。
 * - **子代理不注入**：subagent 是隔离子会话，不需要重复背负整份设备规程（省 token，也避免
 *   子代理误以为可以随意操作设备）。
 */
class DeviceCapabilityHook(
    private val automationManager: AutomationManager,
    /**
     * 虚拟屏可用性探测。默认不可用；由装配方注入真实探测（避免 capability 包反向依赖
     * vdisplay 实现）。为 null 时档案里记为不可用。
     */
    private val virtualDisplayProbe: suspend () -> Boolean = { false },
    /**
     * 自动化总闸探测（设置里的「允许屏幕自动化」）。
     *
     * 为 null 时按开处理：这个档案的价值在于**提前告诉模型别白试**，
     * 所以闸关时必须让它在第一句就看见，而不是等工具调用失败才发现。
     */
    private val automationEnabledProbe: (suspend () -> Boolean)? = null,

    /** 内置 Node 运行时可用性探测（[io.zer0.muse.runtime.MuseRuntime.isReady]）。 */
    private val nodeRuntimeProbe: (() -> Boolean)? = null,
) : SystemPromptComposeHook {

    override val id: String = "device_capability_profile"

    /**
     * priority=80：高于 WorldBookHook(60)，让设备档案尽量靠近系统提示开头，
     * 且先于楼层截断类 Hook 完成注入。
     */
    override val priority: Int = 80

    override suspend fun afterComposeSystemPrompt(context: PromptContext): String {
        if (context.forSubagent) return ""
        return try {
            val profile = detectProfile()
            buildString {
                append(profile.renderForModel())
                appendLine()
                appendLine()
                append(profile.renderOperatingGuide())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(TAG, "能力档案注入失败: ${e.message}", e)
            ""
        }
    }

    /**
     * 探测当前能力档案：先刷新三层通道状态，再叠加虚拟屏/Node 两项独立能力。
     *
     * 刷新失败时使用缓存状态（`permissionState.value`），保证档案始终有一份可用快照。
     */
    private suspend fun detectProfile(): DeviceCapabilityProfile {
        val state = try {
            automationManager.refreshPermissions()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(TAG, "权限刷新失败，使用缓存状态: ${e.message}")
            automationManager.permissionState.value
        }

        return DeviceCapabilityProfile.fromFlags(
            accessibility = state.accessibilityEnabled,
            shizuku = state.shellEnabled,
            root = state.rootEnabled,
            virtualDisplay = probe("virtualDisplay", false) { virtualDisplayProbe() },
            nodeRuntime = probe("nodeRuntime", false) { nodeRuntimeProbe?.invoke() ?: false },
            automationEnabled = probe("automationEnabled", true) {
                automationEnabledProbe?.invoke() ?: true
            },
            details = mapOf(
                "shizukuState" to state.shizukuState.name,
                "shizukuMessage" to state.shizukuMessage,
            ),
        )
    }

    /**
     * 尽力探测：异常只降级为 [fallback]，不中断整份档案的组装。
     *
     * 吞异常是**刻意的**——能力探测失败的正确表现是"这一项记为不可用"，而不是让整段
     * system prompt 注入失败（那样模型会完全失去设备认知）。原因已带栈写入日志。
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private suspend fun probe(name: String, fallback: Boolean, block: suspend () -> Boolean): Boolean = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Logger.w(TAG, "$name 探测失败，记为不可用: ${e.message}", e)
        fallback
    }

    private companion object {
        const val TAG = "DeviceCapabilityHook"
    }
}
