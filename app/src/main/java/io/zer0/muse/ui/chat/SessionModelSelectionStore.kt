package io.zer0.muse.ui.chat

/**
 * v1.x: 会话级 Provider/模型 override 的写接口。
 * ChatViewModel 持有一对 @Volatile override map,由各 Flow collector 重赋值;
 * 这里只暴露"写"操作(设置/清除),供 ChatSettingsController 在不反向依赖宿主的情况下更新。
 */
interface SessionModelSelectionStore {
    /** 设置会话级 provider override(null = 清除,回退全局默认)。 */
    fun setProviderOverride(sessionId: String, providerId: String?)

    /** 设置会话级 model override(null = 清除,回退 Provider 首模型)。 */
    fun setModelOverride(sessionId: String, modelId: String?)
}
