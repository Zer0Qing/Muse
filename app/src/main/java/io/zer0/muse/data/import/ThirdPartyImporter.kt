package io.zer0.muse.data.`import`

import android.content.Context
import android.net.Uri
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.Model
import io.zer0.ai.core.ModelAbility
import io.zer0.ai.core.ProviderConfig
import io.zer0.ai.core.ProviderType
import io.zer0.ai.core.UIMessage
import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.assistant.AssistantEntity
import io.zer0.muse.data.assistant.AssistantRepository
import io.zer0.muse.data.session.SessionRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.uuid.Uuid
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import io.zer0.muse.R
import io.zer0.muse.util.readZipEntryWithLimit
import java.util.zip.ZipInputStream

/**
 * v1.61-A: 第三方数据导入结果。
 */
// i18n 已评估(结构重构暂缓)— errors: List<String> 的填充已全部改用
// context.getString(R.string.import_error_* / import_default_*),用户可见消息已本地化。
// 进一步将 List<String> 改为 List<Int>(资源 ID)需同步改 ImportResult 结构 + UI 调用方
// + 含参消息的 String.format,改动跨文件且含动态参数(e.message / skippedMessages),
// 风险较大,暂缓。i18n 目标已达成,仅结构偏好延后。
data class ImportResult(
    val providersImported: Int = 0,
    val assistantsImported: Int = 0,
    val conversationsImported: Int = 0,
    val messagesImported: Int = 0,
    val errors: List<String> = emptyList(),
)

/**
 * v1.61-A: 第三方数据导入器。
 * 支持 RikkaHub 和 Kelivo 两种备份格式。
 */
object ThirdPartyImporter {

    private const val TAG = "ThirdPartyImporter"

    // H-IMP1: ZIP 解压安全限制,防止 ZIP 炸弹 / 内存耗尽
    private const val MAX_TOTAL_SIZE = 100L * 1024 * 1024 // 100MB
    private const val MAX_SINGLE_FILE = 10L * 1024 * 1024 // 10MB
    private const val MAX_FILE_COUNT = 1000

    /** UTF-8 BOM 前缀(部分 Windows 编辑器导出的 JSON 会带 BOM,需跳过)。 */
    private val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())

    /**
     * 自动检测备份格式并导入。
     * @param backupUri SAF URI(backup_*.zip 或 kelivo_backup_*.zip)
     */
    suspend fun importFromUri(
        context: Context,
        uri: Uri,
        settings: SettingsRepository,
        assistantRepo: AssistantRepository,
        sessionRepo: SessionRepository,
    ): ImportResult = withContext(Dispatchers.IO) {
        val ctx = context
        // H-IMP2: 所有磁盘 IO 在 IO 线程执行
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: return@withContext ImportResult(errors = listOf(context.getString(R.string.import_error_read_file_failed)))

        // 读取 ZIP 内所有条目(H-IMP1: 限制总大小/单文件大小/文件数量)
        val entries = mutableMapOf<String, ByteArray>()
        var totalSize = 0L
        try {
            ZipInputStream(bytes.inputStream()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        // M-IMP2: 校验条目名,防止路径穿越(虽然这里只在内存中按 key 取,但防御性校验)
                        if (entry.name.contains("..") || entry.name.startsWith("/")) {
                            Logger.w(TAG, "跳过可疑路径条目: ${entry.name}")
                            entry = zis.nextEntry
                            continue
                        }
                        // 文件数量限制
                        if (entries.size >= MAX_FILE_COUNT) {
                            throw IllegalStateException("解压条目数超过限制($MAX_FILE_COUNT)")
                        }
                        // 单文件大小限制(先查 entry.size,可能为 -1;实际读取时再校验)
                        val buf = readZipEntryWithLimit(zis, MAX_SINGLE_FILE)
                        totalSize += buf.size.toLong()
                        if (totalSize > MAX_TOTAL_SIZE) {
                            throw IllegalStateException("解压内容总大小超过限制(${MAX_TOTAL_SIZE / 1024 / 1024}MB)")
                        }
                        entries[entry.name] = buf
                    }
                    entry = zis.nextEntry
                }
            }
        } catch (e: IllegalStateException) {
            Logger.w(TAG, "解压终止: ${e.message}")
            return@withContext ImportResult(errors = listOf(e.message ?: context.getString(R.string.import_error_unzip_size_exceeded)))
        } catch (e: Exception) {
            Logger.w(TAG, "无法解压文件", e)
            return@withContext ImportResult(errors = listOf(context.getString(R.string.import_error_unzip_failed, e.message)))
        }

        // 判断格式:Kelivo 有 chats.json;RikkaHub 有 settings.json 且不含 chats.json
        val settingsJson = entries["settings.json"]?.let { decodeUtf8SkipBom(it) }
        val chatsJson = entries["chats.json"]?.let { decodeUtf8SkipBom(it) }
        val hasKelivoChats = entries.containsKey("chats.json")

        when {
            hasKelivoChats -> importKelivo(
                ctx,
                settingsJson,
                chatsJson,
                settings,
                assistantRepo,
                sessionRepo,
            )
            settingsJson != null -> importRikkaHub(
                ctx,
                settingsJson,
                settings,
                assistantRepo,
                sessionRepo,
            )
            else -> ImportResult(
                errors = listOf(context.getString(R.string.import_error_unknown_format)),
            )
        }
    }

    /**
     * M-IMP3: 把字节数组按 UTF-8 解码并跳过开头的 BOM(若有)。
     */
    private fun decodeUtf8SkipBom(bytes: ByteArray): String {
        val src = if (bytes.size >= 3 &&
            bytes[0] == UTF8_BOM[0] && bytes[1] == UTF8_BOM[1] && bytes[2] == UTF8_BOM[2]
        ) {
            bytes.copyOfRange(3, bytes.size)
        } else {
            bytes
        }
        return BufferedReader(InputStreamReader(src.inputStream(), StandardCharsets.UTF_8)).readText()
    }

    // ── RikkaHub 导入 ──
    // RikkaHub 的 settings.json 是 Settings data class 的完整 JSON,含 providers 和 assistants

    private suspend fun importRikkaHub(
        context: Context,
        settingsJson: String,
        settings: SettingsRepository,
        assistantRepo: AssistantRepository,
        sessionRepo: SessionRepository,
    ): ImportResult {
        val errors = mutableListOf<String>()
        var providersCount = 0
        var assistantsCount = 0

        try {
            val root = AppJson.parseToJsonElement(settingsJson).jsonObject

            // 导入 Provider
            val providersArr = root["providers"]?.jsonArray
            if (providersArr != null) {
                for (providerElem in providersArr) {
                    try {
                        val providerObj = providerElem.jsonObject
                        val type = providerObj["type"]?.jsonPrimitive?.contentOrNull ?: "openai"
                        val providerType = when (type) {
                            "openai" -> ProviderType.OPENAI
                            "google" -> ProviderType.GEMINI
                            "claude" -> ProviderType.ANTHROPIC
                            else -> ProviderType.OPENAI
                        }
                        val sourceId = providerObj["id"]?.jsonPrimitive?.contentOrNull ?: Uuid.random().toString()
                        val id = "rikka_$sourceId"
                        val name = providerObj["name"]?.jsonPrimitive?.contentOrNull ?: context.getString(R.string.import_default_provider_name)
                        val apiKey = providerObj["apiKey"]?.jsonPrimitive?.contentOrNull ?: ""
                        val baseUrl = providerObj["baseUrl"]?.jsonPrimitive?.contentOrNull ?: ""
                        val enabled = providerObj["enabled"]?.jsonPrimitive?.booleanOrNull ?: true

                        // 解析模型列表
                        val models = mutableListOf<Model>()
                        val modelsArr = providerObj["models"]?.jsonArray
                        if (modelsArr != null) {
                            for (modelElem in modelsArr) {
                                val modelObj = modelElem.jsonObject
                                val modelId = modelObj["modelId"]?.jsonPrimitive?.contentOrNull ?: continue
                                val displayName = modelObj["displayName"]?.jsonPrimitive?.contentOrNull ?: modelId
                                val abilities = mutableSetOf<ModelAbility>()
                                modelObj["abilities"]?.jsonArray?.forEach { ab ->
                                    ab.jsonPrimitive.contentOrNull?.let { ability ->
                                        when (ability) {
                                            "tool" -> abilities.add(ModelAbility.TOOL)
                                            "reasoning" -> abilities.add(ModelAbility.REASONING)
                                        }
                                    }
                                }
                                val inputMods = mutableSetOf("text")
                                modelObj["inputModalities"]?.jsonArray?.forEach { m ->
                                    if (m.jsonPrimitive.contentOrNull == "image") inputMods.add("image")
                                }
                                models.add(
                                    Model(
                                        id = modelId,
                                        name = displayName,
                                        providerId = id,
                                        abilities = abilities,
                                        inputModalities = inputMods,
                                    ),
                                )
                            }
                        }

                        val config = ProviderConfig(
                            id = id,
                            displayName = name,
                            type = providerType,
                            baseUrl = baseUrl,
                            apiKey = apiKey,
                            models = models,
                            enabled = enabled,
                        )
                        settings.addProvider(config)
                        providersCount++
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (e: Exception) {
                        errors.add(context.getString(R.string.import_error_provider_failed, e.message))
                    }
                }
            }

            // 导入助手
            val assistantsArr = root["assistants"]?.jsonArray
            if (assistantsArr != null) {
                for (asstElem in assistantsArr) {
                    try {
                        val asstObj = asstElem.jsonObject
                        val name = asstObj["name"]?.jsonPrimitive?.contentOrNull ?: context.getString(R.string.import_default_assistant_name)
                        val systemPrompt = asstObj["systemPrompt"]?.jsonPrimitive?.contentOrNull ?: ""
                        val temperature = asstObj["temperature"]?.jsonPrimitive?.floatOrNull
                        val maxTokens = asstObj["maxTokens"]?.jsonPrimitive?.intOrNull
                        val contextMessageSize = asstObj["contextMessageSize"]?.jsonPrimitive?.intOrNull ?: 20

                        val sourceId = asstObj["id"]?.jsonPrimitive?.contentOrNull ?: Uuid.random().toString()
                        val entity = AssistantEntity(
                            id = "rikka_$sourceId",
                            name = name,
                            systemPrompt = systemPrompt,
                            temperature = temperature,
                            maxTokens = maxTokens,
                            contextMessageSize = contextMessageSize,
                        )
                        assistantRepo.upsert(entity)
                        assistantsCount++
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (e: Exception) {
                        errors.add(context.getString(R.string.import_error_assistant_failed, e.message))
                    }
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            Logger.w(TAG, "RikkaHub 备份解析失败", e)
            errors.add(context.getString(R.string.import_error_rikka_parse_failed, e.message))
        }

        return ImportResult(
            providersImported = providersCount,
            assistantsImported = assistantsCount,
            errors = errors,
        )
    }

    // ── Kelivo 导入 ──
    // Kelivo 的 settings.json 是 SharedPreferences 键值对 JSON
    // chats.json 包含 conversations 和 messages

    private suspend fun importKelivo(
        context: Context,
        settingsJson: String?,
        chatsJson: String?,
        settings: SettingsRepository,
        assistantRepo: AssistantRepository,
        sessionRepo: SessionRepository,
    ): ImportResult {
        val errors = mutableListOf<String>()
        var providersCount = 0
        var assistantsCount = 0
        var conversationsCount = 0
        var messagesCount = 0
        var skippedMessages = 0

        try {
            if (settingsJson != null) {
                val settingsRoot = AppJson.parseToJsonElement(settingsJson).jsonObject

                val providerConfigsRaw = settingsRoot["provider_configs_v1"]?.jsonPrimitive?.contentOrNull
                if (providerConfigsRaw != null) {
                    val providerConfigs = AppJson.parseToJsonElement(providerConfigsRaw).jsonObject
                    for ((providerKey, providerElem) in providerConfigs) {
                        try {
                            val providerObj = providerElem.jsonObject
                            val providerTypeStr = providerObj["providerType"]?.jsonPrimitive?.contentOrNull ?: "openai"
                            val providerType = when (providerTypeStr) {
                                "openai" -> ProviderType.OPENAI
                                "google" -> ProviderType.GEMINI
                                "claude" -> ProviderType.ANTHROPIC
                                else -> ProviderType.OPENAI
                            }
                            val apiKey = providerObj["apiKey"]?.jsonPrimitive?.contentOrNull ?: ""
                            val baseUrl = providerObj["baseUrl"]?.jsonPrimitive?.contentOrNull ?: ""
                            val enabled = providerObj["enabled"]?.jsonPrimitive?.booleanOrNull ?: true
                            val name = providerObj["name"]?.jsonPrimitive?.contentOrNull ?: providerKey

                            val providerId = "kelivo_$providerKey"
                            val models = mutableListOf<Model>()
                            val modelsArr = providerObj["models"]?.jsonArray
                            if (modelsArr != null) {
                                for (modelElem in modelsArr) {
                                    val modelObj = modelElem.jsonObject
                                    val modelId = modelObj["id"]?.jsonPrimitive?.contentOrNull ?: continue
                                    val displayName = modelObj["displayName"]?.jsonPrimitive?.contentOrNull ?: modelId
                                    models.add(
                                        Model(
                                            id = modelId,
                                            name = displayName,
                                            providerId = providerId,
                                        ),
                                    )
                                }
                            }

                            val config = ProviderConfig(
                                id = providerId,
                                displayName = name,
                                type = providerType,
                                baseUrl = baseUrl,
                                apiKey = apiKey,
                                models = models,
                                enabled = enabled,
                            )
                            settings.addProvider(config)
                            providersCount++
                        } catch (ce: CancellationException) {
                            throw ce
                        } catch (e: Exception) {
                            errors.add(context.getString(R.string.import_error_provider_with_key_failed, providerKey, e.message))
                        }
                    }
                }

                val assistantsRaw = settingsRoot["assistants_v1"]?.jsonPrimitive?.contentOrNull
                if (assistantsRaw != null) {
                    val assistantsArr = AppJson.parseToJsonElement(assistantsRaw).jsonArray
                    for (asstElem in assistantsArr) {
                        try {
                            val asstObj = asstElem.jsonObject
                            val name = asstObj["name"]?.jsonPrimitive?.contentOrNull ?: context.getString(R.string.import_default_assistant_name)
                            val systemPrompt = asstObj["systemPrompt"]?.jsonPrimitive?.contentOrNull ?: ""
                            val temperature = asstObj["temperature"]?.jsonPrimitive?.floatOrNull
                            val maxTokens = asstObj["maxTokens"]?.jsonPrimitive?.intOrNull
                            val contextMessageSize = asstObj["contextMessageSize"]?.jsonPrimitive?.intOrNull ?: 20

                            val sourceId = asstObj["id"]?.jsonPrimitive?.contentOrNull ?: Uuid.random().toString()
                            val entity = AssistantEntity(
                                id = "kelivo_$sourceId",
                                name = name,
                                systemPrompt = systemPrompt,
                                temperature = temperature,
                                maxTokens = maxTokens,
                                contextMessageSize = contextMessageSize,
                            )
                            assistantRepo.upsert(entity)
                            assistantsCount++
                        } catch (ce: CancellationException) {
                            throw ce
                        } catch (e: Exception) {
                            errors.add(context.getString(R.string.import_error_assistant_failed, e.message))
                        }
                    }
                }
            }

            if (chatsJson != null) {
                val chatsRoot = AppJson.parseToJsonElement(chatsJson).jsonObject
                val conversationsArr = chatsRoot["conversations"]?.jsonArray
                val messagesArr = chatsRoot["messages"]?.jsonArray

                // 按会话分组消息
                val messagesByConv = mutableMapOf<String, MutableList<UIMessage>>()
                if (messagesArr != null) {
                    for (msgElem in messagesArr) {
                        try {
                            val msgObj = msgElem.jsonObject
                            val convId = msgObj["conversationId"]?.jsonPrimitive?.contentOrNull ?: continue
                            val roleStr = msgObj["role"]?.jsonPrimitive?.contentOrNull ?: "user"
                            val role = when (roleStr) {
                                "user" -> MessageRole.USER
                                "assistant" -> MessageRole.ASSISTANT
                                "system" -> MessageRole.SYSTEM
                                else -> MessageRole.USER
                            }
                            val content = msgObj["content"]?.jsonPrimitive?.contentOrNull ?: ""
                            val reasoning = msgObj["reasoningText"]?.jsonPrimitive?.contentOrNull
                            val timestamp = msgObj["timestamp"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
                                ?: System.currentTimeMillis()

                            val msg = UIMessage(
                                id = Uuid.random(),
                                role = role,
                                content = content,
                                reasoning = reasoning,
                                createdAt = timestamp,
                            )
                            messagesByConv.getOrPut(convId) { mutableListOf() }.add(msg)
                        } catch (e: Exception) {
                            // v1.71: 统计跳过的消息并记录日志,原静默吞导致用户不知道丢了哪些消息
                            skippedMessages++
                            Logger.w(TAG, "Kelivo 消息解析失败已跳过", e)
                        }
                    }
                }

                // 创建会话并导入消息
                if (conversationsArr != null) {
                    // H-IMP3: 预取所有已存在的 assistantId,用于校验/重映射 Kelivo 的 assistantId,
                    // 避免会话引用到不存在的 assistant(悬空引用)。原导入逻辑直接透传 Kelivo
                    // 的 assistantId,但该 id 在本项目不存在,导致 UI 无法加载助手信息。
                    val existingAssistantIds: Set<String> = assistantRepo.getAll().map { it.id }.toSet()
                    val defaultAssistantId = existingAssistantIds.firstOrNull { it == "default" }
                        ?: existingAssistantIds.firstOrNull()
                        ?: "default"

                    for (convElem in conversationsArr) {
                        try {
                            val convObj = convElem.jsonObject
                            val convId = convObj["id"]?.jsonPrimitive?.contentOrNull ?: continue
                            val title = convObj["title"]?.jsonPrimitive?.contentOrNull ?: context.getString(R.string.import_default_session_title)
                            val rawAssistantId = convObj["assistantId"]?.jsonPrimitive?.contentOrNull

                            // H-IMP3: 校验 assistantId,不存在则尝试映射到本批次导入的 Kelivo 助手,
                            // 仍不存在则回退到默认助手,杜绝悬空引用。
                            val mappedAssistantId = rawAssistantId?.let { id ->
                                when {
                                    existingAssistantIds.contains(id) -> id
                                    existingAssistantIds.contains("kelivo_$id") -> "kelivo_$id"
                                    else -> {
                                        Logger.w(TAG, "Kelivo 会话 assistantId '$id' 不存在,回退默认助手")
                                        defaultAssistantId
                                    }
                                }
                            } ?: defaultAssistantId

                            val sessionId = sessionRepo.createSession(assistantId = mappedAssistantId)
                            sessionRepo.renameSession(sessionId, title)
                            conversationsCount++

                            // 导入该会话的消息
                            val convMessages = messagesByConv[convId] ?: emptyList()
                            for (msg in convMessages) {
                                sessionRepo.appendMessage(sessionId, msg)
                                messagesCount++
                            }
                        } catch (ce: CancellationException) {
                            throw ce
                        } catch (e: Exception) {
                            errors.add(context.getString(R.string.import_error_session_failed, e.message))
                        }
                    }
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            Logger.w(TAG, "Kelivo 备份解析失败", e)
            errors.add(context.getString(R.string.import_error_kelivo_parse_failed, e.message))
        }

        if (skippedMessages > 0) {
            errors.add(context.getString(R.string.import_error_messages_skipped, skippedMessages))
        }

        return ImportResult(
            providersImported = providersCount,
            assistantsImported = assistantsCount,
            conversationsImported = conversationsCount,
            messagesImported = messagesCount,
            errors = errors,
        )
    }
}
