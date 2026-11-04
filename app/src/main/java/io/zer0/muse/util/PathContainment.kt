package io.zer0.muse.util

import java.io.File

/**
 * 路径包含判定：**candidate 是否严格位于 root 之内**。
 *
 * 为什么要有这一个函数：仓库里同样的判断写过四处（两个 zip 解压守卫、贴纸目录守卫、
 * 技能包读取守卫），其中三处写成 `startsWith(root.canonicalPath + File.separator)`，
 * 第四处漏了分隔符 —— 漏掉的那处会把 `workspace/skillsX/SKILL.md` 判成
 * 在 `workspace/skills` 之内（前缀字符串恰好接得上）。
 * 这类"少一个分隔符"的错在代码评审里几乎看不见，所以统一走这里。
 *
 * 两端都要求已经 `canonicalPath`（或等价地已规范化），否则 `..`、软链接、
 * 大小写差异都可能绕过判定；调用方负责传规范化后的路径 —— 现有四处都是这么做的。
 *
 * @param allowEqual 根目录本身算不算"在其中"。默认不算：
 *        解压/写入守卫里"目标正好等于根目录"意味着 entry 名是空或 `.`，
 *        历史上那三处都是拒绝的，这里保持同样的严格度。
 */
internal fun isStrictlyInside(
    candidatePath: String,
    rootPath: String,
    allowEqual: Boolean = false,
): Boolean {
    val root = rootPath.trimEnd(File.separatorChar)
    if (candidatePath == root) return allowEqual
    return candidatePath.startsWith(root + File.separator)
}
