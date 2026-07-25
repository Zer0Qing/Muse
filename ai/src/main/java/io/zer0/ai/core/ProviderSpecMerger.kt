package io.zer0.ai.core

/**
 * v1.0.7: 内置供应商规格(spec)与用户 overlay 合并器。
 *
 * 对齐 openhanako 的三层合并机制(L1 Builtin Plugin 声明 + L3 Provider Catalog overlay),
 * 在 Android 上的务实落地:
 *  - [ProviderConfig.specId] 非空时,标识该配置源自某个内置供应商规格
 *  - 运行时把 spec 默认模型列表与用户 overlay 模型列表合并(同 id 去重,用户字段优先)
 *  - 内置供应商更新默认模型列表后,已添加的用户自动看到新模型(无需手动重新添加)
 *
 * 与 openhanako 的差异:
 *  - openhanako 的 overlay 是稀疏对象(只含用户改过的字段),字段级覆盖
 *  - 1muse 的 ProviderConfig 是完整对象,这里只做模型列表合并(核心痛点),
 *    其余字段(baseUrl/displayName/specific 等)保持用户配置优先(用户可能已自定义)
 *
 * 合并策略(对齐 openhanako mergeProviderModelEntries):
 *  1. spec 默认模型先入(保持默认顺序),同 id 被 user 版本覆盖(用户可能改了 name/contextWindow)
 *  2. user 新增模型追加(不在 spec 中的)
 *  3. spec 或 user 任一为空时,直接返回非空方(不合并)
 */
object ProviderSpecMerger {

    /**
     * 合并 spec 默认模型列表 + 用户 overlay 模型列表。
     *
     * @param specModels 内置规格的默认模型列表(不可变声明,随版本发布)
     * @param userModels 用户配置的模型列表(可能为空,表示"用默认";可能非空,表示"已自定义/追加")
     * @return 合并后的模型列表:spec 默认 + user 追加,同 id user 优先
     */
    fun mergeModels(specModels: List<Model>, userModels: List<Model>): List<Model> {
        if (specModels.isEmpty()) return userModels
        if (userModels.isEmpty()) return specModels
        val userById = userModels.associateBy { it.id }
        val merged = LinkedHashMap<String, Model>(specModels.size + userModels.size)
        // spec 默认模型先入(保持顺序),同 id 被 user 覆盖
        for (model in specModels) {
            merged[model.id] = userById[model.id] ?: model
        }
        // user 新增模型追加(不在 spec 中的)
        for (model in userModels) {
            if (!merged.containsKey(model.id)) {
                merged[model.id] = model
            }
        }
        return merged.values.toList()
    }

    /**
     * 用 spec 默认值丰富用户配置(当前只合并模型列表)。
     *
     * @param config 用户的 ProviderConfig(已从 DataStore 读取并解密 apiKey)
     * @param spec 内置供应商规格(由调用方通过 specId 查找);null 时不合并
     * @return 合并后的 ProviderConfig;spec 为 null 或 config.specId 为 null 时原样返回
     */
    fun enrichConfig(config: ProviderConfig, spec: ProviderConfig?): ProviderConfig {
        if (spec == null || config.specId == null) return config
        val mergedModels = mergeModels(spec.models, config.models)
        if (mergedModels.size == config.models.size && mergedModels == config.models) {
            return config // 无变化,避免创建新对象
        }
        return config.copy(models = mergedModels)
    }
}
