package io.zer0.muse.runtime

import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.muse.data.AtomicFileStore
import java.io.File

class RuntimeInstallOperationStore(private val file: File) {

    fun load(): RuntimeInstallOperation? {
        if (!file.isFile) return null
        return runCatching {
            AppJson.decodeFromString<RuntimeInstallOperation>(file.readText())
        }.onFailure { error ->
            Logger.w(TAG, "持久安装状态无法解析，隔离损坏文件", error)
            AtomicFileStore.quarantine(file, "runtime-operation")
        }.getOrNull()
    }

    fun saveAtomic(operation: RuntimeInstallOperation) {
        AtomicFileStore.writeText(file, AppJson.encodeToString(operation))
    }

    fun clear() {
        if (file.exists() && !file.delete()) Logger.w(TAG, "无法清理安装状态文件: ${file.name}")
    }

    private companion object {
        const val TAG = "RuntimeInstallOperationStore"
    }
}
