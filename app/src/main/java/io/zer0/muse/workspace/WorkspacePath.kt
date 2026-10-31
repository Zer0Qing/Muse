package io.zer0.muse.workspace

/**
 * 一条解析后的工作区路径。
 *
 * @param mountId 目标挂载标识；为 null 表示没有写前缀，落到默认挂载。
 * @param relative 相对该挂载根目录的路径，已去掉首尾分隔符，根目录为空串。
 */
data class WorkspacePath(
    val mountId: String?,
    val relative: String,
) {
    /** 是否是挂载根目录本身。 */
    val isMountRoot: Boolean get() = relative.isEmpty()
}

/**
 * 工作区路径语法：`挂载标识:/相对路径`。
 *
 * 没写前缀的路径一律落到默认挂载，保证老调用点、老数据、老的模型习惯全部照常工作。
 * 前缀只认已注册的挂载标识：`相册:/a.jpg` 这种中文前缀不算挂载，会当成普通相对路径。
 */
object WorkspacePathParser {
    /** 挂载前缀与相对路径之间的分隔符。 */
    const val PREFIX_SEPARATOR: String = ":/"

    /** 不带挂载前缀时使用的默认挂载标识。 */
    const val DEFAULT_MOUNT_ID: String = WorkspaceMount.MAIN_ID

    /**
     * 解析一条路径。
     *
     * @param raw 原始路径，可能带 `mount:/` 前缀，也可能是空串（根目录）
     * @param knownMountIds 当前已注册的挂载标识集合，用于判定前缀是否有效
     */
    fun parse(raw: String, knownMountIds: Set<String>): WorkspacePath {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed == "/") {
            return WorkspacePath(null, "")
        }
        val colon = trimmed.indexOf(':')
        if (colon > 0) {
            val prefix = trimmed.substring(0, colon)
            if (prefix in knownMountIds) {
                return WorkspacePath(prefix, normalize(trimmed.substring(colon + 1)))
            }
        }
        return WorkspacePath(null, normalize(trimmed))
    }

    /**
     * 规范化相对路径：去掉首尾分隔符，合并重复分隔符。
     *
     * 不做 `..` 消解——越界判定必须由调用方在拼接成真实路径后再做一次，
     * 否则 `a/../../b` 这类写法会在规范化阶段被「洗白」。
     */
    fun normalize(relative: String): String = relative.trim().trim('/').split('/').filter { it.isNotEmpty() && it != "." }.joinToString("/")

    /** 把挂载标识与相对路径拼回一条可读路径。 */
    fun format(mountId: String, relative: String): String {
        val rel = normalize(relative)
        return if (rel.isEmpty()) "$mountId$PREFIX_SEPARATOR" else "$mountId$PREFIX_SEPARATOR$rel"
    }

    /** 路径里是否含有越界段。 */
    fun hasParentSegment(relative: String): Boolean = relative.split('/').any { it == ".." }
}
