package io.zer0.muse.util

import io.zer0.common.Logger

/**
 * 懒解析（lambda 形式注入的依赖）的统一入口：**失败一定留下日志**。
 *
 * 为什么要有这个：Koin 里有些依赖是包成 `() -> T?` 延迟取的（避免循环依赖，
 * 例如 `agent_phone` 用的 `GroupChatScheduler`）。原来的写法是
 * `runCatching { get<T>() }.getOrNull()` —— 测试环境确实该这么降级，
 * 但**生产上装配出错时它同样一声不响**：工具只回一句「未配置」，
 * 日志里没有任何原因，用户看到的是"悄悄发了但没人回"，排查时完全无从下手。
 *
 * 这里保持同样的降级行为（返回 null，绝不抛），只多做事后必要的一件事：
 * 把失败原因记下来。降级可以沉默，**故障不能沉默**。
 *
 * @param label 人类可读的依赖名，直接进日志（不要传字符串资源 id：日志不进 UI）。
 */
internal fun <T : Any> lookupOrNull(label: String, lookup: () -> T): T? =
    runCatching(lookup)
        .onFailure { e ->
            Logger.w(
                TAG,
                "$label 解析失败，本次按「不可用」降级 | 原因: ${e.message ?: e.javaClass.simpleName}",
                e,
            )
        }
        .getOrNull()

private const val TAG = "LazyLookup"
