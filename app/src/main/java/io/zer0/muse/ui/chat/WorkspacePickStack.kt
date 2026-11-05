package io.zer0.muse.ui.chat

/**
 * 下钻栈（纯逻辑，JVM 可测）：对话顶栏「更多 → 工作区」的层级状态。
 *
 * 只存**相对路径**（与 `WorkspaceManager.listDir` 同口径），根是空串。
 * 每进一层压一帧；到底时 `canGoBack=false`，UI 据此禁用「返回」，
 * 而不是留一个点了没反应的按钮。
 */
internal class WorkspacePickStack {
    private val frames = mutableListOf(ROOT)

    var current: String = ROOT
        private set

    val canGoBack: Boolean get() = frames.size > 1

    /** 进入某目录；重复进入同一路径不动栈（否则返回链上会留空台阶）。 */
    fun enter(path: String) {
        val target = normalize(path)
        if (target == current) return
        frames += target
        current = target
    }

    fun back(): Boolean {
        if (!canGoBack) return false
        frames.removeAt(frames.size - 1)
        current = frames.last()
        return true
    }

    companion object {
        const val ROOT = ""

        /** 去首尾空白与斜杠，与 `listDir` 的相对路径口径一致。 */
        fun normalize(path: String): String = path.trim().trim('/')
    }
}
