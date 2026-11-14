package io.zer0.memory.runtime

/**
 * 记忆作用域合同。
 *
 * 三类键必须全部非空：assistantId 是资源拥有者，scope 是事实隔离键，
 * spaceId 是工作/生活等空间键。读取、写入、编译、搜索、删除和后台作业
 * 都必须携带可信作用域；空值不允许静默扩大为全库。
 */
data class MemoryScopeKey(
    val assistantId: String,
    val scope: String,
    val spaceId: String,
) {
    /** 去除首尾空白后的规范化结果。 */
    fun normalized(): MemoryScopeResult = when {
        assistantId.isBlank() -> MemoryScopeResult.Invalid("assistantId must not be blank")
        scope.isBlank() -> MemoryScopeResult.Invalid("scope must not be blank")
        spaceId.isBlank() -> MemoryScopeResult.Invalid("spaceId must not be blank")
        else -> MemoryScopeResult.Valid(
            copy(
                assistantId = assistantId.trim(),
                scope = scope.trim(),
                spaceId = spaceId.trim(),
            ),
        )
    }
}

sealed interface MemoryScopeResult {
    data class Valid(val value: MemoryScopeKey) : MemoryScopeResult
    data class Invalid(val reason: String) : MemoryScopeResult
}
