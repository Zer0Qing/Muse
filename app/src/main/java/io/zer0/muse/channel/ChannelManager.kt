package io.zer0.muse.channel

import android.content.Context
import io.zer0.common.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    private val senders: Map<ChannelPlatform, ChannelSender> = mapOf(
        ChannelPlatform.FEISHU to FeishuChannelSender(),
        ChannelPlatform.QQ to QqChannelSender(),
        ChannelPlatform.WECLAW to WeClawChannelSender(),
    )

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

    /** 删除配置。 */
    suspend fun remove(id: String) {
        mutex.withLock {
            val updated = _channels.value.filterNot { it.id == id }
            store.save(updated)
            _channels.value = updated
        }
    }

    /** 向指定渠道发送文本;所有失败都收敛为结果对象,不抛异常。 */
    suspend fun sendText(channelId: String, text: String): ChannelSendResult {
        val config = _channels.value.firstOrNull { it.id == channelId }
            ?: return ChannelSendResult(false, "渠道不存在: $channelId")
        if (!config.enabled) {
            return ChannelSendResult(false, "渠道已停用: ${config.name.ifBlank { channelId }}")
        }
        if (text.isBlank()) return ChannelSendResult(false, "发送内容为空")
        val sender = senders[config.platform]
            ?: return ChannelSendResult(false, "平台未支持: ${config.platform}")
        return sender.sendText(config, text).fold(
            onSuccess = { ChannelSendResult(true, "已发送到 ${config.name.ifBlank { config.platform.name }}") },
            onFailure = { e ->
                Logger.w(TAG, "渠道发送失败: ${e.message}")
                ChannelSendResult(false, e.message ?: "发送失败")
            },
        )
    }

    companion object {
        private const val TAG = "ChannelManager"
    }
}
