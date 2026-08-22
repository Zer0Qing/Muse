package io.zer0.memory.ticker

/** 记忆后台任务运行时配置与空间快照读取器。 */
data class MemoryRuntimeContext(
    val getConfig: () -> MemoryConfig = { MemoryConfig() },
    val getCurrentSpaceId: suspend () -> String = { "default" },
)
