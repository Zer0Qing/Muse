package io.zer0.muse.ui.translate

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.zer0.ai.ChatService
import io.zer0.ai.core.ChatCompletion
import io.zer0.ai.core.ChatStreamEvent
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.ProviderError
import io.zer0.ai.core.ProviderException
import io.zer0.ai.core.UIMessage
import io.zer0.ai.core.providerError
import io.zer0.common.Logger
import io.zer0.muse.R
import io.zer0.muse.ui.speech.TtsManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * v1.97 gap8: 独立翻译页 ViewModel。
 *
 * 复用 [ChatService] 的通用文本补全能力,不依赖会话/消息持久化。
 * 翻译策略与 [io.zer0.muse.ui.ChatViewModel.translateMessage] 一致:
 *  1. 优先 [ChatService.completeText](一次性,速度快)
 *  2. 若 Provider 未实现 completeText 或出错,降级 [ChatService.streamChat](流式,实时更新译文)
 *
 * v1.0.30 gap4.3 ~ gap4.9 增强集:
 *  - gap4.3 收藏夹:历史项可加星,DAO 提供 observeFavorites/setFavorite
 *  - gap4.4 批量翻译:一次 LLM 调用翻译多段文本(JSON 数组返回)
 *  - gap4.5 自定义风格:SharedPreferences 持久化用户风格,与默认风格并列展示
 *  - gap4.6 术语表:[GlossaryStore] 维护原文→译文映射,翻译时附加到 prompt
 *  - gap4.8 离线缓存:LruCache(50) 缓存最近翻译,命中直接返回避免 LLM 调用
 */
class TranslateViewModel(
    private val chatService: ChatService,
    private val ttsManager: TtsManager,
    private val appContext: Context,
    private val translateHistoryDao: TranslateHistoryDao,
    private val glossaryStore: GlossaryStore,
) : ViewModel() {

    /** UI 状态。 */
    data class State(
        /** 用户输入的原文。 */
        val inputText: String = "",
        /** 翻译后的译文。 */
        val translatedText: String = "",
        /** 是否正在翻译中。 */
        val translating: Boolean = false,
        /** 源语言(中文名),"自动检测"表示由模型判断。 */
        val sourceLanguage: String = SOURCE_LANGUAGES.first(),
        /** 目标语言(中文名)。 */
        val targetLanguage: String = TARGET_LANGUAGES.first(),
        /** v1.0.30: 翻译风格(通用/学术/商务/口语化/润色 + 用户自定义)。 */
        val translationStyle: String = TRANSLATION_STYLES.first(),
        /** 错误消息(null 表示无错误)。 */
        val errorMessage: String? = null,
        /** v1.97: 翻译历史记录(最近 N 条,v1.0.17 起持久化到 Room)。 */
        val history: List<TranslateHistoryItem> = emptyList(),
        /** v1.0.30 gap4.3: 收藏的翻译历史(由 DAO Flow 自动更新)。 */
        val favorites: List<TranslateHistoryItem> = emptyList(),
        /** v1.0.30 gap4.5: 用户自定义风格列表(从 SharedPreferences 加载)。 */
        val customStyles: List<CustomStyle> = emptyList(),
        /** v1.0.30 gap4.4: 批量翻译结果(逐条对应输入)。 */
        val batchResults: List<BatchResult> = emptyList(),
        /** v1.0.30 gap4.4: 是否正在批量翻译。 */
        val batchTranslating: Boolean = false,
        /** v1.0.30 gap4.6: 术语表(原文 → 译文),由 UI 触发刷新后填充。 */
        val glossary: Map<String, String> = emptyMap(),
    )

    /** v1.97: 翻译历史记录项。 */
    data class TranslateHistoryItem(
        val id: String,
        val sourceText: String,
        val translatedText: String,
        val sourceLanguage: String,
        val targetLanguage: String,
        val style: String = "通用",
        val timestamp: Long = System.currentTimeMillis(),
        /** v1.0.30 gap4.3: 是否已收藏。 */
        val favorite: Boolean = false,
    )

    /** v1.0.30 gap4.5: 自定义翻译风格。 */
    data class CustomStyle(
        val name: String,
        /** 附加到 prompt 的指令片段(如"使用古风文言文翻译")。 */
        val prompt: String,
    )

    /** v1.0.30 gap4.4: 批量翻译单条结果。 */
    data class BatchResult(
        val original: String,
        val translated: String,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** v1.0.30 gap4.8: LRU 离线缓存,key = "${sourceText}_${targetLanguage}_${style}"。 */
    private val translateCache = android.util.LruCache<String, String>(50)

    /** v1.0.30 gap4.5: 自定义风格 SharedPreferences。 */
    private val stylePrefs by lazy {
        appContext.getSharedPreferences(PREFS_CUSTOM_STYLES, Context.MODE_PRIVATE)
    }

    init {
        // v1.0.17: 从 Room 加载翻译历史,数据源改为 DAO Flow(进程被杀后历史不丢失)
        viewModelScope.launch {
            translateHistoryDao.observeRecent(MAX_HISTORY).map { entities ->
                entities.map { it.toHistoryItem() }
            }.collect { items ->
                // v1.0.30 gap4.8: 加载历史时填充 LRU 缓存,后续重复翻译直接命中
                items.forEach { item ->
                    val key = buildCacheKey(item.sourceText, item.targetLanguage, item.style)
                    if (translateCache.get(key) == null) {
                        translateCache.put(key, item.translatedText)
                    }
                }
                _state.update { it.copy(history = items) }
            }
        }
        // v1.0.30 gap4.3: 收藏夹 Flow
        viewModelScope.launch {
            translateHistoryDao.observeFavorites().map { entities ->
                entities.map { it.toHistoryItem() }
            }.collect { items ->
                _state.update { it.copy(favorites = items) }
            }
        }
        // v1.0.30 gap4.5: 启动时加载自定义风格
        loadCustomStyles()
        // v1.0.30 gap4.6: 启动时刷新术语表到 state(UI 据此显示数量)
        refreshGlossary()
    }

    /** 当前翻译协程,支持取消。 */
    private var translateJob: Job? = null

    /** v1.0.30 gap4.4: 批量翻译协程,支持取消。 */
    private var batchJob: Job? = null

    /** v1.0.17: Entity → UI 数据模型映射。 */
    private fun TranslateHistoryEntity.toHistoryItem(): TranslateHistoryItem =
        TranslateHistoryItem(
            id = id,
            sourceText = sourceText,
            translatedText = translatedText,
            sourceLanguage = sourceLanguage,
            targetLanguage = targetLanguage,
            style = style,
            timestamp = createdAt,
            favorite = favorite,
        )

    /** 更新输入文本。 */
    fun updateInput(text: String) {
        _state.update { it.copy(inputText = text) }
    }

    /** 更新目标语言。 */
    fun updateTargetLanguage(language: String) {
        _state.update { it.copy(targetLanguage = language) }
    }

    /** 更新源语言。 */
    fun updateSourceLanguage(language: String) {
        _state.update { it.copy(sourceLanguage = language) }
    }

    /** v1.0.30: 更新翻译风格。 */
    fun updateTranslationStyle(style: String) {
        _state.update { it.copy(translationStyle = style) }
    }

    /** 交换源语言与目标语言,并将当前译文回填到输入框(便于继续翻译)。 */
    fun swapLanguages() {
        val current = _state.value
        translateJob?.cancel()
        _state.update {
            it.copy(
                sourceLanguage = current.targetLanguage,
                targetLanguage = if (current.sourceLanguage == SOURCE_AUTO) TARGET_LANGUAGES.first() else current.sourceLanguage,
                inputText = current.translatedText,
                translatedText = "",
                translating = false,
                errorMessage = null,
            )
        }
    }

    /** 清空输入和译文。 */
    fun clear() {
        translateJob?.cancel()
        _state.update {
            it.copy(
                inputText = "",
                translatedText = "",
                translating = false,
                errorMessage = null,
            )
        }
    }

    /** v1.97: 加载历史记录项到输入框(重新翻译或查看)。 */
    fun loadHistoryItem(item: TranslateHistoryItem) {
        translateJob?.cancel()
        _state.update {
            it.copy(
                inputText = item.sourceText,
                translatedText = item.translatedText,
                sourceLanguage = item.sourceLanguage,
                targetLanguage = item.targetLanguage,
                translating = false,
                errorMessage = null,
            )
        }
    }

    /** v1.97: 清空翻译历史。 */
    fun clearHistory() {
        // v1.0.17: 改为持久化删除,DAO Flow 会自动更新 state.history 为空列表
        viewModelScope.launch {
            translateHistoryDao.deleteAll()
        }
    }

    /**
     * 粘贴剪贴板文本到输入框。
     * @param clipText 剪贴板文本(由 UI 层从 ClipboardManager 获取)
     * @return true 表示粘贴成功,false 表示剪贴板为空
     */
    fun paste(clipText: String?): Boolean {
        if (clipText.isNullOrBlank()) return false
        _state.update { it.copy(inputText = clipText) }
        return true
    }

    /** 将译文回填到输入框(用于"翻译后再翻译"场景)。 */
    fun swapResultToInput() {
        val result = _state.value.translatedText
        if (result.isBlank()) return
        translateJob?.cancel()
        _state.update {
            it.copy(
                inputText = result,
                translatedText = "",
                translating = false,
                errorMessage = null,
            )
        }
    }

    /**
     * 执行翻译。
     *
     * 策略:completeText 优先 → streamChat 兜底。
     * 流式收集时实时更新 translatedText,让用户看到逐字输出。
     * v1.0.30 gap4.8: 翻译前先查 LRU 缓存,命中直接返回不调 LLM。
     * v1.0.30 gap4.6: 术语表非空时附加到 prompt 指令。
     */
    fun translate() {
        val current = _state.value
        if (current.translating) return
        if (current.inputText.isBlank()) return

        // gap4.8: 缓存命中直接返回
        val cacheKey = buildCacheKey(current.inputText, current.targetLanguage, current.translationStyle)
        val cached = translateCache.get(cacheKey)
        if (cached != null) {
            _state.update {
                it.copy(
                    translating = false,
                    translatedText = cached,
                    errorMessage = null,
                )
            }
            return
        }

        translateJob?.cancel()
        _state.update {
            it.copy(
                translating = true,
                translatedText = "",
                errorMessage = null,
            )
        }

        translateJob = viewModelScope.launch {
            try {
                val prompt = buildTranslationPrompt(
                    text = current.inputText,
                    targetLanguage = current.targetLanguage,
                    sourceLanguage = current.sourceLanguage,
                    style = current.translationStyle,
                    customStyles = current.customStyles,
                    glossary = current.glossary,
                )
                val messages = listOf(UIMessage(role = MessageRole.USER, content = prompt))

                // 优先 completeText(一次性返回,速度快)
                val translated: String = try {
                    val completion: ChatCompletion = chatService.completeText(messages = messages)
                    // v1.99: 剥离推理模型内嵌的  标签,只保留纯净译文
                    io.zer0.muse.transformer.stripThinkTags(completion.text).trim()
                } catch (e: UnsupportedOperationException) {
                    // Provider 未实现 completeText,降级流式
                    collectStream(messages)
                } catch (e: Exception) {
                    // 其他错误也降级流式(网络抖动等)
                    Logger.w(TAG, "completeText failed, fallback to streamChat", e)
                    collectStream(messages)
                }

                if (translated.isEmpty()) {
                    _state.update {
                        it.copy(
                            translating = false,
                            errorMessage = appContext.getString(R.string.err_chat_translate_empty),
                        )
                    }
                } else {
                    // gap4.8: 写入 LRU 缓存
                    translateCache.put(cacheKey, translated)
                    // v1.0.17: 翻译成功,持久化到 Room(历史列表由 DAO Flow 自动更新)
                    val entity = TranslateHistoryEntity(
                        id = java.util.UUID.randomUUID().toString(),
                        sourceText = current.inputText,
                        translatedText = translated,
                        sourceLanguage = current.sourceLanguage,
                        targetLanguage = current.targetLanguage,
                        style = current.translationStyle,
                    )
                    translateHistoryDao.insert(entity)
                    _state.update {
                        it.copy(
                            translating = false,
                            translatedText = translated,
                        )
                    }
                }
            } catch (e: CancellationException) {
                // 协程取消,不更新状态(cancelTranslation 已处理)
                throw e
            } catch (t: Exception) {
                Logger.e(TAG, "translate failed", t)
                _state.update {
                    it.copy(
                        translating = false,
                        errorMessage = appContext.getString(R.string.err_chat_translate_failed, t.message ?: appContext.getString(R.string.err_chat_unknown)),
                    )
                }
            }
        }
    }

    /** 取消当前翻译。 */
    fun cancelTranslation() {
        translateJob?.cancel()
        _state.update { it.copy(translating = false) }
    }

    /** 消费错误消息(UI 层显示后调用,清除 errorMessage)。 */
    fun consumeError() {
        _state.update { it.copy(errorMessage = null) }
    }

    /** 朗读当前原文。 */
    fun speakSource(): Boolean {
        val text = _state.value.inputText.trim()
        if (text.isBlank()) return false
        return ttsManager.speak(text, utteranceId = "translate_source_${System.currentTimeMillis()}")
    }

    /** 朗读当前译文。 */
    fun speakTranslated(): Boolean {
        val text = _state.value.translatedText.trim()
        if (text.isBlank()) return false
        return ttsManager.speak(text, utteranceId = "translate_result_${System.currentTimeMillis()}")
    }

    /** 停止朗读。 */
    fun stopSpeaking() {
        ttsManager.stop()
    }

    /**
     * v1.0.30 gap4.3: 切换某条历史的收藏状态。
     */
    fun toggleFavorite(item: TranslateHistoryItem) {
        viewModelScope.launch {
            translateHistoryDao.setFavorite(item.id, !item.favorite)
            // 缓存无需变动(收藏状态与译文无关)
        }
    }

    /**
     * v1.0.30 gap4.4: 批量翻译。
     *
     * 用一次 LLM 调用翻译多段文本,prompt 要求模型返回 JSON 数组格式
     * `[{"original":"...","translated":"..."}]`,解析后逐条保存到历史。
     *
     * @param texts 待翻译的文本列表(每条独立翻译)
     * @param targetLanguage 目标语言中文名
     */
    fun translateBatch(texts: List<String>, targetLanguage: String) {
        if (_state.value.batchTranslating) return
        val valid = texts.map { it.trim() }.filter { it.isNotEmpty() }
        if (valid.isEmpty()) return

        batchJob?.cancel()
        _state.update {
            it.copy(
                batchTranslating = true,
                batchResults = emptyList(),
                errorMessage = null,
            )
        }

        batchJob = viewModelScope.launch {
            try {
                val style = _state.value.translationStyle
                val sourceLang = _state.value.sourceLanguage
                val prompt = buildBatchPrompt(valid, targetLanguage, sourceLang, style)
                val messages = listOf(UIMessage(role = MessageRole.USER, content = prompt))

                val raw: String = try {
                    val completion = chatService.completeText(messages = messages)
                    io.zer0.muse.transformer.stripThinkTags(completion.text).trim()
                } catch (e: UnsupportedOperationException) {
                    collectStream(messages)
                } catch (e: Exception) {
                    Logger.w(TAG, "batch completeText failed, fallback to streamChat", e)
                    collectStream(messages)
                }

                val results = parseBatchResponse(raw, valid)
                if (results.isEmpty()) {
                    _state.update {
                        it.copy(
                            batchTranslating = false,
                            errorMessage = appContext.getString(R.string.err_chat_translate_empty),
                        )
                    }
                    return@launch
                }

                // 逐条写入历史 + LRU 缓存
                results.forEach { r ->
                    val entity = TranslateHistoryEntity(
                        id = java.util.UUID.randomUUID().toString(),
                        sourceText = r.original,
                        translatedText = r.translated,
                        sourceLanguage = sourceLang,
                        targetLanguage = targetLanguage,
                        style = style,
                    )
                    translateHistoryDao.insert(entity)
                    val key = buildCacheKey(r.original, targetLanguage, style)
                    translateCache.put(key, r.translated)
                }
                _state.update {
                    it.copy(
                        batchTranslating = false,
                        batchResults = results,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Exception) {
                Logger.e(TAG, "batch translate failed", t)
                _state.update {
                    it.copy(
                        batchTranslating = false,
                        errorMessage = appContext.getString(R.string.err_chat_translate_failed, t.message ?: appContext.getString(R.string.err_chat_unknown)),
                    )
                }
            }
        }
    }

    /** v1.0.30 gap4.4: 取消批量翻译。 */
    fun cancelBatchTranslation() {
        batchJob?.cancel()
        _state.update { it.copy(batchTranslating = false) }
    }

    /** v1.0.30 gap4.4: 清空批量翻译结果(UI 关闭对话框时调用)。 */
    fun clearBatchResults() {
        _state.update { it.copy(batchResults = emptyList()) }
    }

    // ── gap4.5 自定义风格管理 ──

    /**
     * 添加自定义风格;同名存在则覆盖。
     * 持久化到 SharedPreferences(JSON 数组:[{"name":"...","prompt":"..."}])。
     */
    fun addCustomStyle(name: String, prompt: String) {
        val trimmedName = name.trim()
        val trimmedPrompt = prompt.trim()
        if (trimmedName.isEmpty()) return
        val current = loadCustomStylesFromPrefs().toMutableList()
        current.removeAll { it.name == trimmedName }
        current.add(CustomStyle(trimmedName, trimmedPrompt))
        saveCustomStylesToPrefs(current)
        _state.update { it.copy(customStyles = current) }
    }

    /** 删除自定义风格;返回是否实际删除。 */
    fun removeCustomStyle(name: String): Boolean {
        val current = loadCustomStylesFromPrefs().toMutableList()
        val removed = current.removeAll { it.name == name }
        if (removed) {
            saveCustomStylesToPrefs(current)
            _state.update { it.copy(customStyles = current) }
        }
        return removed
    }

    /** 从 SharedPreferences 加载自定义风格到 state。 */
    private fun loadCustomStyles() {
        _state.update { it.copy(customStyles = loadCustomStylesFromPrefs()) }
    }

    private fun loadCustomStylesFromPrefs(): List<CustomStyle> {
        val json = stylePrefs.getString(KEY_CUSTOM_STYLES, null) ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.optJSONObject(i) ?: return@mapNotNull null
                CustomStyle(
                    name = obj.optString("name"),
                    prompt = obj.optString("prompt"),
                )
            }.filter { it.name.isNotEmpty() }
        } catch (e: Exception) {
            Logger.w(TAG, "解析自定义风格失败: ${e.message}")
            emptyList()
        }
    }

    private fun saveCustomStylesToPrefs(list: List<CustomStyle>) {
        val arr = JSONArray()
        list.forEach { s ->
            arr.put(JSONObject().apply {
                put("name", s.name)
                put("prompt", s.prompt)
            })
        }
        stylePrefs.edit().putString(KEY_CUSTOM_STYLES, arr.toString()).apply()
    }

    // ── gap4.6 术语表 ──

    /** 添加术语映射到 [GlossaryStore]。 */
    fun addGlossaryEntry(original: String, translated: String) {
        glossaryStore.add(original, translated)
        refreshGlossary()
    }

    /** 删除术语映射;返回是否实际删除。 */
    fun removeGlossaryEntry(original: String): Boolean {
        val removed = glossaryStore.remove(original)
        if (removed) refreshGlossary()
        return removed
    }

    /** 从 [GlossaryStore] 刷新术语表到 state。 */
    fun refreshGlossary() {
        _state.update { it.copy(glossary = glossaryStore.list()) }
    }

    // ── gap4.7 OCR 翻译 ──

    /**
     * v1.0.30 gap4.7: 将 OCR 识别结果填入原文输入框。
     *
     * 由 UI 层调用 [io.zer0.muse.doc.OcrManager.recognize] 得到识别文本后传入。
     */
    fun applyOcrText(text: String) {
        if (text.isBlank()) return
        val current = _state.value.inputText
        val merged = if (current.isBlank()) text else "$current\n---\n$text"
        _state.update { it.copy(inputText = merged) }
    }

    /**
     * 流式收集翻译结果,实时更新 translatedText。
     */
    private suspend fun collectStream(messages: List<UIMessage>): String {
        val sb = StringBuilder()
        chatService.streamChat(messages = messages).collect { event ->
            when (event) {
                is ChatStreamEvent.ContentDelta -> {
                    sb.append(event.delta)
                    _state.update { it.copy(translatedText = sb.toString()) }
                }
                is ChatStreamEvent.Error -> {
                    throw ProviderException(
                        providerError = event.providerError ?: ProviderError.Unknown(displayMessage = event.message),
                        cause = event.throwable,
                    )
                }
                is ChatStreamEvent.Done -> {
                    // 流结束,返回收集到的完整文本
                }
                else -> {
                    // 忽略 ReasoningDelta / ToolCallDelta / ImageDelta
                }
            }
        }
        return io.zer0.muse.transformer.stripThinkTags(sb.toString()).trim()
    }

    /** v1.0.30 gap4.8: 构建 LRU 缓存 key。 */
    private fun buildCacheKey(sourceText: String, targetLanguage: String, style: String): String =
        "${sourceText.hashCode()}_${targetLanguage}_$style"

    companion object {
        private const val TAG = "TranslateVM"
        /** v1.104: 翻译历史上限(之前硬编码 take(20),抽出为常量便于调整)。 */
        private const val MAX_HISTORY = 50

        /** v1.0.30 gap4.5: 自定义风格 SharedPreferences 文件名与 key。 */
        private const val PREFS_CUSTOM_STYLES = "translate_custom_styles"
        private const val KEY_CUSTOM_STYLES = "custom_styles_json"

        /** 自动检测源语言占位值。 */
        const val SOURCE_AUTO = "自动检测"

        /** 支持的源语言列表(中文名,首项为自动检测)。 */
        val SOURCE_LANGUAGES: List<String> = listOf(
            SOURCE_AUTO, "中文", "English", "日本語", "한국어",
            "Français", "Deutsch", "Español", "Русский",
            "العربية", "Português",
        )

        /** 支持的目标语言列表(中文名,与 ChatViewModel.translateMessage 一致)。 */
        val TARGET_LANGUAGES: List<String> = listOf(
            "中文", "English", "日本語", "한국어",
            "Français", "Deutsch", "Español", "Русский",
            "العربية", "Português",
        )

        /** v1.0.30: 支持的翻译风格。 */
        val TRANSLATION_STYLES: List<String> = listOf(
            "通用", "学术", "商务", "口语化", "润色", "简洁"
        )

        /**
         * 构建翻译 prompt。
         *
         * v1.0.30 gap4.5: 支持自定义风格(customStyles 中 name 命中时附加其 prompt 指令)。
         * v1.0.30 gap4.6: 术语表非空时附加"请参考以下术语表进行翻译: A→B, C→D..."指令。
         *
         * @param sourceLanguage 源语言,自动检测时让模型自行判断
         * @param style 翻译风格,影响语气与用词
         * @param customStyles 用户自定义风格列表(命中 name 时附加其 prompt)
         * @param glossary 术语表(原文 → 译文),非空时附加到 prompt
         */
        fun buildTranslationPrompt(
            text: String,
            targetLanguage: String,
            sourceLanguage: String = SOURCE_AUTO,
            style: String = TRANSLATION_STYLES.first(),
            customStyles: List<CustomStyle> = emptyList(),
            glossary: Map<String, String> = emptyMap(),
        ): String = buildString {
            if (sourceLanguage == SOURCE_AUTO) {
                appendLine("你是一个专业翻译助手。请自动识别下面文本的语言,并将其翻译为$targetLanguage。")
            } else {
                appendLine("你是一个专业翻译助手。请将下面的文本从$sourceLanguage 翻译为$targetLanguage。")
            }
            appendLine("- 只输出译文,不要加解释、前缀或注释")
            appendLine("- 保留原文的格式(换行/列表/代码块等)")
            appendLine("- 如果原文已经是$targetLanguage,原样输出")
            // 自定义风格优先:命中自定义风格时使用其 prompt 指令
            val custom = customStyles.firstOrNull { it.name == style }
            if (custom != null && custom.prompt.isNotEmpty()) {
                appendLine("- ${custom.prompt}")
            } else {
                when (style) {
                    "学术" -> appendLine("- 使用学术、正式、严谨的表达方式")
                    "商务" -> appendLine("- 使用商务、专业、礼貌的表达方式")
                    "口语化" -> appendLine("- 使用自然、口语化、贴近日常对话的表达方式")
                    "润色" -> appendLine("- 在忠实原意的基础上润色译文,使其更流畅优美")
                    "简洁" -> appendLine("- 尽量简洁,去除冗余表达,保留核心信息")
                    else -> appendLine("- 使用通用、准确、自然的表达方式")
                }
            }
            // gap4.6: 术语表
            if (glossary.isNotEmpty()) {
                val snippet = glossary.entries.joinToString(", ") { "${it.key}→${it.value}" }
                appendLine("- 请参考以下术语表进行翻译: $snippet")
            }
            appendLine()
            appendLine("原文:")
            appendLine(text)
        }

        /**
         * v1.0.30 gap4.4: 构建批量翻译 prompt。
         *
         * 要求模型返回 JSON 数组 `[{"original":"原文1","translated":"译文1"},...]`,
         * 顺序与输入保持一致,便于解析后逐条对应。
         */
        fun buildBatchPrompt(
            texts: List<String>,
            targetLanguage: String,
            sourceLanguage: String,
            style: String,
        ): String = buildString {
            if (sourceLanguage == SOURCE_AUTO) {
                appendLine("你是一个专业翻译助手。请自动识别每段文本的语言,并将其翻译为$targetLanguage。")
            } else {
                appendLine("你是一个专业翻译助手。请将下面的每段文本从$sourceLanguage 翻译为$targetLanguage。")
            }
            appendLine("- 严格输出 JSON 数组格式: [{\"original\":\"原文\",\"translated\":\"译文\"},...]")
            appendLine("- 数组顺序与输入顺序一一对应")
            appendLine("- original 字段保留原文,translated 字段为对应译文")
            appendLine("- 不要输出 JSON 之外的任何内容(不要 markdown 代码块)")
            appendLine("- 如果某段已经是$targetLanguage,translated 与 original 相同")
            when (style) {
                "学术" -> appendLine("- 使用学术、正式、严谨的表达方式")
                "商务" -> appendLine("- 使用商务、专业、礼貌的表达方式")
                "口语化" -> appendLine("- 使用自然、口语化、贴近日常对话的表达方式")
                "润色" -> appendLine("- 在忠实原意的基础上润色译文,使其更流畅优美")
                "简洁" -> appendLine("- 尽量简洁,去除冗余表达,保留核心信息")
                else -> appendLine("- 使用通用、准确、自然的表达方式")
            }
            appendLine()
            appendLine("待翻译文本(共 ${texts.size} 段):")
            texts.forEachIndexed { idx, t ->
                appendLine("[${idx + 1}] $t")
            }
        }

        /**
         * v1.0.30 gap4.4: 解析批量翻译响应。
         *
         * 模型可能返回 markdown 代码块包裹的 JSON,先剥离再解析。
         * 解析失败时返回空列表(调用方降级为错误提示)。
         */
        fun parseBatchResponse(raw: String, originals: List<String>): List<BatchResult> {
            val cleaned = raw
                .trim()
                .removePrefix("```json")
                .removePrefix("```")
                .removeSuffix("```")
                .trim()
            if (cleaned.isEmpty() || !cleaned.startsWith("[")) return emptyList()
            return try {
                val arr = JSONArray(cleaned)
                (0 until arr.length()).mapNotNull { i ->
                    val obj = arr.optJSONObject(i) ?: return@mapNotNull null
                    val original = obj.optString("original")
                    val translated = obj.optString("translated")
                    if (translated.isEmpty()) return@mapNotNull null
                    BatchResult(original = original, translated = translated)
                }
            } catch (e: Exception) {
                Logger.w(TAG, "解析批量翻译响应失败: ${e.message}")
                // 兜底:按行尝试映射(模型未返回 JSON 数组时)
                val lines = raw.split("\n").filter { it.isNotBlank() }
                if (lines.size == originals.size) {
                    lines.mapIndexedNotNull { idx, line ->
                        val translated = line.trim().removePrefix("```").trim()
                        if (translated.isEmpty() || idx >= originals.size) null
                        else BatchResult(originals[idx], translated)
                    }
                } else {
                    emptyList()
                }
            }
        }
    }
}
