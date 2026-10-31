package io.zer0.muse.data

import java.util.concurrent.CopyOnWriteArrayList

/**
 * Provider 配置写入后的进程内广播。
 *
 * 数据层写完供应商/模型配置(含 baseUrl、apiKey、模型列表)后通知一次,
 * 持有「由 Provider 配置派生出来的缓存」的消费者自行失效。这样数据层无需反向依赖
 * 上层服务(如 rag 包里的 EmbeddingService),也不会把缓存失效逻辑散落到各个调用点。
 *
 * 当前订阅者:[io.zer0.muse.rag.EmbeddingService](Provider 实例缓存)。
 * 回调在写入方协程里同步执行,实现必须是非阻塞、可重入且幂等的。
 */
object ProviderConfigChangeBus {

    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    /** 注册变更回调;同一实例重复注册只保留一份。 */
    fun register(listener: () -> Unit) {
        if (!listeners.contains(listener)) listeners.add(listener)
    }

    /** 注销变更回调(测试或长生命周期对象销毁时用)。 */
    fun unregister(listener: () -> Unit) {
        listeners.remove(listener)
    }

    /** 通知所有订阅者配置已变更。单个订阅者抛异常不影响其他订阅者与写入流程。 */
    fun notifyChanged() {
        listeners.forEach { listener ->
            runCatching { listener() }
        }
    }
}
