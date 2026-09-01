package io.zer0.muse.data.schedule

import io.zer0.common.AppJson
import io.zer0.common.Logger
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * v1.137: 复杂自动化配置(条件触发、链式任务、多动作类型)。
 *
 * 以 JSON 形式持久化在 ScheduledTaskEntity 的扩展字段中,
 * 保持与旧版定时任务的向后兼容(空值 = 默认行为 = 总是执行 ai_prompt)。
 */
object AutomationConfig {

    /** 条件类型。 */
    @Serializable
    data class Condition(
        @SerialName("type") val type: String = "always",
        @SerialName("config") val config: JsonObject = JsonObject(emptyMap()),
    ) {
        companion object {
            const val ALWAYS = "always"
            const val NETWORK_AVAILABLE = "network_available"
            const val TIME_RANGE = "time_range"
            const val CONTAINS = "contains"
            const val QUICK_NOTE_EXISTS = "quick_note_exists"
            /** v1.0.17: 电量阈值条件,config["minLevel"] 为最低电量百分比(默认 20)。 */
            const val BATTERY_LEVEL = "battery_level"
            /** v1.0.17: 充电状态条件,config["mustCharging"] 为是否必须充电(默认 true)。 */
            const val CHARGING = "charging"
        }
    }

    /** 动作配置。 */
    @Serializable
    data class Action(
        @SerialName("type") val type: String = "ai_prompt",
        @SerialName("config") val config: JsonObject = JsonObject(emptyMap()),
    ) {
        companion object {
            const val AI_PROMPT = "ai_prompt"
            const val CREATE_QUICK_NOTE = "create_quick_note"
            const val CALL_TOOL = "call_tool"
            const val NOTIFY = "notify"
        }
    }

    fun Condition.toJson(): String = try {
        AppJson.encodeToString(Condition.serializer(), this)
    } catch (e: Exception) {
        Logger.w("AutomationConfig", "Condition encode failed: ${e.message}")
        ""
    }

    fun String.toCondition(): Condition = if (this.isBlank()) {
        Condition()
    } else {
        try {
            AppJson.decodeFromString(Condition.serializer(), this)
        } catch (e: Exception) {
            Logger.w("AutomationConfig", "Condition decode failed: ${e.message}")
            Condition()
        }
    }

    fun Action.toJson(): String = try {
        AppJson.encodeToString(Action.serializer(), this)
    } catch (e: Exception) {
        Logger.w("AutomationConfig", "Action encode failed: ${e.message}")
        ""
    }

    fun String.toAction(): Action = if (this.isBlank()) {
        Action()
    } else {
        try {
            AppJson.decodeFromString(Action.serializer(), this)
        } catch (e: Exception) {
            Logger.w("AutomationConfig", "Action decode failed: ${e.message}")
            Action()
        }
    }

    fun List<String>.toIdsJson(): String = try {
        AppJson.encodeToString(kotlinx.serialization.builtins.ListSerializer(kotlinx.serialization.json.JsonElement.serializer()), this.map { kotlinx.serialization.json.JsonPrimitive(it) })
    } catch (e: Exception) {
        Logger.w("AutomationConfig", "Ids encode failed: ${e.message}")
        "[]"
    }

    fun String.toIdsList(): List<String> = if (this.isBlank()) {
        emptyList()
    } else {
        try {
            AppJson.decodeFromString(kotlinx.serialization.builtins.ListSerializer(kotlinx.serialization.json.JsonElement.serializer()), this)
                .mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
        } catch (e: Exception) {
            Logger.w("AutomationConfig", "Ids decode failed: ${e.message}")
            emptyList()
        }
    }

    inline fun <reified T> JsonObject.getValue(key: String): T? = try {
        AppJson.decodeFromJsonElement<T>(this[key] ?: JsonObject(emptyMap()))
    } catch (e: Exception) {
        null
    }

    inline fun <reified T> JsonObject.getString(key: String): String? =
        this[key]?.toString()?.trim('"')
}
