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
 * v2.5.2: 标题是否为无意义占位（空白，或全由 ./。/·/… 等符号组成）。
 *
 * 供展示层（[displaySessionTitle]）与写入层（自动命名拦截）共用同一判定。
 */
fun isMeaninglessSessionTitle(title: String?): Boolean {
    val trimmed = title?.trim().orEmpty()
    if (trimmed.isEmpty()) return true
    return trimmed.all {
        it == '.' || it == '。' || it == '·' || it == '…' || it.isWhitespace()
    }
}
