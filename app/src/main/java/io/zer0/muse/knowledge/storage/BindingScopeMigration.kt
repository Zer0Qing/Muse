package io.zer0.muse.knowledge.storage

/**
 * R2 步骤 7:绑定作用域迁移 — `kbId::folderPath` 旧绑定升级为稳定 folderId 引用。
 *
 * 合同(计划:「迁移 kbId::folderPath 绑定;冲突和无法解析绑定进入报告而不是 All」):
 *  - 旧格式 `kbId::folderPath`(path 可空 = 整库)与 folderId 化格式并存解析;
 *  - 解析成功的条目 → [BindingScopeMigration.Resolved](folderId 已归一);
 *  - 无法解析(空 kbId / 空元素 / 格式段数错误)→ [BindingScopeMigration.Unresolved]
 *    进报告,**绝不扩大成 All**(与 R1 Invalid 合同同向);
 *  - 本对象是纯函数解析器;assistant JSON 的读写与迁移写回由调用方(R8 UI/R4 接线)
 *    消费,本批不改动 AssistantEntity 存储格式(写回迁移在数据可见后进行)。
 */
object BindingScopeMigration {

    sealed interface Entry {
        /** 解析成功:path=空串 表示整库绑定(folderId 空串 = 根)。 */
        data class Resolved(
            val kbId: String,
            val folderId: String,
            /** 归一化后的路径(报告可读性;folderId 是身份)。 */
            val normalizedPath: String,
        ) : Entry

        /** 无法解析:进迁移报告;调用方按失效绑定处理(R1:零结果,不回退 All)。 */
        data class Unresolved(val raw: String, val reason: String) : Entry
    }

    /**
     * 解析绑定条目列表。整库绑定(`kbId::` 或 `kbId`)的 folderId = 空串;
     * 路径绑定必须提供 [FolderIdResolver] 归一 —— 无 resolver 时目录绑定拒绝解析
     * (Unresolved),调用方不可用本函数做纯路径解析(防止静默域扩大)。
     */
    fun migrate(
        entries: List<String>,
        folderIdResolver: FolderIdResolver? = null,
    ): List<Entry> = entries.map { raw ->
        val trimmed = raw.trim()
        when {
            trimmed.isEmpty() -> Entry.Unresolved(raw, "空条目")
            else -> {
                val parts = trimmed.split("::")
                when {
                    parts.size == 1 -> Entry.Resolved(parts[0], "", "") // 整库绑定(无路径段)
                    parts.size == 2 -> {
                        val kbId = parts[0].trim()
                        val path = parts[1].trim()
                        when {
                            kbId.isBlank() -> Entry.Unresolved(raw, "kbId 为空")
                            path.isEmpty() -> Entry.Resolved(kbId, "", "") // "kbId::" = 整库
                            // 目录绑定必须经 resolver 归一到 folderId;无 resolver 时拒绝解析
                            // (进报告),绝不把目录绑定降级成 folderId=""(整库语义)—— 那是
                            // 静默域扩大,踩中「绑定不得扩大为全库」的逆反。
                            folderIdResolver == null -> Entry.Unresolved(raw, "无 folderId 解析器,目录绑定拒绝解析")
                            else -> Entry.Resolved(kbId, folderIdResolver.resolve(kbId, path), path)
                        }
                    }
                    // 多段:任何空段 = 格式损坏;全非空 = 段数超限(既非旧格式也非 folderId 化)
                    parts.any { it.isBlank() } -> Entry.Unresolved(raw, "格式段为空")
                    else -> Entry.Unresolved(raw, "段数超过 2(既非旧格式也非 folderId 化格式)")
                }
            }
        }
    }
}
