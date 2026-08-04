package io.zer0.muse.tools.quicknote

import android.content.Context
import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.muse.data.quicknote.QuickNoteDao
import io.zer0.muse.data.quicknote.QuickNoteEntity
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/**
 * v1.136: 快速记录(轻量笔记)存储。
 *
 * 数据以 JSON 文件保存在应用私有目录,支持标题、正文、标签、置顶。
 * 模型可通过 ToolRegistry 中的 quick_note_* 工具读写维护。
 *
 * v1.0.17: Room 迁移后,此类仅作为:
 *  - 迁移源([migrateToRoom] 把旧 JSON 数据导入 Room)
 *  - 兼容格式化(ToolRegistry 内部用于 QuickNote → 文本格式化)
 *  - 历史数据兜底读取(若迁移失败可手动恢复)
 *
 * 真正的读写持久化由 [QuickNoteDao] / [QuickNoteEntity] 承担,UI 通过
 * [io.zer0.muse.ui.quicknotes.QuickNotesViewModel] 观察 Room Flow。
 */
class QuickNoteStore(private val context: Context) {

    private val file by lazy { java.io.File(context.filesDir, "quicknotes/notes.json") }

    /** B5-06: 最近一次文件损坏提示(可被 UI 消费后清空)。 */
    @Volatile
    private var lastError: String? = null

    /** 消费最近一次损坏提示(UI 展示后调用)。 */
    fun consumeError(): String? {
        val e = lastError
        lastError = null
        return e
    }

    /** 列出记录,可选按关键字/标签过滤,置顶记录排在前面。 */
    fun list(keyword: String? = null, tag: String? = null, limit: Int = 50): List<QuickNote> {
        var all = readAll().sortedWith(compareByDescending<QuickNote> { it.pinned }.thenByDescending { it.updatedAtMillis })
        if (!keyword.isNullOrBlank()) {
            val kw = keyword.lowercase()
            all = all.filter {
                it.title.lowercase().contains(kw) || it.content.lowercase().contains(kw) || it.tags.any { t -> t.lowercase().contains(kw) }
            }
        }
        if (!tag.isNullOrBlank()) {
            all = all.filter { it.tags.any { t -> t.equals(tag, ignoreCase = true) } }
        }
        return all.take(limit)
    }

    /** 搜索记录(与 list 关键字过滤行为一致)。 */
    fun search(keyword: String, limit: Int = 20): List<QuickNote> = list(keyword = keyword, limit = limit)

    /** 根据 id 获取单条记录。 */
    fun get(id: String): QuickNote? = readAll().find { it.id == id }

    /**
     * 添加一条记录。
     *
     * @return 记录 id
     */
    fun add(title: String, content: String, tags: List<String>): String {
        val now = System.currentTimeMillis()
        val note = QuickNote(
            id = java.util.UUID.randomUUID().toString(),
            title = title,
            content = content,
            tags = tags,
            pinned = false,
            createdAtMillis = now,
            updatedAtMillis = now,
        )
        save(readAll().toMutableList().apply { add(note) })
        return note.id
    }

    /** 更新指定记录。 */
    fun update(id: String, title: String?, content: String?, tags: List<String>?): Boolean {
        val all = readAll().toMutableList()
        val idx = all.indexOfFirst { it.id == id }
        if (idx < 0) return false
        val old = all[idx]
        all[idx] = old.copy(
            title = title ?: old.title,
            content = content ?: old.content,
            tags = tags ?: old.tags,
            updatedAtMillis = System.currentTimeMillis(),
        )
        save(all)
        return true
    }

    /** 删除记录。 */
    fun remove(id: String): Boolean {
        val all = readAll().toMutableList()
        val removed = all.removeIf { it.id == id }
        if (removed) save(all)
        return removed
    }

    /** 切换置顶状态。 */
    fun setPinned(id: String, pinned: Boolean): Boolean {
        val all = readAll().toMutableList()
        val idx = all.indexOfFirst { it.id == id }
        if (idx < 0) return false
        all[idx] = all[idx].copy(pinned = pinned, updatedAtMillis = System.currentTimeMillis())
        save(all)
        return true
    }

    /**
     * v1.0.17: 一次性把旧 JSON 数据迁移到 Room。
     *
     * 调用时机:App 启动时由 [io.zer0.muse.MuseApp] 通过 SharedPreferences 标志
     * `quick_notes_migrated` 控制仅执行一次(幂等保障):
     *  - 首次升级到 v1.0.17 的用户:标志为 false → 执行迁移 → 置 true
     *  - 已迁移过的用户:标志为 true → 跳过
     *  - 新装用户:JSON 文件不存在,readAll() 返回 emptyList,空跑迁移后置 true
     *
     * 幂等性:[QuickNoteDao.upsert] 用 OnConflictStrategy.REPLACE,即使重复调用
     * 也以 id 为主键覆盖,不会产生重复记录。但为避免每次启动都遍历 JSON 文件,
     * 仍由调用方用 SharedPreferences 标志保证只执行一次。
     *
     * 迁移完成后不删除 JSON 文件,作为本地备份保留(用户可手动清理)。
     *
     * @param dao Room 数据访问对象
     * @return 迁移的记录数(0 表示无数据或文件不存在)
     */
    suspend fun migrateToRoom(dao: QuickNoteDao): Int {
        val notes = readAll()
        if (notes.isEmpty()) {
            Logger.i(TAG, "migrateToRoom: JSON 文件无数据,跳过迁移")
            return 0
        }
        var migrated = 0
        notes.forEach { n ->
            dao.upsert(
                QuickNoteEntity(
                    id = n.id,
                    title = n.title,
                    content = n.content,
                    tags = n.tags,
                    pinned = n.pinned,
                    // 旧 JSON 无回收站概念,迁移后全部为正常记录
                    deleted = false,
                    deletedAt = 0,
                    createdAt = n.createdAtMillis,
                    updatedAt = n.updatedAtMillis,
                ),
            )
            migrated++
        }
        Logger.i(TAG, "migrateToRoom: 成功迁移 $migrated 条快速记录到 Room")
        return migrated
    }

    private fun readAll(): List<QuickNote> {
        return try {
            if (!file.exists()) return emptyList()
            AppJson.decodeFromString(ListSerializer(QuickNote.serializer()), file.readText())
        } catch (e: Exception) {
            backupCorruptFile()
            lastError = "快速记录文件损坏，已备份原文件并重建"
            Logger.e(TAG, "读取快速记录失败，已备份坏文件: ${e.message}", e)
            emptyList()
        }
    }

    /** B5-06: 损坏文件备份为 .bak-<时间戳>,避免静默丢失现场。 */
    private fun backupCorruptFile() {
        runCatching {
            if (file.exists()) {
                val backup = java.io.File(file.parentFile, "${file.name}.bak-${System.currentTimeMillis()}")
                file.copyTo(backup, overwrite = true)
                Logger.w(TAG, "损坏文件已备份到 ${backup.absolutePath}")
            }
        }.onFailure { Logger.w(TAG, "备份损坏文件失败: ${it.message}") }
    }

    private fun save(list: List<QuickNote>) {
        try {
            file.parentFile?.mkdirs()
            file.writeText(AppJson.encodeToString(ListSerializer(QuickNote.serializer()), list))
        } catch (e: Exception) {
            Logger.w(TAG, "保存快速记录失败: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "QuickNoteStore"
    }
}

@Serializable
data class QuickNote(
    @SerialName("id") val id: String,
    @SerialName("title") val title: String,
    @SerialName("content") val content: String,
    @SerialName("tags") val tags: List<String>,
    @SerialName("pinned") val pinned: Boolean,
    @SerialName("created_at") val createdAtMillis: Long,
    @SerialName("updated_at") val updatedAtMillis: Long,
)
