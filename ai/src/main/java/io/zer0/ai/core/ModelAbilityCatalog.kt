package io.zer0.ai.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 模型能力库(照搬既有实现 的 `model-catalog`)。
 *
 * 把"模型能做什么"从散落的硬编码收敛成 **一份数据** —— 现状是
 * `ProviderCompat` 的 host 白名单、`ModelRegistry` 的名称前缀规则、`KnownModels`
 * 词典三条独立链并存,同一模型可能得出不同结论。这里统一为单一真相。
 *
 * 字段命名对齐上游目录,便于互导;数据源可为内置 JSON,将来也可替换为远端目录
 * (留 [schemaVersion] / [publishedAt] 两个口子)。
 */
@Serializable
data class ModelCatalog(
    val schemaVersion: Int = 1,
    val publishedAt: String = "",
    /** providerId → (modelId → 能力条目)。 */
    val providers: Map<String, Map<String, ModelCatalogEntry>> = emptyMap(),
) {
    /** 查一个模型的能力条目;provider 或 model 不存在返回 null。 */
    fun entryOf(providerId: String, modelId: String): ModelCatalogEntry? =
        providers[providerId]?.get(modelId)

    /**
     * 按模型 id 全局查（跨 provider 第一个命中）。
     *
     * 用于 user 自建供应商场景 —— 它们的 providerId 是运行时生成的（provider-xxx），
     * 与目录里的逻辑供应商名对不上，此时以模型名（上游全局唯一）为准。
     * 惰性索引,不参与序列化,首次查询时构建。
     */
    fun entryByModelId(modelId: String): ModelCatalogEntry? = byModelIdIndex[modelId]

    private val byModelIdIndex: Map<String, ModelCatalogEntry> by lazy {
        buildMap {
            for (models in providers.values) {
                for ((id, entry) in models) putIfAbsent(id, entry)
            }
        }
    }

    /** 某 provider 下所有模型条目。 */
    fun modelsOf(providerId: String): Map<String, ModelCatalogEntry> =
        providers[providerId] ?: emptyMap()
}

/** 单个模型的能力画像。 */
@Serializable
data class ModelCatalogEntry(
    val name: String = "",
    val context: Long? = null,
    val maxOutput: Long? = null,
    val image: Boolean = false,
    val reasoning: Boolean = false,
    /** 工具调用规格 —— 含"方言",替代按 host / 名称前缀猜的那一套。 */
    val toolUse: ToolUseSpec? = null,
    /** 支持的推理档位(如 low/medium/high/xhigh/max);空表示不支持推理档位。 */
    val thinkingLevels: List<String> = emptyList(),
    val defaultThinkingLevel: String? = null,
    /** 兼容性画像(替代硬编码 host 白名单)。 */
    val compat: CompatProfile? = null,
)

/** 工具调用规格。 */
@Serializable
data class ToolUseSpec(
    val supportsTools: Boolean = false,
    /** 工具调用方言:openai / anthropic / gemini。 */
    val dialect: String = "openai",
    /** 工具结果回传格式:message / tool-result。 */
    val toolResultFormat: String = "message",
)

/** 兼容性画像。 */
@Serializable
data class CompatProfile(
    val thinkingFormat: String? = null,
    val reasoningProfile: String? = null,
)

/**
 * 模型能力库加载入口。
 *
 * 解析失败返回**空库**而不抛异常 —— 调用方读到空库时自然退回各自的兜底逻辑,
 * 避免一份坏数据把整条链路打挂(与上游"失败保留旧 snapshot"同一取向)。
 */
object ModelCatalogLoader {
    private val json = Json { ignoreUnknownKeys = true }

    fun loadOrEmpty(raw: String?): ModelCatalog {
        if (raw.isNullOrBlank()) return ModelCatalog()
        return runCatching { json.decodeFromString(ModelCatalog.serializer(), raw) }
            .getOrElse { ModelCatalog() }
    }
}
