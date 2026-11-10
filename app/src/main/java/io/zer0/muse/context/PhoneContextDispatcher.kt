package io.zer0.muse.context

/**
 * 手机上下文的分发器（对比清单 P2-02）。
 *
 * 这一层只做两件事：**按来源取数**、**把结果包装成统一信封**。
 * 具体来源怎么查由注入的 [Provider] 决定 —— 这样分发与话术可以在没有设备的环境里验证，
 * 而每个来源的真实实现各自单独接线。
 */
object PhoneContextDispatcher {

    /** 单个来源的取数结果（不含时间戳与窗口，那些由分发器统一填）。 */
    data class Loaded(
        val availability: PhoneContext.Availability,
        val items: List<String> = emptyList(),
        val note: String? = null,
        val truncated: Boolean = false,
    ) {
        companion object {
            fun denied(note: String? = null) = Loaded(PhoneContext.Availability.DENIED, note = note)

            fun unavailable(note: String? = null) = Loaded(PhoneContext.Availability.UNAVAILABLE, note = note)

            fun available(items: List<String>, truncated: Boolean = false) =
                Loaded(PhoneContext.Availability.AVAILABLE, items = items, truncated = truncated)
        }
    }

    fun interface Provider {
        fun load(days: Int): Loaded
    }

    /**
     * 取一组来源并渲染。
     *
     * 没注册 provider 的来源按 [PhoneContext.Availability.UNAVAILABLE] 处理并说明原因 ——
     * 静默跳过会让模型以为"用户没有数据"，而实际上是这个应用还没接。
     */
    fun describe(sources: List<PhoneContext.Source>, days: Int, nowMillis: Long, providers: Map<PhoneContext.Source, Provider>): String =
        sources.joinToString("\n\n") { source ->
            val provider = providers[source]
            val loaded = provider?.load(days) ?: Loaded.unavailable("这个应用还没有接入这项数据源")
            val envelope = PhoneContext.Envelope(
                source = source,
                availability = loaded.availability,
                capturedAtMillis = nowMillis,
                windowDays = days,
                itemCount = loaded.items.size,
                truncated = loaded.truncated,
                note = loaded.note,
            )
            PhoneContext.render(envelope, loaded.items)
        }
}
