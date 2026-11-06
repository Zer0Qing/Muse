package io.zer0.muse.data.session

/**
 * v2.5.2: 会话标题显示兜底 —— 历史导入 / 自动命名可能把标题存成纯省略号（"…"、"..."、
 * "。。"、"·" 等）或空白，直接占位显示像坏数据。
 *
 * 全应用统一口径（首页列表 CHAT-16、备份导出会话列表等）都走这里，
 * 避免同一批坏数据在不同页面表现不一致（首页显示「新会话」、导出列表显示 "…"）。
 *
 * 只影响展示；数据库中的原值不动（避免误改用户数据）。
 *
 * @param title 原始标题
 * @param fallback 兜底展示名（通常是「新会话」）
 */
fun displaySessionTitle(title: String?, fallback: String): String {
    val trimmed = title?.trim().orEmpty()
    if (trimmed.isEmpty()) return fallback
    return if (isMeaninglessSessionTitle(trimmed)) fallback else trimmed
}

/**
 * 历史上按"当时界面语言"写进数据库的默认标题字面量。
 *
 * 它们不是用户起的名字。切语言后,旧语言的默认标题会一直挂在会话列表、
 * 聊天顶栏与备份导出里(实机走查 实-W3-02/03:西语界面显示 "New Conversation",
 * 同一条会话在首页与搜索里又各自显示另一种写法)。展示层把它们与空白同等对待,
 * 统一回落到当前语言的兜底名;数据库原值不动,避免误改用户数据。
 *
 * 这份清单与各语言资源 strings_data.xml 里的 session_repo_default_title 一一对应,
 * 由 SessionTitleDefaultLiteralTest 钉住:改了文案而没同步这里,测试会断。
 */
internal val legacyDefaultSessionTitles: Set<String> = setOf(
    "新会话",
    "New Conversation",
    "Nueva conversación",
    "新しい会話",
    "새로운 대화",
    "Nova conversa",
    "Новый разговор",
)

/**
 * v2.5.2: 标题是否为无意义占位（空白，或全由 ./。/·/… 等符号组成）。
 *
 * 供展示层（[displaySessionTitle]）与写入层（自动命名拦截）共用同一判定。
 *
 * 默认标题字面量也算无意义:自动命名因此可以给"新会话"改名,展示层则统一回落兜底名。
 */
fun isMeaninglessSessionTitle(title: String?): Boolean {
    val trimmed = title?.trim().orEmpty()
    if (trimmed.isEmpty()) return true
    if (trimmed in legacyDefaultSessionTitles) return true
    return trimmed.all {
        it == '.' || it == '。' || it == '·' || it == '…' || it.isWhitespace()
    }
}
