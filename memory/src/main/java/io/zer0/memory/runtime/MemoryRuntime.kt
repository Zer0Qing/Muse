package io.zer0.memory.runtime

interface MemoryRuntime {
    suspend fun read(request: MemoryReadRequest): MemoryReadResult
}
