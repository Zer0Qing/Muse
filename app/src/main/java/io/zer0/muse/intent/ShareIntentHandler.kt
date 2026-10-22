package io.zer0.muse.intent

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.data.assistant.AssistantCardExporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Phase 8.10: Deep Link + 系统分享接收处理器。
 *
 * 支持两类 Intent:
 *
 * **深度链接**(`muse://` 协议):
 *  - `muse://session/{sessionId}` → 打开指定会话
 *  - `muse://new` → 新建会话
 *  - `muse://assistants` → 打开 Assistant 管理页
 *  - `muse://settings` → 打开设置页
 *
 * **系统分享**(ACTION_SEND / ACTION_PROCESS_TEXT):
 *  - 其他 App 调"分享到…"→ muse 接收文本,预填到输入框
 *  - 长按选中文本 → "在 muse 中发送" → 同上
 *  - 支持 EXTRA_TEXT(纯文本) + EXTRA_STREAM(文本类 URI,简化处理)
 *
 * 设计:
 *  - 纯解析层,不依赖 ViewModel;返回 [ShareResult] 由调用方(ChatScreen/ChatViewModel)消费
 *  - Deep Link 路径分段用 `Uri.host` + `Uri.pathSegments`,不依赖正则
 *  - 分享文本最大长度 10000 字(避免超大 Intent OOM)
 *
 * @param context 用于解析 stream URI(读 InputStream)
 */
class ShareIntentHandler(private val context: Context) {

    /** 解析结果。 */
    sealed class ShareResult {
        /** 打开指定会话。 */
        data class OpenSession(val sessionId: String) : ShareResult()

        /**
         * 新建会话。
         *
         * [assistantId] 非空表示调用方(桌面小组件)指定了助手,新建时必须用它,
         * 否则用户在小组件里选的助手在点开后退回全局默认助手。
         *
         * [startVoice] 为真时新建后立即进入语音输入 —— 桌面小组件的「快速语音输入」
         * 此前只新建会话、并不开麦，名不副实。开麦逻辑复用 Launcher 快捷方式那一条。
         */
        data class NewSession(val assistantId: String? = null, val startVoice: Boolean = false) : ShareResult()

        /** 打开 Assistant 管理页。 */
        object OpenAssistants : ShareResult()

        /** 打开设置页。 */
        object OpenSettings : ShareResult()

        /** 打开会话列表(来自桌面小部件"最近会话")。 */
        object OpenChats : ShareResult()

        /** v1.64: 打开定时任务页(来自定时任务通知点击)。 */
        object OpenScheduledTasks : ShareResult()

        /** 打开指定定时任务并展开最近一次执行记录。 */
        data class OpenScheduledTask(val taskId: String) : ShareResult()

        /** 打开快速记录并展开指定笔记。 */
        data class OpenQuickNote(val noteId: String) : ShareResult()

        /** 打开聊天详情页(没有具体会话上下文的通知)。 */
        object OpenChat : ShareResult()

        /** 打开任务首页。 */
        object OpenHome : ShareResult()

        /** 打开设置-数据与备份。 */
        object OpenSettingsData : ShareResult()

        /** 打开设置-云备份。 */
        object OpenCloudBackup : ShareResult()

        /** 打开设置-外观/关于等通知目标。 */
        object OpenSettingsAbout : ShareResult()

        /** 打开设置-助手与 Agent。 */
        object OpenSettingsAgent : ShareResult()

        /** 打开设置-模型与服务。 */
        object OpenSettingsModel : ShareResult()

        /** 打开记忆中心。 */
        object OpenMemory : ShareResult()

        /** 打开知识库管理。 */
        object OpenKnowledge : ShareResult()

        /** 打开知识库集合管理。 */
        object OpenKnowledgeBases : ShareResult()

        /** v2.x: 导入角色包(.muse-assistant 文件被点开)。 */
        data class ImportAssistantCard(val uri: Uri) : ShareResult()

        /** Launcher 快捷方式:打开翻译页(来自 ACTION_TRANSLATE)。 */
        object OpenTranslate : ShareResult()

        /** Launcher 快捷方式:进入主页并触发语音输入(来自 ACTION_VOICE_INPUT)。 */
        object StartVoiceInput : ShareResult()

        /** v1.0.18: Launcher 快捷方式:打开快速记录页(来自 ACTION_QUICK_NOTES)。 */
        object OpenQuickNotes : ShareResult()

        /** 预填文本到输入框(来自分享/PROCESS_TEXT)。 */
        data class PrefillText(val text: String) : ShareResult()

        /** 无匹配 Intent。 */
        object None : ShareResult()
    }

    /**
     * 解析 Activity Intent,返回 [ShareResult]。
     * 在 [io.zer0.muse.MainActivity.onNewIntent] 和 onCreate 中调用。
     *
     * H5-1: 改为 suspend,内部文件 IO 用 withContext(Dispatchers.IO) 包裹,避免阻塞主线程。
     * 调用方需在协程中调用(如 LaunchedEffect 或 appScope.launch)。
     */
    suspend fun handle(intent: Intent?): ShareResult {
        if (intent == null) return ShareResult.None
        // 优先处理 Deep Link
        val data = intent.data
        if (data != null && data.scheme == SCHEME) {
            return parseDeepLink(data)
        }
        // v2.x: 角色包文件打开(VIEW + application/x-muse-assistant)→ 导入预览流程
        if (intent.action == Intent.ACTION_VIEW && data != null) {
            val type = intent.type ?: withContext(Dispatchers.IO) {
                resultOf { context.contentResolver.getType(data) }.getOrNull()
            }
            if (type == AssistantCardExporter.MIME_TYPE) {
                return ShareResult.ImportAssistantCard(data)
            }
        }
        // 再处理系统分享
        when (intent.action) {
            Intent.ACTION_SEND -> return parseShareSend(intent)
            Intent.ACTION_PROCESS_TEXT -> return parseProcessText(intent)
        }
        return ShareResult.None
    }

    /** 解析 muse:// deep link(目标集合有限且显式列出,便于审计每个通知入口)。 */
    @Suppress("CyclomaticComplexMethod")
    private fun parseDeepLink(uri: Uri): ShareResult {
        val host = uri.host ?: return ShareResult.None
        val segments = uri.pathSegments
        return when (host) {
            "session" -> {
                val id = segments.firstOrNull()
                // M4: sessionId 格式校验 — 防注入,只允许字母/数字/下划线/短横线,长度 1-64
                if (id != null && SESSION_ID_REGEX.matches(id)) {
                    ShareResult.OpenSession(id)
                } else {
                    if (id != null) Logger.w(TAG, "Deep link sessionId 格式非法,已忽略: $id")
                    ShareResult.None
                }
            }
            "new" -> ShareResult.NewSession()
            "assistants" -> ShareResult.OpenAssistants
            "settings" -> ShareResult.OpenSettings
            "scheduled-tasks" -> ShareResult.OpenScheduledTasks
            "scheduled-task" -> {
                val id = segments.firstOrNull()
                if (id != null && SESSION_ID_REGEX.matches(id)) {
                    ShareResult.OpenScheduledTask(id)
                } else {
                    ShareResult.None
                }
            }
            "quick-notes" -> ShareResult.OpenQuickNotes
            "quick-note" -> {
                val id = segments.firstOrNull()
                if (id != null && SESSION_ID_REGEX.matches(id)) {
                    ShareResult.OpenQuickNote(id)
                } else {
                    ShareResult.None
                }
            }
            "chat" -> ShareResult.OpenChat
            "home" -> ShareResult.OpenHome
            "settings-data" -> ShareResult.OpenSettingsData
            "cloud-backup" -> ShareResult.OpenCloudBackup
            "settings-about" -> ShareResult.OpenSettingsAbout
            "settings-agent" -> ShareResult.OpenSettingsAgent
            "settings-model" -> ShareResult.OpenSettingsModel
            "memory" -> ShareResult.OpenMemory
            "knowledge" -> ShareResult.OpenKnowledge
            "knowledge-bases" -> ShareResult.OpenKnowledgeBases
            else -> {
                Logger.w(TAG, "Unknown deep link host: $host")
                ShareResult.None
            }
        }
    }

    /** 解析 ACTION_SEND(分享文本)。 */
    private suspend fun parseShareSend(intent: Intent): ShareResult {
        // 优先 EXTRA_TEXT(纯文本分享)
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
        if (!text.isNullOrBlank()) {
            return ShareResult.PrefillText(text.take(MAX_SHARE_TEXT_LEN))
        }
        // 再尝试 EXTRA_STREAM(文本类 URI,如 .txt 文件)
        // M5-1: API 33+ 使用带类型的 getParcelableExtra,旧 API 走 deprecated 路径
        val stream = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        }
        if (stream != null) {
            // H5-1: 文件 IO 放到 Dispatchers.IO,避免阻塞主线程
            // H5-2: 限制读取量,累计不超过 MAX_SHARE_TEXT_LEN + 1 字符即停止,避免大文件 OOM
            // M5-2: 改用 resultOf{}(正确重抛 CancellationException)
            val streamText = withContext(Dispatchers.IO) {
                resultOf {
                    context.contentResolver.openInputStream(stream)?.use { input ->
                        val reader = input.bufferedReader(Charsets.UTF_8)
                        val buf = CharArray(8192)
                        val sb = StringBuilder()
                        while (sb.length <= MAX_SHARE_TEXT_LEN) {
                            val n = reader.read(buf)
                            if (n < 0) break
                            sb.append(buf, 0, n)
                        }
                        if (sb.length > MAX_SHARE_TEXT_LEN) {
                            sb.substring(0, MAX_SHARE_TEXT_LEN)
                        } else {
                            sb.toString()
                        }
                    }
                }.onError { msg, _ -> Logger.w(TAG, "读取分享流失败: $msg") }.getOrNull()
            }
            if (!streamText.isNullOrBlank()) {
                return ShareResult.PrefillText(streamText)
            }
        }
        return ShareResult.None
    }

    /** 解析 ACTION_PROCESS_TEXT(长按选中文本 → 在 muse 中发送)。 */
    private fun parseProcessText(intent: Intent): ShareResult {
        val text = intent.getStringExtra(Intent.EXTRA_PROCESS_TEXT)
        return if (text.isNullOrBlank()) {
            ShareResult.None
        } else {
            ShareResult.PrefillText(text.take(MAX_SHARE_TEXT_LEN))
        }
    }

    companion object {
        private const val TAG = "ShareIntentHandler"

        /** muse:// scheme(AndroidManifest intent-filter 需匹配)。 */
        const val SCHEME = "muse"

        /**
         * 解析桌面小组件点击带来的 Intent。
         *
         * 从 MainActivity 私有方法提出来,是为了让「小组件带过来的助手 id 有没有被读」可单测;
         * 之前这条链路只在真机点一下才能验,断了很久没人发现。
         */
        fun parseWidgetAction(intent: Intent?): ShareResult? {
            val action = intent?.getStringExtra(EXTRA_WIDGET_ACTION) ?: return null
            return when (action) {
                WIDGET_ACTION_NEW_SESSION, WIDGET_ACTION_VOICE_INPUT -> {
                    val assistantId = intent.getStringExtra(EXTRA_WIDGET_ASSISTANT_ID)
                        ?.takeIf { it.isNotBlank() }
                    ShareResult.NewSession(
                        assistantId = assistantId,
                        startVoice = action == WIDGET_ACTION_VOICE_INPUT,
                    )
                }
                WIDGET_ACTION_OPEN_CHATS -> ShareResult.OpenChats
                WIDGET_ACTION_OPEN_SESSION -> {
                    val sessionId = intent.getStringExtra(EXTRA_WIDGET_SESSION_ID)
                    if (sessionId != null) ShareResult.OpenSession(sessionId) else null
                }
                else -> null
            }
        }

        /** 分享文本最大长度(防止超大 Intent OOM)。 */
        private const val MAX_SHARE_TEXT_LEN = 10_000

        /** M4: sessionId 格式校验正则 — 防注入,只允许字母/数字/下划线/短横线,长度 1-64。 */
        private val SESSION_ID_REGEX = Regex("^[a-zA-Z0-9_-]{1,64}$")

        /** Phase 12: 桌面小部件 → MainActivity 启动 extra key(与 MuseQuickWidget 对齐)。 */
        const val EXTRA_WIDGET_ACTION = "widget_action"
        const val WIDGET_ACTION_NEW_SESSION = "new_session"
        const val WIDGET_ACTION_OPEN_CHATS = "open_chats"

        /** v2.6.7: 桌面小组件「快速语音输入」——新建会话后立即进入语音输入。 */
        const val WIDGET_ACTION_VOICE_INPUT = "voice_input"

        /** P3-16: 打开指定会话(配合 EXTRA_WIDGET_SESSION_ID 使用)。 */
        const val WIDGET_ACTION_OPEN_SESSION = "open_session"
        const val EXTRA_WIDGET_SESSION_ID = "widget_session_id"

        /** 桌面小组件 → 新建会话时指定助手(配合 WIDGET_ACTION_NEW_SESSION 使用)。 */
        const val EXTRA_WIDGET_ASSISTANT_ID = "widget_assistant_id"

        /** Launcher 长按快捷方式 action(与 shortcuts.xml 中定义一致)。 */
        const val ACTION_NEW_CHAT = "io.zer0.muse.ACTION_NEW_CHAT"
        const val ACTION_TRANSLATE = "io.zer0.muse.ACTION_TRANSLATE"
        const val ACTION_VOICE_INPUT = "io.zer0.muse.ACTION_VOICE_INPUT"
        const val ACTION_SETTINGS = "io.zer0.muse.ACTION_SETTINGS"

        /** v1.0.18: 快速记录快捷方式 action。 */
        const val ACTION_QUICK_NOTES = "io.zer0.muse.ACTION_QUICK_NOTES"
    }
}
