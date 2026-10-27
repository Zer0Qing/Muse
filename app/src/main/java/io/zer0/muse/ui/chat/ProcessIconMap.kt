package io.zer0.muse.ui.chat

import androidx.compose.ui.graphics.vector.ImageVector
import io.zer0.muse.ui.common.icons.MuseIcons

/**
 * 过程步骤的图标：按工具名推断「这一步在干什么」。
 *
 * 拆成几组小函数而不是一个大 when —— 单函数里几十个 `||` 会直接把圈复杂度顶穿阈值，
 * 而且分组之后「哪一类先判」这件事写在调用顺序里，比藏在长表达式里更好读。
 *
 * 调用顺序即优先级：
 *  - 智能体类最先，否则 agent_discuss 会落到「委派」分支
 *  - 命令类排在文件类之前，exec_command 里也有 read 字样
 *
 * 全都匹配不上就退回扳手：宁可平庸，也不要猜错动作。
 */
internal fun processIconFor(name: String): ImageVector {
    val n = name.lowercase()
    return agentIcon(n) ?: shellIcon(n) ?: fileIcon(n) ?: netIcon(n) ?: dataIcon(n) ?: MuseIcons.wrench
}

/** 思考、讨论、委派 —— 跟「谁在干」有关。 */
private fun agentIcon(n: String): ImageVector? = when {
    n.contains("思考") || n.contains("think") || n.contains("reasoning") -> MuseIcons.brain
    n.contains("discuss") || n.contains("council") -> MuseIcons.users
    n.contains("subagent") || n.contains("delegate") || n.contains("agent") -> MuseIcons.robot
    else -> null
}

/** 命令与搜索 —— 跟「怎么找」有关。 */
private fun shellIcon(n: String): ImageVector? = when {
    n.contains("exec_command") || n.contains("run_command") || n.contains("termux") -> MuseIcons.terminal
    n.contains("shell") || n.contains("terminal") || n.contains("adb") -> MuseIcons.terminal
    n.contains("search") || n.contains("grep") || n.contains("find") -> MuseIcons.search
    else -> null
}

/** 读与写 —— 跟「动没动文件」有关。 */
private fun fileIcon(n: String): ImageVector? = when {
    n.contains("read") || n.contains("cat") || n.contains("list_files") -> MuseIcons.bookOpen
    n.contains("open_file") || n.contains("翻") || n.contains("读过") -> MuseIcons.bookOpen
    n.contains("write") || n.contains("edit") || n.contains("patch") -> MuseIcons.edit
    n.contains("create") || n.contains("save") || n.contains("落笔") -> MuseIcons.edit
    else -> null
}

/** 网络 —— 跟「出没出网」有关。 */
private fun netIcon(n: String): ImageVector? = when {
    n.contains("browser") || n.contains("navigate") -> MuseIcons.browser
    n.contains("fetch") || n.contains("http") || n.contains("curl") -> MuseIcons.globe
    n.contains("download") || n.contains("upload") -> MuseIcons.cloudDownload
    else -> null
}

/** 记忆、媒体、数据 —— 跟「碰的是什么」有关。 */
private fun dataIcon(n: String): ImageVector? = when {
    n.contains("memory") || n.contains("remember") || n.contains("recall") -> MuseIcons.brain
    n.contains("image") || n.contains("photo") || n.contains("video") || n.contains("sticker") -> MuseIcons.image
    n.contains("db") || n.contains("sql") || n.contains("database") -> MuseIcons.database
    else -> null
}
