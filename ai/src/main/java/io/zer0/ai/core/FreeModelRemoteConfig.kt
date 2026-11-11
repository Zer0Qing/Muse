package io.zer0.ai.core

import kotlinx.serialization.Serializable

/**
 * 免费模型远端派发配置。
 *
 * 免费档的接口地址、密钥与模型名由服务端**不定期轮换**，客户端不应把任何一份
 * 写死为唯一真源。每次请求前向派发端点取当前有效配置；取不到时回退到内置默认值，
 * 保证离线/服务故障时免费档仍可用（而不是直接 401）。
 *
 * 设计约束：
 *  - 本对象只承载**公开可下发**的字段（baseUrl / model / key / 展示名），不放用户凭据；
 *  - 密钥由服务端下发，客户端只在内存与本地缓存中保存，不写日志、不进诊断；
 *  - 版本号 [version] 单调递增，客户端据此判断缓存是否过期（避免每次请求都覆盖写盘）。
 */
@Serializable
data class FreeModelRemoteConfig(
    /** 配置版本，服务端递增；用于缓存比较与回滚识别。 */
    val version: Int = 0,
    /** OpenAI 兼容端点，如 https://example.com/v1。 */
    val baseUrl: String,
    /** 当前轮换到的模型 id。 */
    val model: String,
    /** 服务端下发的调用密钥。 */
    val apiKey: String,
    /** 模型展示名；空串时由客户端按 [model] 生成。 */
    val displayName: String = "",
    /** 上下文窗口；<=0 时沿用内置默认。 */
    val contextWindow: Int = 0,
    /** 单次最大输出；<=0 时沿用内置默认。 */
    val maxOutputTokens: Int = 0,
    /** 是否接受图片输入。 */
    val supportsVision: Boolean = false,
    /** 是否支持工具调用。 */
    val supportsTools: Boolean = false,
    /** 是否返回 reasoning。 */
    val supportsReasoning: Boolean = false,
    /** 服务端给出的轮换说明（可选，仅用于诊断，不直接展示给用户）。 */
    val rotationNote: String = "",
) {
    /** 字段自洽性：缺少端点/模型/密钥时不可用于真实请求。 */
    val isUsable: Boolean
        get() = baseUrl.isNotBlank() && model.isNotBlank() && apiKey.isNotBlank()

    /** 展示名：优先服务端给的，否则用模型 id。 */
    val effectiveDisplayName: String
        get() = displayName.ifBlank { model }
}

/**
 * 派发端点返回的外层信封。
 *
 * 允许服务端用 `{ "data": {...} }` 包裹，也允许直接返回 [FreeModelRemoteConfig] 本体，
 * 由解析层兼容两种形态（服务端演进时客户端不至于整批失效）。
 */
@Serializable
data class FreeModelRemoteEnvelope(
    val data: FreeModelRemoteConfig? = null,
)
