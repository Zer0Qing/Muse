package io.zer0.muse.ui.chat

import io.zer0.ai.core.Model
import io.zer0.ai.core.ProviderConfig

/**
 * 请求模型绑定的三态判定结果。
 *
 * 背景:原判定 `provider.models.none { it.id == modelId }` 在 `models` 为空列表时
 * **恒为 true**(空集上的 none{} 返回 true),于是把「模型列表还没拉过 / 拉取失败」
 * 误判成「模型真的不存在」并触发降级 —— 会话被换到能力更差的兜底模型
 * (真机表现:GLM-4-9B 工具调用畸形,卡片生成直接失败)。
 *
 * 三种状态必须分开,口径只此一处:
 *  - [providerMissing]:Provider 配置真的没了 → 降级 + W 级告警(原有行为)
 *  - [modelMissing]:Provider 存在、模型列表**非空**、但确实没有这个 id → 降级 + W 级告警
 *  - [listNotReady]:Provider 存在、模型列表**为空**(从未拉取 / 拉取失败)→ 不降级、不告警,
 *    保留用户请求的绑定交给上游判定;只留一条可排查的 info 日志
 *
 * 空列表 ≠ 不含。
 */
internal data class RequestedModelBinding(
    /** Provider 配置不存在。 */
    val providerMissing: Boolean,
    /** Provider 存在且模型列表非空,但列表里确实没有请求的 id。 */
    val modelMissing: Boolean,
    /** Provider 存在但模型列表为空 —— 列表未就绪,不能据此判定缺失。 */
    val listNotReady: Boolean,
    /** 可用绑定:精确命中,或列表未就绪时按请求 id 构造的占位绑定;真缺失时为 null。 */
    val model: Model?,
) {
    /** 只有「真缺失」才需要降级并打 W 级告警。 */
    val missing: Boolean get() = providerMissing || modelMissing
}

/**
 * 解析「请求的 provider + model」这一对绑定。
 *
 * 单一出口表达式写法:三态是互斥枚举而非四个独立布尔,用 when 一次穷举,
 * 避免多 return 分支各自拼装 data class 时漏字段(也让判定可一眼读完)。
 *
 * @param providers 当前全部 Provider 配置快照
 * @param providerId 请求的 Provider id(null 表示调用方未指定,不做判定)
 * @param modelId 请求的模型 id(null 表示调用方未指定,不做判定)
 */
internal fun resolveRequestedModelBinding(providers: List<ProviderConfig>, providerId: String?, modelId: String?): RequestedModelBinding {
    val provider = providerId?.let { pid -> providers.firstOrNull { it.id == pid } }
    return when {
        // 调用方没指定 provider:不做任何判定。
        providerId == null -> NO_BINDING
        // Provider 缺失优先于 modelId 为空 —— 与原有行为一致(配置没了就得告警)。
        provider == null -> MISSING_PROVIDER
        modelId == null -> NO_BINDING
        // 精确命中 → 用列表里的真实 Model(带完整能力声明)
        else -> {
            val exact = provider.models.firstOrNull { it.id == modelId }
            when {
                exact != null ->
                    RequestedModelBinding(
                        providerMissing = false,
                        modelMissing = false,
                        listNotReady = false,
                        model = exact,
                    )
                // 列表为空 → 未就绪。保留请求绑定(能力由 ModelRegistry 按 id 推导),不降级。
                provider.models.isEmpty() ->
                    RequestedModelBinding(
                        providerMissing = false,
                        modelMissing = false,
                        listNotReady = true,
                        model = Model(id = modelId, name = modelId, providerId = provider.id),
                    )
                // 真缺失:列表非空且不含该 id → 调用方按原兜底路径处理。
                else -> MISSING_MODEL
            }
        }
    }
}

private val NO_BINDING = RequestedModelBinding(
    providerMissing = false,
    modelMissing = false,
    listNotReady = false,
    model = null,
)

private val MISSING_PROVIDER = RequestedModelBinding(
    providerMissing = true,
    modelMissing = false,
    listNotReady = false,
    model = null,
)

private val MISSING_MODEL = RequestedModelBinding(
    providerMissing = false,
    modelMissing = true,
    listNotReady = false,
    model = null,
)
