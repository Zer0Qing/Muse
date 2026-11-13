package io.zer0.muse.asr

/**
 * 「现在这个语音输入入口能不能用」的唯一判据。
 *
 * 两个消费者必须答一致：
 *  - 输入栏：无文字时要不要把发送键换成语音键（[io.zer0.muse.ui.ChatScreen]）
 *  - 录音协调器：长按说话要不要真的去建控制器（[io.zer0.muse.ui.chat.ChatAudioCoordinator]）
 *
 * 一旦两边漂移，用户看到的就是「键在，按下去没反应」或「明明配好了却没有语音键」，
 * 而这在真机上极难被注意到 —— 所以判据只有这一份。
 *
 * 各引擎的「可用」含义不同，这正是它必须集中在一个函数里的原因：
 *  - SYSTEM：走系统 Intent，由系统判断可用性，这里不算「已配置」
 *  - DASHSCOPE_FILE：只做文件转录，没有实时录音入口
 *  - LOCAL_SENSEVOICE：不联网、没有 apiKey，可用性 = 模型文件是否就绪
 *  - 其余云端引擎：有 apiKey 才算配置好；空 key 的请求必然失败
 */
internal fun isAsrInputAvailable(provider: AsrProviderType, apiKey: String, localModelReady: Boolean): Boolean = when (provider) {
    AsrProviderType.SYSTEM, AsrProviderType.DASHSCOPE_FILE -> false
    AsrProviderType.LOCAL_SENSEVOICE -> localModelReady
    AsrProviderType.DASHSCOPE,
    AsrProviderType.STEP,
    AsrProviderType.OPENAI_WHISPER,
    AsrProviderType.OPENAI_REALTIME,
    AsrProviderType.AGNES,
    -> apiKey.isNotBlank()
}
