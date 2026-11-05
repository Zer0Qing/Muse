package io.zer0.muse.ui.settings

/**
 * 「多引擎 API Key」每一行的显示与保存语义（纯逻辑，可 JVM 单测）。
 *
 * 为什么要抽出来：原来这一行是把**已保存的 Key 明文**直接填进输入框
 * （`keyDraft = apiKeys[provider].orEmpty()`），于是设置页一打开、或投屏/旁人扫一眼，
 * 每个引擎的密钥就都在屏幕上了。参考样式（深色「搜索引擎」设置页）用的是
 * 掩码 + `显示` 切换，所以这里做同样的事：默认掩码、按需揭示。
 *
 * 但"改成掩码"顺手会引出第二个坑：**留空到底算什么？**
 * 原语义是"留空保存＝删除该 Key"。一旦输入框默认不再回填密钥，
 * 用户点开设置页什么也没做、直接按保存，就会被读成"清空"——密钥被误删。
 * 所以这里把三态显式分开：
 *  - 没动过（`edited == false`）：保存时**保持原值**，即使显示上是空的；
 *  - 动过且非空：写入新值；
 *  - 动过且清空：才是真的要删除。
 */
internal object WebSearchKeyFieldState {

    /** 输入框里该显示什么：已编辑用草稿，否则用已保存值（由 UI 决定是否掩码）。 */
    fun displayValue(saved: String, draft: String, edited: Boolean): String =
        if (edited) draft else saved

    /** 默认掩码；只有用户显式揭示，或这一行根本还没有密钥时才明文。 */
    fun shouldMask(saved: String, revealed: Boolean): Boolean =
        saved.isNotBlank() && !revealed

    /** 没有已保存密钥时不必提供“显示/隐藏”按钮（掩码一串空字符串毫无意义）。 */
    fun canToggleVisibility(saved: String): Boolean = saved.isNotBlank()

    /** 保存动作真正要写入的值：null 表示删除该引擎的 Key。 */
    fun valueToSave(saved: String, draft: String, edited: Boolean): String? {
        if (!edited) return saved.ifBlank { null }
        return draft.trim().ifBlank { null }
    }
}
