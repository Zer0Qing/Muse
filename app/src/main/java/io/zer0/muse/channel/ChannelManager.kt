package io.zer0.muse.channel

import android.content.Context
import io.zer0.common.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * v1.0.92: 渠道管理器 — 配置 CRUD + 发送分发。
 *
 * v1 范围(发送侧):把 Muse 的文本推送到飞书 / QQ 等平台;
 * 接收侧(事件长连接 / webhook)与配置 UI、LLM 工具分步推进。
 */
class ChannelManager(context: Context) {

    private val store = ChannelStore(File(context.filesDir, "channel_configs.json"))
    private val mutex = Mutex()

    private val _channels = MutableStateFlow<List<ChannelConfig>>(emptyList())
    val channels: StateFlow<List<ChannelConfig>> = _channels.asStateFlow()

    init {
        // 启动自检：媒体能力声明缺字段在这里就炸，不留到用户发图时。
        validateChannelCapabilities()
    }

    private val senders: Map<ChannelPlatform, ChannelSender> = mapOf(
        ChannelPlatform.FEISHU to FeishuChannelSender(),
        ChannelPlatform.QQ to QqChannelSender(),
        ChannelPlatform.WECLAW to WeClawChannelSender(),
        ChannelPlatform.TELEGRAM to TelegramChannelSender(),
        ChannelPlatform.DINGTALK to DingtalkChannelSender(),
    )

    /** 该平台的媒体能力表。 */
    fun mediaCapabilitiesOf(platform: ChannelPlatform): io.zer0.muse.channel.MediaCapabilities = platform.capabilities.mediaCapabilities

    /** 加载持久化配置(应用启动或页面进入时调用)。 */
    suspend fun refresh() {
        mutex.withLock {
            _channels.value = store.load()
        }
    }

    /** 新增/更新配置并持久化。 */
    suspend fun upsert(config: ChannelConfig) {
        mutex.withLock {
            val updated = _channels.value.filterNot { it.id == config.id } + config
            store.save(updated)
            _channels.value = updated
        }
    }

    /** 删除配置并清理运行时状态与联系人元数据。 */
    suspend fun remove(id: String) {
        mutex.withLock {
            val updated = _channels.value.filterNot { it.id == id }
            store.save(updated)
            _channels.value = updated
        }
        ChannelRuntimeRegistry.remove(id)
        ChannelContactMetaStore.removeForChannel(id)
    }

    /**
     * 向指定渠道发送文本;所有失败都收敛为结果对象,不抛异常。
     *
     * [targetOverride] 供自动回复"回发到消息来源"使用(见 [ChannelSender.sendText])。
     */
    suspend fun sendText(channelId: String, text: String, targetOverride: String? = null): ChannelSendResult =
        sendTextWithOptions(channelId, text, ChannelSendOptions(targetOverride = targetOverride))

    /** v2.6.23(C1): 本层能确定的发送目标:显式 override 优先,否则渠道默认目标。 */
    private fun sendTarget(config: ChannelConfig, targetOverride: String?): String =
        targetOverride?.trim()?.takeIf { it.isNotEmpty() } ?: config.targetId.trim()

    /** 目标不可见(WeClaw 信用 reply token)时不在这里拦 — 入站闸已覆盖,空串误判成 allowlist 不命中是错的。 */
    private fun policyAllowsAtSend(config: ChannelConfig, targetOverride: String?): Boolean {
        val target = sendTarget(config, targetOverride)
        return target.isEmpty() || ChannelPolicy.allows(config, target)
    }

    internal suspend fun sendTextWithOptions(channelId: String, text: String, options: ChannelSendOptions): ChannelSendResult {
        val config = _channels.value.firstOrNull { it.id == channelId }
        val sender = config?.let { senders[it.platform] }
        val result = when {
            config == null -> ChannelSendResult(false, "渠道不存在: $channelId")
            !config.enabled -> ChannelSendResult(false, "渠道已停用: ${config.name.ifBlank { channelId }}")
            text.isBlank() -> ChannelSendResult(false, "发送内容为空")
            sender == null -> ChannelSendResult(false, "平台未支持: ${config.platform}")
            // v2.6.23(C1): 出站策略。只对「这里能确定的目标」判定:
            //  - 工具/手动发送一定有 targetOverride 或 config.targetId → 判;
            //  - WeClaw 回信用的是入站 context token,目标在本层不可见 → 交给入站那道闸
            //    (ChannelAutoReply),不在这里误拦成"目标是空串"。
            !policyAllowsAtSend(config, options.targetOverride) ->
                ChannelSendResult(false, ChannelPolicy.blockReason(config, sendTarget(config, options.targetOverride)))
            else -> sender.sendText(
                config,
                text,
                options.targetOverride,
                options.contextTokenOverride,
                options.sourceEventIdOverride,
                options.sourceEventSequenceOverride,
            ).fold(
                onSuccess = { ChannelSendResult(true, "已发送到 ${config.name.ifBlank { config.platform.name }}") },
                onFailure = { error ->
                    Logger.w(TAG, "渠道发送失败: ${error.message}")
                    ChannelSendResult(false, error.message ?: "发送失败")
                },
            )
        }
        return result
    }

    /**
     * 人工接管回发（文本或本地图片）：走同一出站策略与发送管线，成功后同步记入会话台账与防回声账本。
     */
    suspend fun sendManualReply(channelId: String, targetId: String, text: String, localImagePath: String = ""): ChannelSendResult {
        val cleanedText = text.trim()
        if (cleanedText.isBlank() && localImagePath.isBlank()) {
            return ChannelSendResult(false, "发送内容为空")
        }
        val config = _channels.value.firstOrNull { it.id == channelId }
            ?: return ChannelSendResult(false, "渠道不存在: $channelId")
        val target = targetId.trim().ifBlank { config.targetId.trim() }
        if (target.isBlank() && !config.platform.capabilities.requiresRecentInbound) {
            return ChannelSendResult(false, "未指定发送目标")
        }
        if (target.isNotEmpty() && !ChannelPolicy.allows(config, target)) {
            return ChannelSendResult(false, ChannelPolicy.blockReason(config, target))
        }

        if (localImagePath.isNotBlank()) {
            val mediaResult = sendLocalImage(config, target, localImagePath)
            if (!mediaResult.ok) return mediaResult
        }
        if (cleanedText.isNotBlank()) {
            val textResult = sendText(channelId, cleanedText, targetOverride = target.ifBlank { null })
            if (!textResult.ok) return textResult
            ChannelRelayLedger.recordSent(channelId, cleanedText, System.currentTimeMillis())
        }

        val compactBase64 = if (localImagePath.isNotBlank()) {
            withContext(Dispatchers.IO) {
                runCatching { ChannelMediaUtils.toCompactImageBase64(File(localImagePath).readBytes()) }
                    .getOrNull()
                    .orEmpty()
            }
        } else {
            ""
        }
        if (target.isNotBlank()) {
            ChannelConversationStore.appendTurn(
                channelId = channelId,
                from = target,
                turn = ChannelConversationStore.Turn(
                    role = "assistant",
                    text = cleanedText,
                    mediaKind = if (localImagePath.isNotBlank()) "image" else "",
                    mediaBase64 = compactBase64,
                    mediaPath = localImagePath,
                    source = "manual",
                ),
            )
        }
        return ChannelSendResult(true, "已发送")
    }

    private suspend fun sendLocalImage(config: ChannelConfig, target: String, localImagePath: String): ChannelSendResult {
        val file = File(localImagePath)
        if (!file.exists()) return ChannelSendResult(false, "图片文件不存在")
        val inputModes = config.platform.capabilities.mediaCapabilities.inputModes
        val outbound: OutboundMedia = when {
            MediaInputMode.LOCAL_FILE in inputModes -> OutboundMedia.LocalFile(
                kind = MediaKind.IMAGE,
                path = file.absolutePath,
                fileName = file.name,
                mimeType = "image/jpeg",
            )
            MediaInputMode.BUFFER in inputModes -> OutboundMedia.Bytes(
                kind = MediaKind.IMAGE,
                bytes = withContext(Dispatchers.IO) { file.readBytes() },
                fileName = file.name,
                mimeType = "image/jpeg",
            )
            else -> return ChannelSendResult(false, "${config.platform.name} 暂不支持从本机发送图片")
        }
        return MediaSender.send(config, outbound, targetOverride = target.ifBlank { null }).fold(
            onSuccess = { ChannelSendResult(true, "图片已发送") },
            onFailure = { error -> ChannelSendResult(false, error.message ?: "图片发送失败") },
        )
    }

    /**
     * 结构化凭证校验：返回识别到的机器人展示名与可读摘要，不再泄露开发术语。
     */
    suspend fun validateStructured(config: ChannelConfig): ChannelValidationResult = withContext(Dispatchers.IO) {
        when (config.platform) {
            ChannelPlatform.FEISHU -> runCatching { FeishuChannelSender().fetchToken(config) }.fold(
                onSuccess = {
                    ChannelValidationResult(ok = true, botName = config.name, summary = "凭证有效，可建立飞书连接")
                },
                onFailure = { e ->
                    ChannelValidationResult(ok = false, errorDetail = e.message ?: "飞书凭据校验失败")
                },
            )
            ChannelPlatform.QQ -> QqClient.fetchAccessToken(config.appId.trim(), config.appSecret.trim()).fold(
                onSuccess = {
                    ChannelValidationResult(ok = true, botName = config.name, summary = "凭证有效，可连接 QQ 机器人")
                },
                onFailure = { e ->
                    ChannelValidationResult(ok = false, errorDetail = e.message ?: "QQ 凭据校验失败")
                },
            )
            ChannelPlatform.DINGTALK ->
                DingtalkClient
                    .fetchAccessToken(config.appId.trim(), config.appSecret.trim(), config.dingtalkApiBase)
                    .fold(
                        onSuccess = {
                            ChannelValidationResult(ok = true, botName = config.name, summary = "凭证有效，可接入钉钉应用")
                        },
                        onFailure = { e ->
                            ChannelValidationResult(ok = false, errorDetail = e.message ?: "钉钉凭据校验失败")
                        },
                    )
            ChannelPlatform.TELEGRAM -> TelegramClient.getMe(config.appSecret.trim()).fold(
                onSuccess = { botHandle ->
                    ChannelValidationResult(ok = true, botName = botHandle, summary = "已识别机器人 $botHandle")
                },
                onFailure = { e ->
                    ChannelValidationResult(ok = false, errorDetail = e.message ?: "Bot Token 校验失败")
                },
            )
            ChannelPlatform.WECLAW -> ChannelValidationResult(
                ok = config.appSecret.isNotBlank(),
                botName = config.name,
                summary = if (config.appSecret.isNotBlank()) "微信扫码凭据就绪" else "请先扫码绑定微信",
                errorDetail = if (config.appSecret.isBlank()) "尚未完成微信扫码绑定" else "",
            )
        }
    }

    /** 兼容旧调用方：委托给 [validateStructured]。 */
    suspend fun validate(config: ChannelConfig): Result<String> {
        val structured = validateStructured(config)
        return if (structured.ok) {
            Result.success(structured.summary.ifBlank { structured.botName })
        } else {
            Result.failure(IllegalStateException(structured.errorDetail.ifBlank { "校验失败" }))
        }
    }

    companion object {
        private const val TAG = "ChannelManager"
    }
}

internal data class ChannelSendOptions(
    val targetOverride: String? = null,
    val contextTokenOverride: String? = null,
    val sourceEventIdOverride: String? = null,
    val sourceEventSequenceOverride: Int? = null,
)
