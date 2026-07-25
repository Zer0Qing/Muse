package io.zer0.muse.importer

import android.content.Context
import android.net.Uri
import io.zer0.ai.core.Model
import io.zer0.ai.core.ProviderConfig
import io.zer0.ai.core.ProviderType
import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.data.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Phase 8.9: 第三方客户端配置导入器。
 *
 * 支持两种格式(自动嗅探,无需用户指定来源):
 *  - **CherryStudio**: 导出 JSON 含 `providers` 数组,字段为
 *    `id` / `name` / `apiHost` / `apiKey` / `models[]`(每项 `{id,name}` 或纯字符串)
 *  - **Chatbox**: 导出 JSON 含 `providers` 数组或 `settings.<providerId>` map,
 *    字段类似(`endpoint` 或 `apiHost` 作为 baseUrl)
 *
 * 设计:
 *  - 纯字符串/JSON 解析,不依赖任何外部 schema 库
 *  - 每个 Provider 转换为 [ProviderConfig] 并写入 [SettingsRepository.addProvider]
 *  - id 冲突时跳过(不覆盖已有 Provider)
 *  - 类型推断:baseUrl 含 `anthropic.com` → ANTHROPIC;含 `gemini`/`googleapis` → GEMINI;其他 → OPENAI
 *  - 不导入模型能力/视觉/流式标记(用户后续手动完善)
 *
 * 限制:
 *  - 不导入对话历史(CherryStudio/Chatbox 各自的会话格式与 muse 的 SessionEntity 不兼容)
 *  - 不导入 Assistant / QuickMessage / Lorebook 等高级配置(各客户端字段差异大)
 *  - CherryStudio 的 `isSystem` 标志忽略(已通过 id 冲突跳过处理)
 *
 * @param settings muse 设置仓库(用于 addProvider)
 */
class ConfigImporter(
    private val settings: SettingsRepository,
) {

    /**
     * 导入结果。
     * @param imported 成功导入的 Provider 数量
     * @param skipped 跳过的 Provider 数量(id 冲突或字段缺失)
     * @param providers 导入的 Provider 名称列表(用于 UI 提示)
     * @param skippedProviders 问题7.5: 被跳过的 Provider 名称列表(id 冲突场景),
     *   供 UI 弹提示"以下 Provider 已存在,已跳过:xxx"
     */
    data class Result(
        val imported: Int,
        val skipped: Int,
        val providers: List<String>,
        val skippedProviders: List<String> = emptyList(),
    )

    /**
     * 从 SAF URI 读取并导入配置。
     * @return [Result];解析失败时 imported=0
     */
    suspend fun importFromUri(context: Context, uri: Uri): Result = withContext(Dispatchers.IO) {
        val text = resultOf {
            context.contentResolver.openInputStream(uri)?.use { input ->
                // v1.114: 限制读取 10MB,防止超大文件 OOM
                val MAX_READ_BYTES = 10L * 1024 * 1024
                val sb = StringBuilder()
                val buffer = CharArray(8192)
                var total = 0L
                input.bufferedReader(Charsets.UTF_8).use { reader ->
                    while (true) {
                        val read = reader.read(buffer)
                        if (read <= 0) break
                        total += read
                        if (total > MAX_READ_BYTES) {
                            error("文件过大,超过 ${MAX_READ_BYTES / 1024 / 1024}MB 限制")
                        }
                        sb.append(buffer, 0, read)
                    }
                }
                sb.toString()
            }
        }.getOrNull()
        if (text.isNullOrBlank()) {
            Logger.w("ConfigImporter", "读取导入文件失败或内容为空")
            return@withContext Result(0, 0, emptyList())
        }
        importFromJson(text)
    }

    /**
     * 从 JSON 字符串解析并导入 Provider。
     * 自动嗅探 CherryStudio / Chatbox 格式。
     * @return [Result]
     */
    suspend fun importFromJson(jsonText: String): Result = withContext(Dispatchers.IO) {
        val root = resultOf { AppJson.parseToJsonElement(jsonText) }.getOrNull() ?: run {
            Logger.w("ConfigImporter", "JSON 解析失败")
            return@withContext Result(0, 0, emptyList())
        }
        if (root !is JsonObject) return@withContext Result(0, 0, emptyList())

        val rawProviders = extractProviderList(root)
        if (rawProviders.isEmpty()) return@withContext Result(0, 0, emptyList())

        var imported = 0
        var skipped = 0
        val names = mutableListOf<String>()
        // 问题7.5: 记录因 id 冲突被跳过的 Provider 名称,供 UI 提示
        val skippedNames = mutableListOf<String>()
        // 取一次已存在的 provider id 集合,用于去重(id 冲突时跳过,不覆盖)
        val existingIds = settings.providersFlow.first().map { it.id }.toSet()
        for (raw in rawProviders) {
            val cfg = parseProvider(raw) ?: run { skipped++; continue }
            if (cfg.id in existingIds) {
                // id 冲突 → 跳过(不覆盖)
                skipped++
                skippedNames += cfg.displayName
                Logger.i("ConfigImporter", "Skip duplicate provider: ${cfg.id} (${cfg.displayName})")
                continue
            }
            // 问题7.4: apiKey 明文风险已由 SettingsRepository 内部消除 —
            // addProvider → encodeProviders 已对每个 ProviderConfig.apiKey 调用
            // SecureKeyStore.encrypt(Android Keystore AES-256-GCM),持久化层为密文。
            // 此处无需额外加密,直接传入明文 apiKey 即可。
            settings.addProvider(cfg)
            imported++
            names += cfg.displayName
        }
        Result(imported, skipped, names, skippedNames)
    }

    /**
     * 从根对象提取 Provider JSON 对象列表。
     * 支持三种结构:
     *  - `providers: [...]`(CherryStudio / 新版 Chatbox)
     *  - `settings: { <providerId>: { ... } }`(旧版 Chatbox)
     *  - 直接是数组(部分导出格式)
     */
    private fun extractProviderList(root: JsonObject): List<JsonObject> {
        // 1. root.providers 数组
        val providers = root["providers"]
        if (providers is JsonArray) {
            return providers.mapNotNull { it as? JsonObject }
        }
        // 2. root.settings.<id> map
        val settingsObj = root["settings"]
        if (settingsObj is JsonObject) {
            // 把 map 的 key 作为 provider id 注入对象
            return settingsObj.entries.mapNotNull { (id, v) ->
                if (v is JsonObject) mergeProviderId(v, id) else null
            }
        }
        // 3. root 直接是数组(理论上 root 已是 JsonObject,这里兜底)
        return emptyList()
    }

    /** 把 map 的 key 合并到对象作为 id 字段(若对象无 id)。 */
    private fun mergeProviderId(obj: JsonObject, id: String): JsonObject {
        val map = obj.toMutableMap()
        if (map["id"] == null) map["id"] = JsonPrimitive(id)
        return JsonObject(map)
    }

    /**
     * 把单个 Provider JSON 对象解析为 [ProviderConfig]。
     * 兼容字段:
     *  - id / name / displayName
     *  - apiHost / endpoint / baseUrl
     *  - apiKey / key
     *  - models: [{id,name}] / [string] / [{id}]
     */
    private fun parseProvider(obj: JsonObject): ProviderConfig? {
        val id = strField(obj, "id") ?: return null
        val displayName = strField(obj, "name") ?: strField(obj, "displayName") ?: id
        val baseUrl = strField(obj, "apiHost") ?: strField(obj, "endpoint") ?: strField(obj, "baseUrl") ?: ""
        val apiKey = strField(obj, "apiKey") ?: strField(obj, "key") ?: ""
        val type = inferType(baseUrl, id)
        val models = parseModels(obj["models"], id)
        // 跳过完全无 apiKey 且无 models 的空 Provider(常见于未启用的内置项)
        if (apiKey.isBlank() && models.isEmpty()) return null
        return ProviderConfig(
            id = sanitizeId(id),
            displayName = displayName,
            type = type,
            baseUrl = baseUrl,
            apiKey = apiKey,
            models = models,
        )
    }

    /** 根据 baseUrl / id 推断 Provider 类型。 */
    private fun inferType(baseUrl: String, id: String): ProviderType {
        val combined = (baseUrl + id).lowercase()
        return when {
            "anthropic" in combined || "claude" in combined -> ProviderType.ANTHROPIC
            "gemini" in combined || "googleapis" in combined -> ProviderType.GEMINI
            else -> ProviderType.OPENAI
        }
    }

    /**
     * 解析 models 字段。
     * 支持:数组元素为 string / {id,name} / {id}。
     */
    private fun parseModels(element: JsonElement?, providerId: String): List<Model> {
        if (element !is JsonArray) return emptyList()
        return element.mapNotNull { item ->
            when (item) {
                is JsonPrimitive -> Model(id = item.content, providerId = providerId)
                is JsonObject -> {
                    val mid = strField(item, "id") ?: return@mapNotNull null
                    val mname = strField(item, "name") ?: mid
                    Model(id = mid, name = mname, providerId = providerId)
                }
                else -> null
            }
        }
    }

    /** 从 JsonObject 取字符串字段(支持 JsonPrimitive.content)。 */
    private fun strField(obj: JsonObject, key: String): String? {
        val v = obj[key] ?: return null
        return (v as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
    }

    /**
     * 把任意字符串转为合法 Provider id(避免路径分隔符/空格)。
     * 保留字母数字 + 横杠 + 下划线 + 点,其余替换为下划线。
     */
    private fun sanitizeId(raw: String): String =
        raw.lowercase().replace(INVALID_ID_CHAR_REGEX, "_").take(64).ifBlank { "imported_${System.currentTimeMillis()}" }

    companion object {
        // L6: 把 Regex 提到 companion object 复用,避免每次调用 sanitizeId 都新建实例
        private val INVALID_ID_CHAR_REGEX = Regex("[^a-z0-9._-]")
    }
}
