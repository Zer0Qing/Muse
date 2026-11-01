package io.zer0.muse.util

import java.io.File

/**
 * 包内路径的安全判定（ZIP 条目名、manifest 里的 entry 等都走这里）。
 *
 * 这条判断此前在 `PluginPackageLoader` 与 `PluginSecurityGate` 里**各有一份逐字节相同的实现**。
 * 安全校验留三副本的后果不是"代码重复"，而是：某一次为了放宽别处只改了一份，
 * 另一份就悄悄变成更宽松的那条 —— 而路径穿越只在被利用时才看得见。
 */
object SafePaths {

    /**
     * 是否是一个可以安全拼接到目标目录下的相对路径。
     *
     * 拒绝清单（任一命中即不安全）：
     *  - 空 / 只有分隔符；
     *  - 以 `/` 或 `\` 开头（绝对路径，能写到工作区之外）；
     *  - 含 `..`（目录穿越）；
     *  - 含反斜杠（Windows 分隔符变体，也顺带挡掉 `c:\..` 这类写法）；
     *  - 分段里出现空串或 `.`（`a//b`、`./x` 这类会被解析器吃掉，落到磁盘上位置却不同）。
     */
    fun isSafeRelative(path: String): Boolean {
        val normalized = path.trimEnd('/')
        return normalized.isNotBlank() &&
            !normalized.startsWith("/") &&
            !normalized.startsWith("\\") &&
            !normalized.contains("..") &&
            !normalized.contains('\\') &&
            normalized.split('/').none { it.isBlank() || it == "." }
    }

    /**
     * 把相对路径拼到 [parent] 下，并确认结果**真的还在** parent 里面。
     *
     * [isSafeRelative] 挡的是字面写法；这里再挡一次解析后的位置 ——
     * 已有符号链接、大小写不敏感文件系统、`File` 的规范化差异都可能让字面安全的路径落到外面。
     * 不安全时返回 null，由调用方给出可读拒绝原因（不要静默改写路径）。
     */
    fun safeResolve(parent: File, relative: String): File? {
        if (!isSafeRelative(relative)) return null
        val target = File(parent, relative)
        val parentPath = parent.absoluteFile.canonicalPath.trimEnd(File.separatorChar) + File.separator
        val targetPath = runCatching { target.canonicalPath }.getOrNull() ?: return null
        return target.takeIf { targetPath.startsWith(parentPath) }
    }
}
