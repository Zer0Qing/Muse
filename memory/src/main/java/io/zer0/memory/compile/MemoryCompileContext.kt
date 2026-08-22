package io.zer0.memory.compile

/** 编译产物的宿主隔离边界，避免把 scope/space 作为散落的构造参数。 */
data class MemoryCompileContext(
    val getScope: suspend () -> String = { "main" },
    val getSpaceId: suspend () -> String = { "default" },
)
