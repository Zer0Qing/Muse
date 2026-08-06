package io.zer0.muse.tools.script

import io.zer0.common.Logger
import io.zer0.muse.tools.JsSandbox
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 基于 WebView V8 的 [SkillEngine] 实现 (P3-1)。
 *
 * 封装 [JsSandbox] 单例，添加互斥锁保证引擎实例不可重入。
 *
 * 注意：JsSandbox 内部持有一个全局 WebView 单例，所有 WebViewSkillEngine 实例
 * 共享同一个底层 V8 引擎。互斥锁 [executionMutex] 确保同一时刻只有一个调用方
 * 使用引擎，避免 evaluateJavascript 回调串扰。
 *
 * 若需真正并行执行，应使用 [SkillEnginePool] 管理多个 JsSandbox 实例
 * （当前受单例 WebView 限制，池内多个 engine 仍串行；未来接入 QuickJS 后可并行）。
 */
class WebViewSkillEngine : SkillEngine {

    private val executionMutex = Mutex()

    override suspend fun eval(script: String, timeoutMs: Long): SkillEngineResult {
        if (JsSandbox.isCircuitBroken) {
            return SkillEngineResult.Error(message = "JS 沙盒已熔断，请稍后重试")
        }
        return executionMutex.withLock {
            val result = JsSandbox.execute(script, timeoutMs)
            result.fold(
                onSuccess = { jsResult ->
                    if (jsResult.error != null) {
                        SkillEngineResult.Error(
                            message = jsResult.error,
                            consoleLogs = jsResult.consoleLogs,
                        )
                    } else {
                        SkillEngineResult.Success(
                            valueJson = jsResult.value?.toString() ?: "null",
                            consoleLogs = jsResult.consoleLogs,
                        )
                    }
                },
                onFailure = { e ->
                    Logger.w(TAG, "SkillEngine eval 失败: ${e.message}")
                    SkillEngineResult.Error(
                        message = e.message ?: "引擎执行异常",
                    )
                },
            )
        }
    }

    override fun interrupt() {
        // WebView V8 无法真正中断 JS 执行
        // 超时后 Kotlin 侧返回错误，JS 仍会跑完（但结果被丢弃）
        Logger.d(TAG, "interrupt() 被调用（WebView V8 无法真正中断，等待超时）")
    }

    companion object {
        private const val TAG = "WebViewSkillEngine"
    }
}
