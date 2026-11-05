package io.zer0.muse.data.routing

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 辅助模型最近一次调用失败。
 *
 * 后台任务（记忆抽取、滚动摘要、插件模型调用）用的是「任务路由」里单独绑定的模型，
 * 与聊天模型是两条线。它失败时聊天照常，所以界面上看不到任何异常 ——
 * 用户会以为「换遍模型都没用」。
 *
 * 这里**只记账，不改行为**：不自动回退、不换模型重试。后台调用频繁，
 * 悄悄改用用户可能很贵的付费模型，等于替他花钱。
 */
object UtilityModelHealth {

    /** 一次失败。 */
    data class Failure(
        val modelId: String,
        val message: String,
        val at: Long,
    )

    private const val KEEP_MS = 24 * 60 * 60 * 1000L

    private val _failures = MutableStateFlow<Map<String, Failure>>(emptyMap())

    /** 当前各绑定最近一次的失败记录，key 见 [key]。 */
    val failures: StateFlow<Map<String, Failure>> = _failures.asStateFlow()

    /** 记一次失败。同一绑定只保留最近一条。 */
    fun record(providerId: String?, modelId: String, message: String, at: Long = System.currentTimeMillis()) {
        if (modelId.isBlank()) return
        _failures.value = _failures.value.toMutableMap().apply {
            put(key(providerId, modelId), Failure(modelId, message.take(200), at))
        }
    }

    /** 取某个绑定最近一次的失败；超过一天的旧记录直接丢掉，免得一直挂着吓人。 */
    fun lastFor(providerId: String?, modelId: String?, now: Long = System.currentTimeMillis()): Failure? {
        if (modelId.isNullOrBlank()) return null
        val hit = _failures.value[key(providerId, modelId)] ?: return null
        return hit.takeIf { now - it.at <= KEEP_MS }
    }

    /** 清空（更换绑定后调用，旧失败不该继续挂在新模型上）。 */
    fun clear(providerId: String?, modelId: String?) {
        if (modelId.isNullOrBlank()) return
        _failures.value = _failures.value.toMutableMap().apply { remove(key(providerId, modelId)) }
    }

    private fun key(providerId: String?, modelId: String): String = "${providerId.orEmpty()}|$modelId"
}
