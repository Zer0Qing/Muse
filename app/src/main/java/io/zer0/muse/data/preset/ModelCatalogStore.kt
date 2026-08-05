package io.zer0.muse.data.preset

import android.content.Context
import io.zer0.common.AppJson
import io.zer0.common.Logger
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.io.File

/**
 * P1 模型目录（本地可维护）。
 *
 * 目标：解决 PresetProviders.kt 硬编码模型清单过期、上下文/能力不准的问题。
 *
 * 三层数据：
 * 1. 内置默认：由 [builtinEntries] 提供（随版本发布，代码内默认）。
 * 2. 用户覆盖：`filesDir/model_catalog/user_models.json`，用户手动修正过的字段。
 * 3. 远端目录：预留接口，当前未接入（避免依赖不稳定网络）。
 *
 * 合并规则（用户优先）：
 * - 同一 providerId + modelId：用户覆盖字段优先，未覆盖字段回退内置。
 * - 用户新增模型：保留。
 * - 用户删除模型：删除标记生效（builtInRemoved）。
 *
 * 用户手动更改优先级由“字段级合并”保证，后续远端目录刷新也只会补字段，
 * 不会覆盖 [ModelCatalogEntry.userEdited] 标记的字段。
 */
class ModelCatalogStore(
    private val context: Context,
) {

    @Serializable
    data class ModelCatalogEntry(
        val providerId: String,
        val modelId: String,
        val displayName: String? = null,
        val contextWindow: Int? = null,
        val maxOutputTokens: Int? = null,
        val supportsVision: Boolean? = null,
        val supportsTools: Boolean? = null,
        val supportsReasoning: Boolean? = null,
        val updatedAt: Long = 0L,
        val userEdited: Boolean = false,
        val builtInRemoved: Boolean = false,
    )

    @Serializable
    private data class UserOverrides(
        val items: List<ModelCatalogEntry> = emptyList(),
        val updatedAt: Long = 0L,
    )

    private val catalogDir: File
        get() = File(context.filesDir, "model_catalog")

    private val userOverridesFile: File
        get() = File(catalogDir, "user_models.json")

    @Volatile
    private var userCache: UserOverrides? = null

    /**
     * 内置默认模型目录（随版本发布）。
     *
     * 只覆盖主流/常用模型，字段缺失时 UI 显示“未收录/未知”，
     * 不强行猜测，避免把错误上下文发给 Provider。
     */
    fun builtinEntries(): List<ModelCatalogEntry> = buildList {
        // OpenAI
        add(entry("openai", "gpt-4o", "GPT-4o", 128_000, 16_384, vision = true, tools = true))
        add(entry("openai", "gpt-4o-mini", "GPT-4o mini", 128_000, 16_384, vision = true, tools = true))
        add(entry("openai", "gpt-4.1", "GPT-4.1", 1_047_576, 32_768, vision = true, tools = true))
        add(entry("openai", "gpt-4.1-mini", "GPT-4.1 mini", 1_047_576, 32_768, vision = true, tools = true))
        add(entry("openai", "o3", "o3", 200_000, 100_000, tools = true, reasoning = true))
        add(entry("openai", "o4-mini", "o4-mini", 200_000, 100_000, vision = true, tools = true, reasoning = true))
        // Anthropic
        add(entry("anthropic", "claude-sonnet-4-5-20250514", "Claude Sonnet 4.5", 200_000, 64_000, vision = true, tools = true, reasoning = true))
        add(entry("anthropic", "claude-opus-4-1-20250805", "Claude Opus 4.1", 200_000, 32_000, vision = true, tools = true, reasoning = true))
        add(entry("anthropic", "claude-haiku-4-5-20251001", "Claude Haiku 4.5", 200_000, 64_000, vision = true, tools = true))
        // Gemini
        add(entry("gemini", "gemini-2.5-pro", "Gemini 2.5 Pro", 1_048_576, 65_536, vision = true, tools = true, reasoning = true))
        add(entry("gemini", "gemini-2.5-flash", "Gemini 2.5 Flash", 1_048_576, 65_536, vision = true, tools = true, reasoning = true))
        // DeepSeek
        add(entry("deepseek", "deepseek-chat", "DeepSeek Chat", 64_000, 8_192, tools = true))
        add(entry("deepseek", "deepseek-reasoner", "DeepSeek Reasoner", 64_000, 8_192, reasoning = true))
        // 通义
        add(entry("qwen", "qwen-plus", "Qwen Plus", 131_072, 8_192, tools = true))
        add(entry("qwen", "qwen-max", "Qwen Max", 32_000, 8_192, vision = true, tools = true))
        // 智谱
        add(entry("zhipu", "glm-4-plus", "GLM-4 Plus", 128_000, 8_192, tools = true))
        add(entry("zhipu", "glm-4-flash", "GLM-4 Flash", 128_000, 8_192, tools = true))
        // Kimi
        add(entry("moonshot", "kimi-k2", "Kimi K2", 128_000, 16_384, tools = true))
        add(entry("moonshot", "kimi-k2.7", "Kimi K2.7", 256_000, 16_384, tools = true, reasoning = true))
        // 豆包
        add(entry("doubao", "doubao-pro-32k", "Doubao Pro 32K", 32_000, 4_096, tools = true))
        // xAI
        add(entry("xai", "grok-4", "Grok 4", 256_000, 32_768, vision = true, tools = true))
        add(entry("xai", "grok-4-heavy", "Grok 4 Heavy", 256_000, 32_768, vision = true, tools = true, reasoning = true))
        // Ollama 本地常见模型
        add(entry("ollama", "llama3.1", "Llama 3.1", 128_000, null, tools = true))
        add(entry("ollama", "qwen2.5", "Qwen 2.5", 131_072, null, tools = true))
        // SiliconFlow 免费常用
        add(entry("siliconflow", "deepseek-ai/DeepSeek-V3", "DeepSeek V3", 64_000, 8_192, tools = true))
        add(entry("siliconflow", "Qwen/Qwen2.5-7B-Instruct", "Qwen 2.5 7B", 131_072, 8_192, tools = true))
    }

    /**
     * 返回内置 + 用户覆盖合并后的完整目录。
     *
     * @param providerId 为空返回全部；非空只返回该供应商。
     */
    fun entries(providerId: String? = null): List<ModelCatalogEntry> {
        val merged = merge(builtinEntries(), loadUserOverrides())
        return merged.filter { providerId == null || it.providerId.equals(providerId, ignoreCase = true) }
    }

    /** 按 providerId + modelId 精确查询（含用户覆盖）。 */
    fun find(providerId: String, modelId: String): ModelCatalogEntry? =
        entries(providerId).firstOrNull { it.modelId.equals(modelId, ignoreCase = true) }

    /**
     * 保存用户对某个模型的字段修改。
     *
     * 只写入用户显式修改的字段；内置其他字段继续回退。
     */
    @Synchronized
    fun saveUserOverride(providerId: String, modelId: String, patch: ModelCatalogEntry): ModelCatalogEntry {
        val current = find(providerId, modelId)
        val base = current ?: ModelCatalogEntry(providerId = providerId, modelId = modelId)
        val merged = base.copy(
            providerId = providerId,
            modelId = modelId,
            displayName = patch.displayName ?: base.displayName,
            contextWindow = patch.contextWindow ?: base.contextWindow,
            maxOutputTokens = patch.maxOutputTokens ?: base.maxOutputTokens,
            supportsVision = patch.supportsVision ?: base.supportsVision,
            supportsTools = patch.supportsTools ?: base.supportsTools,
            supportsReasoning = patch.supportsReasoning ?: base.supportsReasoning,
            updatedAt = System.currentTimeMillis(),
            userEdited = true,
            builtInRemoved = false,
        )
        val overrides = loadUserOverrides()
        val newList = overrides.filterNot {
            it.providerId == providerId && it.modelId == modelId
        } + merged
        persistUserOverrides(UserOverrides(items = newList, updatedAt = System.currentTimeMillis()))
        return merged
    }

    /** 用户删除内置模型（目录中隐藏，不破坏内置数据）。 */
    @Synchronized
    fun removeModel(providerId: String, modelId: String) {
        val overrides = loadUserOverrides()
        val removed = ModelCatalogEntry(
            providerId = providerId,
            modelId = modelId,
            updatedAt = System.currentTimeMillis(),
            userEdited = true,
            builtInRemoved = true,
        )
        val newList = overrides.filterNot {
            it.providerId == providerId && it.modelId == modelId
        } + removed
        persistUserOverrides(UserOverrides(items = newList, updatedAt = System.currentTimeMillis()))
    }

    /** 恢复内置默认（移除该模型的所有用户覆盖）。 */
    @Synchronized
    fun resetModel(providerId: String, modelId: String) {
        val overrides = loadUserOverrides()
        val newList = overrides.filterNot {
            it.providerId == providerId && it.modelId == modelId
        }
        persistUserOverrides(UserOverrides(items = newList, updatedAt = System.currentTimeMillis()))
    }

    /** 用户手动新增一个目录中不存在的模型。 */
    @Synchronized
    fun addUserModel(providerId: String, modelId: String, displayName: String? = null): ModelCatalogEntry =
        saveUserOverride(
            providerId,
            modelId,
            ModelCatalogEntry(
                providerId = providerId,
                modelId = modelId,
                displayName = displayName ?: modelId,
            ),
        )

    /** 导出当前合并目录为 JSON（备份/分享用）。 */
    fun exportJson(): String = AppJson.encodeToString(
        ListSerializer(ModelCatalogEntry.serializer()),
        entries(),
    )

    /** 导入目录 JSON（仅更新用户覆盖；内置仍由代码提供）。 */
    @Synchronized
    fun importJson(json: String): Boolean {
        return runCatching {
            val imported = AppJson.decodeFromString<List<ModelCatalogEntry>>(json)
            val overrides = loadUserOverrides()
            val existing = overrides.filterNot { existingEntry ->
                imported.any { it.providerId == existingEntry.providerId && it.modelId == existingEntry.modelId }
            }
            persistUserOverrides(
                UserOverrides(
                    items = existing + imported.map { it.copy(userEdited = true, updatedAt = System.currentTimeMillis()) },
                    updatedAt = System.currentTimeMillis(),
                ),
            )
        }.onFailure { e ->
            Logger.w(TAG, "模型目录导入失败", e)
        }.isSuccess
    }

    private fun entry(
        providerId: String,
        modelId: String,
        displayName: String? = null,
        contextWindow: Int? = null,
        maxOutputTokens: Int? = null,
        vision: Boolean? = null,
        tools: Boolean? = null,
        reasoning: Boolean? = null,
    ) = ModelCatalogEntry(
        providerId = providerId,
        modelId = modelId,
        displayName = displayName,
        contextWindow = contextWindow,
        maxOutputTokens = maxOutputTokens,
        supportsVision = vision,
        supportsTools = tools,
        supportsReasoning = reasoning,
    )

    private fun merge(
        builtin: List<ModelCatalogEntry>,
        user: List<ModelCatalogEntry>,
    ): List<ModelCatalogEntry> {
        if (user.isEmpty()) return builtin
        val userByKey = user.associateBy { keyOf(it.providerId, it.modelId) }
        val result = LinkedHashMap<String, ModelCatalogEntry>()
        builtin.forEach { b ->
            val u = userByKey[keyOf(b.providerId, b.modelId)]
            if (u?.builtInRemoved == true) return@forEach
            result[keyOf(b.providerId, b.modelId)] = if (u == null) b else b.mergeUser(u)
        }
        user.forEach { u ->
            if (!u.builtInRemoved) {
                result.putIfAbsent(keyOf(u.providerId, u.modelId), u)
            }
        }
        return result.values.toList()
    }

    private fun ModelCatalogEntry.mergeUser(user: ModelCatalogEntry): ModelCatalogEntry =
        copy(
            displayName = user.displayName ?: displayName,
            contextWindow = user.contextWindow ?: contextWindow,
            maxOutputTokens = user.maxOutputTokens ?: maxOutputTokens,
            supportsVision = user.supportsVision ?: supportsVision,
            supportsTools = user.supportsTools ?: supportsTools,
            supportsReasoning = user.supportsReasoning ?: supportsReasoning,
            updatedAt = user.updatedAt.let { if (it > 0) it else updatedAt },
            userEdited = user.userEdited || userEdited,
            builtInRemoved = user.builtInRemoved || builtInRemoved,
        )

    private fun loadUserOverrides(): List<ModelCatalogEntry> {
        val cached = userCache
        if (cached != null) return cached.items
        if (!userOverridesFile.exists()) return emptyList()
        return runCatching {
            AppJson.decodeFromString(UserOverrides.serializer(), userOverridesFile.readText()).items
        }.onSuccess {
            userCache = UserOverrides(items = it)
        }.getOrElse { e ->
            Logger.w(TAG, "用户模型覆盖解析失败,按空处理", e)
            emptyList()
        }
    }

    private fun persistUserOverrides(overrides: UserOverrides) {
        runCatching {
            catalogDir.mkdirs()
            userOverridesFile.writeText(AppJson.encodeToString(UserOverrides.serializer(), overrides))
            userCache = overrides
        }.onFailure { e ->
            Logger.w(TAG, "用户模型覆盖写入失败", e)
        }
    }

    private fun keyOf(providerId: String, modelId: String) =
        "${providerId.lowercase()}\u0000${modelId.lowercase()}"

    companion object {
        private const val TAG = "ModelCatalogStore"
    }
}
