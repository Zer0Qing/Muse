package io.zer0.muse.data.card

import io.zer0.ai.ChatService
import io.zer0.ai.core.ChatRequestMode
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.data.assistant.AssistantRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 卡片内静默 AI 通道（`window.muse.ask`）与状态直存（`window.muse.saveState`）协调器。
 *
 * 与 `window.muse.send`（往聊天流写入用户气泡并触发主对话）不同：
 *  - 全程不写入 `messages` 表，不打断当前聊天记录；
 *  - 仅装配「简短助手人设 + 卡片任务要求 + 当前 state JSON」，单次 token 极轻；
 *  - 若模型返回里带 `state` 字段或卡片显式调用 `saveState`，同步落盘到 [CardDataStore]。
 */
class CardSilentAiCoordinator(
    private val chatService: ChatService? = null,
    private val assistantRepository: AssistantRepository? = null,
    private val cardDataStore: CardDataStore? = null,
    private val cardEntityDao: CardEntityDao? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val inFlightByCard = ConcurrentHashMap<String, AtomicBoolean>()
    private val rateWindowByCard = ConcurrentHashMap<String, MutableList<Long>>()
    private val stateSaveMutex = Mutex()

    private data class AskPayload(
        val prompt: String,
        val responseFormat: String,
        val assistantId: String,
        val stateJson: String?,
    )

    private sealed interface AskCompletion {
        data class Reply(val text: String) : AskCompletion
        data class Failure(val json: String) : AskCompletion
    }

    /** 处理 `window.muse.ask(...)` 请求并返回供前端 Promise 解析的 JSON 文本。 */
    suspend fun handleAsk(cardId: String?, payloadJson: String): String {
        val payload = parseAskPayload(payloadJson)
            ?: return errorJson("invalid_payload", "请求体不是有效 JSON")
        if (payload.prompt.isEmpty()) return errorJson("empty_prompt", "缺少 prompt")
        return executeAsk(cardId, payload)
    }

    private fun parseAskPayload(payloadJson: String): AskPayload? {
        val parsed = runCatching { AppJson.parseToJsonElement(payloadJson).jsonObject }.getOrNull() ?: return null
        return AskPayload(
            prompt = parsed["prompt"]?.jsonPrimitive?.contentOrNull.orEmpty()
                .trim().take(MAX_PROMPT_CHARS),
            responseFormat = parsed["responseFormat"]?.jsonPrimitive?.contentOrNull
                ?.trim()?.lowercase()?.ifBlank { FORMAT_JSON } ?: FORMAT_JSON,
            assistantId = parsed["assistantId"]?.jsonPrimitive?.contentOrNull?.trim()
                ?.takeIf { it.isNotBlank() } ?: DEFAULT_ASSISTANT_ID,
            stateJson = parsed["state"]?.toString()?.take(MAX_STATE_CHARS),
        )
    }

    private suspend fun executeAsk(cardId: String?, payload: AskPayload): String {
        val lockKey = cardId?.takeIf { it.isNotBlank() } ?: DEFAULT_LOCK_KEY
        val cardLock = inFlightByCard.getOrPut(lockKey) { AtomicBoolean(false) }
        val result = if (!cardLock.compareAndSet(false, true)) {
            errorJson("busy", "卡片正在等待上一次回应")
        } else {
            try {
                when {
                    !allowByRateLimit(lockKey) -> errorJson("rate_limited", "请求过于频繁，请稍后再试")
                    chatService == null -> errorJson("service_unavailable", "模型服务暂不可用")
                    else -> when (val completion = completeAsk(lockKey, payload)) {
                        is AskCompletion.Failure -> completion.json
                        is AskCompletion.Reply -> buildAskReply(cardId, payload, completion.text)
                    }
                }
            } finally {
                cardLock.set(false)
            }
        }
        return result
    }

    private suspend fun completeAsk(lockKey: String, payload: AskPayload): AskCompletion {
        val persona = resolveBriefPersona(payload.assistantId)
        val systemPrompt = buildSystemPrompt(persona, payload.responseFormat)
        val userPrompt = buildAskUserPrompt(payload)
        return try {
            val text = withTimeout(REQUEST_TIMEOUT_MS) {
                chatService?.completeText(
                    messages = listOf(
                        UIMessage(role = MessageRole.SYSTEM, content = systemPrompt),
                        UIMessage(role = MessageRole.USER, content = userPrompt),
                    ),
                    temperature = 0.5f,
                    maxTokens = 800,
                    mode = ChatRequestMode.UTILITY,
                )?.text?.trim().orEmpty()
            }
            AskCompletion.Reply(text)
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            Logger.w(TAG, "卡片静默调用超时 (card=$lockKey)", e)
            AskCompletion.Failure(errorJson("timeout", "助手响应超时，请重试"))
        } catch (e: CancellationException) {
            throw e
        } catch (e: RuntimeException) {
            Logger.w(TAG, "卡片静默调用失败 (card=$lockKey): ${e.message}", e)
            AskCompletion.Failure(errorJson("model_error", e.message ?: e.javaClass.simpleName))
        }
    }

    private fun buildAskUserPrompt(payload: AskPayload): String = buildString {
        if (!payload.stateJson.isNullOrBlank()) {
            appendLine("当前卡片状态 JSON：")
            appendLine(payload.stateJson)
            appendLine()
        }
        append("请求：")
        append(payload.prompt)
    }

    private suspend fun buildAskReply(cardId: String?, payload: AskPayload, rawReply: String): String {
        val extractedJson = payload.responseFormat.takeIf { it == FORMAT_JSON }?.let(::extractJsonObject)
        val returnedState = extractedJson?.get("state")?.toString()
        if (!returnedState.isNullOrBlank() && !cardId.isNullOrBlank()) {
            saveCardState(cardId, returnedState)
        }
        return buildJsonObject {
            put("ok", true)
            put("text", rawReply)
            if (extractedJson != null) put("json", extractedJson)
            if (returnedState != null) extractedJson?.get("state")?.let { put("state", it) }
        }.toString()
    }

    /** 静默保存卡片状态到持久化存储，不发送对话消息。 */
    suspend fun saveCardState(cardId: String?, stateJson: String): Boolean {
        val safeId = cardId?.trim()?.takeIf { it.isNotBlank() } ?: return false
        val safeJson = stateJson.trim().take(MAX_STATE_CHARS)
        if (safeJson.isEmpty()) return false
        return stateSaveMutex.withLock {
            withContext(Dispatchers.IO) {
                val existing = resultOf { cardEntityDao?.getById(safeId) }
                    .onError { _, t -> Logger.w(TAG, "读取 Spaces 卡片状态失败 ($safeId)", t) }
                    .getOrNull()
                val existingJson = cardDataStore?.get(safeId) ?: existing?.dataJson
                if (hasStaleGomokuRevision(safeJson, existingJson)) return@withContext false
                var updated = false
                cardDataStore?.let {
                    it.put(safeId, safeJson)
                    updated = true
                }
                if (existing != null) {
                    resultOf { cardEntityDao?.upsert(existing.copy(dataJson = safeJson, updatedAt = clock())) }
                        .onSuccess { updated = true }
                        .onError { _, t -> Logger.w(TAG, "更新 Spaces 卡片数据失败 ($safeId)", t) }
                }
                updated
            }
        }
    }

    private fun hasStaleGomokuRevision(candidateJson: String, currentJson: String?): Boolean {
        if (currentJson.isNullOrBlank()) return false
        val candidateRevision = gomokuRevision(candidateJson) ?: return false
        val currentRevision = gomokuRevision(currentJson) ?: return false
        return candidateRevision <= currentRevision
    }

    private fun gomokuRevision(json: String): Long? = runCatching {
        AppJson.parseToJsonElement(json).jsonObject["__museGomokuRevision"]
            ?.jsonPrimitive?.contentOrNull?.toLongOrNull()
    }.getOrNull()

    private fun allowByRateLimit(lockKey: String): Boolean {
        val now = clock()
        val bucket = rateWindowByCard.getOrPut(lockKey) { mutableListOf() }
        synchronized(bucket) {
            bucket.removeAll { ts -> now - ts > RATE_WINDOW_MS }
            if (bucket.size >= MAX_CALLS_PER_WINDOW) return false
            bucket.add(now)
            return true
        }
    }

    private suspend fun resolveBriefPersona(assistantId: String): String {
        val repo = assistantRepository ?: return ""
        val assistant = runCatching {
            repo.getById(assistantId) ?: repo.getAll().firstOrNull()
        }.getOrNull() ?: return ""
        val name = assistant.name.trim()
        val styleSnippet = assistant.systemPrompt.trim()
            .lineSequence()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .joinToString(" ")
            .take(180)
        return buildString {
            if (name.isNotBlank()) append("你的名字是「$name」。")
            if (styleSnippet.isNotBlank()) append(styleSnippet)
        }
    }

    private fun buildSystemPrompt(persona: String, responseFormat: String): String = buildString {
        appendLine("你正在交互卡片内部与用户进行轻量同步互动（如棋类对弈、答题、沙盒模拟），本轮交互不会进入聊天记录。")
        if (persona.isNotBlank()) {
            append("人设风格：")
            appendLine(persona)
        }
        if (responseFormat == FORMAT_JSON) {
            append("只输出一个合法 JSON 对象，不要 markdown 代码块或多余解释；若请求要求给出坐标/动作/短评或更新后的 state，直接放在 JSON 字段中。")
        } else {
            append("直接输出精炼中文回应，不超过 120 字。")
        }
    }

    internal fun extractJsonObject(raw: String): JsonObject? {
        val trimmed = raw.trim()
        val start = trimmed.indexOf('{')
        val end = trimmed.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching {
            AppJson.parseToJsonElement(trimmed.substring(start, end + 1)) as? JsonObject
        }.getOrNull()
    }

    private fun errorJson(code: String, message: String): String = buildJsonObject {
        put("ok", false)
        put("error", JsonPrimitive(code))
        put("message", JsonPrimitive(message.take(200)))
    }.toString()

    companion object {
        private const val TAG = "CardSilentAi"
        private const val DEFAULT_ASSISTANT_ID = "default"
        private const val DEFAULT_LOCK_KEY = "_anonymous_card"
        private const val FORMAT_JSON = "json"
        private const val MAX_PROMPT_CHARS = 2_000
        private const val MAX_STATE_CHARS = 16_000
        private const val REQUEST_TIMEOUT_MS = 30_000L
        private const val RATE_WINDOW_MS = 60_000L
        private const val MAX_CALLS_PER_WINDOW = 20
    }
}
