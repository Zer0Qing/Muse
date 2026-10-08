package io.zer0.muse.data.session

import io.zer0.common.AppJson
import kotlinx.serialization.Serializable

/**
 * v2.5.3 (P3-2): 会话级配置快照。
 *
 * 问题：会话此前只绑助手，模型/思考级别走全局；用户切了全局模型，旧会话「当时的配置」就找不回了，
 * 重发时会用新模型，得不到与当时一致的回复。
 *
 * 方案：会话首次生成时把当时的配置拍一份快照落库（[SessionEntity.configSnapshotJson]）。
 * 读取时「会话快照覆盖助手/全局字段」；若快照里固定的模型已被删除，则回退助手当前值（不硬失败）。
 *
 * 快照保持最小必要字段，仅覆盖影响生成结果的项：provider、模型、思考级别、温度、最大输出。
 */
@Serializable
data class SessionConfigSnapshot(
    val providerId: String? = null,
    val modelId: String? = null,
    /** 思考级别（OFF/AUTO/LOW/MEDIUM/HIGH/XHIGH…），null = 未固定。 */
    val reasoningLevel: String? = null,
    val temperature: Float? = null,
    val maxTokens: Int? = null,
) {
    /** 是否为空快照（没有任何被固定的字段）。 */
    fun isEmpty(): Boolean = providerId == null && modelId == null && reasoningLevel == null &&
        temperature == null && maxTokens == null

    companion object {
        /** 序列化为 JSON 字符串；空快照返回 null（不落库、不加列值）。 */
        fun encode(snapshot: SessionConfigSnapshot?): String? {
            if (snapshot == null || snapshot.isEmpty()) return null
            return runCatching { AppJson.encodeToString(serializer(), snapshot) }.getOrNull()
        }

        /** 反序列化；null/空白/坏 JSON 一律返回 null（视为未拍摄快照）。 */
        fun decode(raw: String?): SessionConfigSnapshot? {
            if (raw.isNullOrBlank()) return null
            return runCatching { AppJson.decodeFromString(serializer(), raw) }.getOrNull()
        }
    }
}
