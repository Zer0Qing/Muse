package io.zer0.ai.core

/**
 * Provider 与模型的稳定联合引用。
 *
 * Muse 的旧设置仍分别保存 providerId 和 modelId；本类型提供统一的可序列化文本形式，
 * 供日志、缓存和后续配置迁移使用。模型 ID 本身允许包含 `/`，因此只切分第一个分隔符。
 */
data class ModelReference(
    val providerId: String,
    val modelId: String,
) {
    init {
        require(providerId.isNotBlank()) { "providerId must not be blank" }
        require(modelId.isNotBlank()) { "modelId must not be blank" }
    }

    /** 返回稳定的 `providerId/modelId` 引用。 */
    fun asString(): String = "$providerId/$modelId"

    companion object {
        /** 解析 `providerId/modelId`，裸模型 ID 不会被猜测归属。 */
        fun parse(raw: String?): ModelReference? {
            val value = raw?.trim().orEmpty()
            val separator = value.indexOf('/')
            if (separator <= 0 || separator == value.lastIndex) return null
            val provider = value.substring(0, separator).trim()
            val model = value.substring(separator + 1).trim()
            return if (provider.isBlank() || model.isBlank()) null else ModelReference(provider, model)
        }

        /** 从已解析模型生成稳定联合引用。 */
        fun of(model: Model): ModelReference = ModelReference(model.providerId, model.id)
    }
}
