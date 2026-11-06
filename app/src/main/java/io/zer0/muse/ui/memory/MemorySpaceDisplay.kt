package io.zer0.muse.ui.memory

import io.zer0.memory.space.MemorySpaceEntity

/**
 * 空间名会进检索、落库和 rename 的回填，不能本地化；只在展示这一步把默认空间的哨兵换成当前语言的标签。
 */
internal fun memorySpaceDisplayName(name: String, defaultLabel: String): String =
    if (name == MemorySpaceEntity.DEFAULT_SPACE_NAME) defaultLabel else name
