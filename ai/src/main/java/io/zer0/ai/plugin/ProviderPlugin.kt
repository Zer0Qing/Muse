package io.zer0.ai.plugin

import kotlinx.serialization.Serializable

/**
 * P2-10: Provider 插件模型声明。
 *
 * 每个插件通过 JSON 文件描述一个 OpenAI 兼容协议的供应商,
 * 用户无需写代码即可接入自定义中转 / 自托管模型服务。
 *
 * @param id 插件唯一标识(同时作为生成 ProviderConfig 的 id 前缀)
 * @param displayName 展示名称
 * @param description 简介
 * @param baseUrl 上游 API 基础 URL(如 https://relay.example.com/v1)
 * @param apiKeyPattern API Key 校验正则(留空表示不校验)
 * @param apiKeyHint API Key 输入框提示文案
 * @param models 该插件声明的模型清单
 * @param headers 自定义请求头(注入到上游 HTTP 请求)
 * @param requestTemplate 请求体 JSON 模板,占位符 {{prompt}} {{model}} 等由调用方替换;
 *   留空时走默认 OpenAI Chat Completions 模板
 * @param responsePath 非流式响应 JSON 提取路径(默认 $.choices[0].message.content)
 * @param streamResponsePath 流式响应 JSON 提取路径(默认 $.choices[0].delta.content)
 */
@Serializable
data class ProviderPlugin(
    val id: String,
    val displayName: String,
    val description: String = "",
    val baseUrl: String,
    val apiKeyPattern: String = "",
    val apiKeyHint: String = "",
    val models: List<PluginModel> = emptyList(),
    val headers: Map<String, String> = emptyMap(),
    val requestTemplate: String = "",
    val responsePath: String = "$.choices[0].message.content",
    val streamResponsePath: String = "$.choices[0].delta.content",
)

/**
 * P2-10: 插件模型声明。
 *
 * 字段含义对齐 [io.zer0.ai.core.Model](只取 UI 关心的子集),
 * 由 [ProviderPluginRegistry.toProviderConfig] 转换为完整 [io.zer0.ai.core.Model]。
 *
 * @param id 模型 id(传给上游 API 的 model 字段)
 * @param displayName 展示名称(默认同 id)
 * @param contextWindow 上下文窗口大小(token 数,默认 4096)
 * @param supportsVision 是否支持视觉输入
 * @param supportsTools 是否支持工具调用(function calling)
 */
@Serializable
data class PluginModel(
    val id: String,
    val displayName: String = id,
    val contextWindow: Int = 4096,
    val supportsVision: Boolean = false,
    val supportsTools: Boolean = false,
)
