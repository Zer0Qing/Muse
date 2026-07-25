package io.zer0.muse.asr

import kotlinx.coroutines.flow.StateFlow

/**
 * 流式 ASR Controller 接口(借鉴 rikkahub 架构)。
 *
 * 与旧版 [AsrClient] 的区别:
 * - 旧版: recognize(pcmBytes) 一次性识别,录完才出结果
 * - 新版: start(onTranscriptChange) 流式识别,边录边传边回调
 *
 * Controller 内部负责:
 * - 录音采集(AudioRecord + VOICE_COMMUNICATION)
 * - 音频上传(WebSocket / HTTP)
 * - 状态管理(StateFlow<ASRState>)
 * - 结果回调(onTranscriptChange)
 *
 * 生命周期:
 * - start(): 开始录音+上传,状态 Idle→Connecting→Listening
 * - stop(): 停止录音,等待最后结果,状态 Listening→Stopping→Idle
 * - dispose(): 释放所有资源(协程 scope / WebSocket / AudioRecord)
 */
interface ASRController {
    /** 当前状态(可观察)。 */
    val state: StateFlow<ASRState>

    /**
     * 开始录音+识别。
     * @param onTranscriptChange 识别文本变化回调(主线程),每次有新结果(中间或最终)都回调
     */
    fun start(onTranscriptChange: ((String) -> Unit)? = null)

    /** 停止录音,等待最后一段结果返回后切回 Idle。 */
    fun stop()

    /** 释放所有资源(协程 scope / WebSocket / AudioRecord),不可再 start。 */
    fun dispose()
}
