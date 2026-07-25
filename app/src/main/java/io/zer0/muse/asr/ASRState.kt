package io.zer0.muse.asr

/**
 * ASR 状态机枚举。
 * - Idle: 空闲
 * - Connecting: 连接中(WebSocket 握手 / HTTP 准备)
 * - Listening: 录音中(正在采集并上传)
 * - Stopping: 停止中(收尾,等待最后一段结果)
 * - Reconnecting: 断网重连中(WebSocket 断线后指数退避重连,期间继续录音并缓冲音频帧)
 * - Error: 错误
 */
enum class ASRStatus {
    Idle,
    Connecting,
    Listening,
    Stopping,
    Reconnecting,
    Error,
}

/**
 * ASR 状态数据类。
 * - status: 当前状态
 * - isAvailable: Provider 是否可用(配置了 apiKey)
 * - transcript: 当前累计识别文本(含中间结果)
 * - errorMessage: 错误消息(仅 Error 状态;Reconnecting 期间也携带"正在重连…"提示文案)
 * - amplitudes: 音量振幅历史(归一化 0-1f,用于 UI 波形),最多 32 个
 */
data class ASRState(
    val status: ASRStatus = ASRStatus.Idle,
    val isAvailable: Boolean = false,
    val transcript: String = "",
    val errorMessage: String? = null,
    val amplitudes: List<Float> = emptyList(),
) {
    /** 是否处于录音中(含连接/录音/停止/重连过渡态)。 */
    val isRecording: Boolean
        get() = status == ASRStatus.Connecting || status == ASRStatus.Listening ||
            status == ASRStatus.Stopping || status == ASRStatus.Reconnecting
}
