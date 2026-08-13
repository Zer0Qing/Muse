package io.zer0.muse.ui.quicknotes
import io.zer0.muse.R

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.data.quicknote.NoteCipher
import io.zer0.muse.data.quicknote.QuickNoteDao
import io.zer0.muse.data.quicknote.QuickNoteEntity
import io.zer0.muse.ui.common.feedback.MuseToast
import io.zer0.muse.tools.reminder.ReminderAlarmReceiver
import io.zer0.muse.tools.reminder.ReminderStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import io.zer0.common.AppJson

/**
 * v1.0.17: 快速记录 UI 状态。
 *
 * @param notes 当前展示的快速记录列表(已应用搜索/标签/文件夹过滤,置顶在前)
 * @param searchKeyword 搜索关键字(空串表示不过滤)
 * @param selectedTag 当前选中的标签(null 表示不过滤)
 * @param selectedFolder 当前选中的文件夹(null 表示全部,空串表示未分类)
 * @param allTags 所有可用标签(从 activeNotes 派生,小写去重排序)
 * @param folders 所有非空文件夹列表(从 observeFolders 派生)
 * @param trashItems 回收站条目
 * @param showTrash 是否展示回收站面板
 * @param isInitialLoading 首次加载标志(避免闪空状态)
 * @param hasMore 是否还有更多记录可加载(分页)
 */
data class QuickNotesUiState(
    val notes: List<QuickNoteEntity> = emptyList(),
    val searchKeyword: String = "",
    val selectedTag: String? = null,
    val selectedFolder: String? = null,
    val allTags: List<String> = emptyList(),
    val folders: List<String> = emptyList(),
    val trashItems: List<QuickNoteEntity> = emptyList(),
    val showTrash: Boolean = false,
    val isInitialLoading: Boolean = true,
    val hasMore: Boolean = false,
)

/**
 * v1.0.17: 快速记录 ViewModel — 替代 QuickNotesScreen 直接持有 QuickNoteStore 的方式。
 *
 * 职责:
 *  - 从 [QuickNoteDao] 的 Flow 收集正常记录 + 回收站记录
 *  - 维护搜索关键字 / 标签 / 文件夹过滤状态,并派生 allTags / folders / notes 视图
 *  - 暴露 add / update / delete / restore / deletePermanent / setPinned / clearTrash 操作
 *  - v1.0.18: 增加 folder / contentType / 提醒 / 加密 / 导入导出 / 分页
 *
 * 数据源:Room(替代 JSON 文件存储),通过 [io.zer0.muse.tools.quicknote.QuickNoteStore.migrateToRoom]
 * 在 App 启动时一次性把旧 JSON 数据导入 Room,后续 Room 即唯一真源。
 *
 * v1.0.18: 构造增加 [context](用于 ReminderStore + AlarmManager 调度提醒)。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class QuickNotesViewModel(
    private val dao: QuickNoteDao,
    private val context: Context,
) : ViewModel() {

    private val reminderStore = ReminderStore(context)
    private val noteCipher = NoteCipher()

    private val _searchKeyword = MutableStateFlow("")
    private val _selectedTag = MutableStateFlow<String?>(null)
    private val _selectedFolder = MutableStateFlow<String?>(null)
    private val _showTrash = MutableStateFlow(false)

    /** v1.0.18: 分页 — 当前加载上限(到达底部时 loadMore 递增)。 */
    private val _currentLimit = MutableStateFlow(DEFAULT_LIMIT)
    private val currentLimit: StateFlow<Int> = _currentLimit

    /** 正常记录(deleted=0),按 updatedAt 降序、置顶在前。 */
    private val activeNotesFlow = dao.observeActive(limit = MAX_LIMIT)

    /** 回收站记录(deleted=1),按 deletedAt 降序。 */
    private val trashFlow = dao.observeTrash()

    /** v1.0.18: 所有非空文件夹。 */
    private val foldersFlow = dao.observeFolders()

    /**
     * 综合 UI 状态:
     *  - notes: 应用搜索关键字 + 标签 + 文件夹过滤后的列表(再按 currentLimit 截断)
     *  - allTags: 从全量 activeNotes 派生(小写去重排序)
     *  - folders: 从 foldersFlow 取
     *  - trashItems: 直接从 trashFlow 取
     *  - showTrash: 由用户切换
     *  - hasMore: filtered.size > currentLimit(还有未加载的记录)
     */
    val state: StateFlow<QuickNotesUiState> =
        combine(
            activeNotesFlow,
            trashFlow,
            foldersFlow,
            _searchKeyword,
            _selectedTag,
            _selectedFolder,
            _showTrash,
            currentLimit,
        ) { values ->
            @Suppress("UNCHECKED_CAST")
            val active = values[0] as List<QuickNoteEntity>
            @Suppress("UNCHECKED_CAST")
            val trash = values[1] as List<QuickNoteEntity>
            @Suppress("UNCHECKED_CAST")
            val folders = values[2] as List<String>
            val keyword = values[3] as String
            val tag = values[4] as String?
            val folder = values[5] as String?
            val showTrash = values[6] as Boolean
            val limit = values[7] as Int

            val filtered = filterNotes(active, keyword, tag, folder)
            val allTags = active
                .flatMap { it.tags }
                .map { it.lowercase() }
                .distinct()
                .sorted()
            QuickNotesUiState(
                notes = filtered.take(limit),
                searchKeyword = keyword,
                selectedTag = tag,
                selectedFolder = folder,
                allTags = allTags,
                folders = folders,
                trashItems = trash,
                showTrash = showTrash,
                isInitialLoading = false,
                hasMore = filtered.size > limit,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = QuickNotesUiState(),
        )

    /** 更新搜索关键字。空串表示清除搜索。 */
    fun onSearchKeywordChange(keyword: String) {
        _searchKeyword.value = keyword
    }

    /** 切换标签过滤。再次点击同一标签取消选中。 */
    fun onTagSelected(tag: String) {
        _selectedTag.value = if (_selectedTag.value == tag) null else tag
    }

    /**
     * v1.0.18: 切换文件夹过滤。
     * - null: 显示全部文件夹
     * - "": 未分类(folder 为空的记录)
     * - 其他: 指定文件夹
     * 再次点击同一文件夹取消选中(回到全部)。
     */
    fun onFolderSelected(folder: String?) {
        _selectedFolder.value = if (_selectedFolder.value == folder) null else folder
    }

    /** 切换回收站面板显示/隐藏。 */
    fun toggleTrash(show: Boolean) {
        _showTrash.value = show
    }

    /**
     * v1.0.18: 加载更多(分页)。
     * 到达列表底部时调用,递增 limit 后刷新视图。
     */
    fun loadMore() {
        if (_currentLimit.value >= MAX_LIMIT) return
        _currentLimit.value = (_currentLimit.value + DEFAULT_LIMIT).coerceAtMost(MAX_LIMIT)
    }

    /**
     * 添加一条快速记录。
     *
     * v1.0.18: 增加 folder / contentType 参数(默认空文件夹 + plain)。
     */
    fun add(
        title: String,
        content: String,
        tags: List<String>,
        folder: String = "",
        contentType: String = "plain",
    ) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            dao.upsert(
                QuickNoteEntity(
                    id = java.util.UUID.randomUUID().toString(),
                    title = title,
                    content = content,
                    tags = tags,
                    pinned = false,
                    deleted = false,
                    deletedAt = 0,
                    createdAt = now,
                    updatedAt = now,
                    folder = folder,
                    contentType = contentType,
                ),
            )
        }
    }

    /**
     * 更新指定记录(局部更新)。
     *
     * v1.0.18: 增加 folder / contentType / reminderAt 参数(传 null 表示不修改)。
     */
    fun update(
        id: String,
        title: String? = null,
        content: String? = null,
        tags: List<String>? = null,
        folder: String? = null,
        contentType: String? = null,
        reminderAt: Long? = null,
    ) {
        viewModelScope.launch {
            val existing = dao.getById(id) ?: return@launch
            val newContent = content
            // 审计修复 (0.6): 空串也视为"未改内容",不得用空串加密覆盖原密文。
            // 对话框打开加密笔记时 content 为空,若用户只改标题,content 传空串,
            // 原实现 newContent != null 会把 encrypt("") 写入,原内容永久丢失。
            val finalEncryptedContent = if (existing.encrypted) {
                if (newContent != null && newContent.isNotBlank()) {
                    runCatching { noteCipher.encrypt(newContent) }.getOrElse {
                        Logger.w(TAG, "快捷笔记更新加密失败: ${it.message}")
                        existing.encryptedContent
                    }
                } else existing.encryptedContent
            } else existing.encryptedContent
            val finalContent = if (existing.encrypted) "" else (newContent ?: existing.content)
            dao.upsert(
                existing.copy(
                    title = title ?: existing.title,
                    content = finalContent,
                    encryptedContent = finalEncryptedContent,
                    tags = tags ?: existing.tags,
                    folder = folder ?: existing.folder,
                    contentType = contentType ?: existing.contentType,
                    reminderAt = reminderAt ?: existing.reminderAt,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
            // 同步提醒闹钟
            if (reminderAt != null) {
                syncReminder(existing.id, existing.title, existing.content, reminderAt)
            }
        }
    }

    /** 移入回收站(soft delete)。 */
    fun delete(id: String) {
        viewModelScope.launch { dao.moveToTrash(id) }
    }

    /** 从回收站恢复。 */
    fun restore(id: String) {
        viewModelScope.launch { dao.restore(id) }
    }

    /** 永久删除单条记录(不可恢复)。 */
    fun deletePermanent(id: String) {
        viewModelScope.launch { dao.deletePermanent(id) }
    }

    /** 切换置顶状态。 */
    fun setPinned(id: String, pinned: Boolean) {
        viewModelScope.launch { dao.setPinned(id, pinned) }
    }

    /**
     * v1.0.18: 设置文件夹。
     * folder 传空串表示移出文件夹(未分类)。
     */
    fun setFolder(id: String, folder: String) {
        viewModelScope.launch { dao.setFolder(id, folder) }
    }

    /**
     * v1.0.18: 切换内容类型(plain ↔ markdown)。
     */
    fun setContentType(id: String, contentType: String) {
        viewModelScope.launch { dao.setContentType(id, contentType) }
    }

    /**
     * v1.0.18: 设置提醒。
     * reminderAt=0 表示取消提醒;否则通过 ReminderStore + AlarmManager 调度。
     */
    fun setReminder(id: String, title: String, message: String, reminderAt: Long) {
        viewModelScope.launch {
            dao.setReminderAt(id, reminderAt)
            syncReminder(id, title, message, reminderAt)
        }
    }

    /**
     * v1.0.18: 取消提醒。
     */
    fun cancelReminder(id: String) {
        viewModelScope.launch {
            dao.setReminderAt(id, 0)
            cancelAlarm(id)
            // ReminderStore 中也可能有对应条目,尝试移除
            reminderStore.list().firstOrNull { it.id == id }?.let { reminderStore.remove(it.id) }
        }
    }

    /**
     * v1.0.18: 切换加密状态。
     *
     * 加密已实现: NoteCipher 使用 Android Keystore 的 AES-GCM 真实加解密,
     * 密文格式 base64(iv):base64(ciphertext),导出/导入保留 encryptedContent。
     * 解密失败时按旧占位密文迁移处理(避免旧版本数据丢失)。
     */
    fun setEncrypted(id: String, encrypted: Boolean) {
        viewModelScope.launch {
            val existing = dao.getById(id) ?: return@launch
            if (encrypted) {
                // 审计修复 (0.7): 加密失败不得降级明文。
                // 原实现 getOrElse 返回明文写入 encryptedContent,UI 显示"已加密"实为明文,
                // 用户误以为受保护。失败时保持原样并提示。
                if (existing.content.isBlank()) {
                    MuseToast.show("没有可加密的内容")
                    return@launch
                }
                val cipher = runCatching { noteCipher.encrypt(existing.content) }.getOrNull()
                if (cipher == null) {
                    MuseToast.show("加密失败,笔记保持原样")
                    Logger.w(TAG, "快捷笔记加密失败,保持明文未标记加密")
                    return@launch
                }
                dao.setEncrypted(id, true, cipher)
                MuseToast.show("已加密")
            } else {
                // 审计修复 (0.7): 解密失败保留密文与加密标记,不得把密文当明文写入 content。
                if (existing.encryptedContent.isNotBlank()) {
                    val plain = runCatching { noteCipher.decrypt(existing.encryptedContent) }.getOrNull()
                    if (plain == null) {
                        MuseToast.show("解密失败,笔记保持加密")
                        Logger.w(TAG, "快捷笔记解密失败,保留密文")
                        return@launch
                    }
                    dao.setEncrypted(id, false, plain)
                } else {
                    dao.setEncrypted(id, false, existing.content)
                }
                MuseToast.show("已解密")
            }
        }
    }

    /**
     * v1.0.18: 添加图片附件路径。
     * attachmentsJson 存储为 JSON 数组字符串。
     */
    fun addAttachment(id: String, path: String) {
        viewModelScope.launch {
            val existing = dao.getById(id) ?: return@launch
            val list = parseAttachments(existing.attachmentsJson).toMutableList()
            if (path !in list) list.add(path)
            dao.upsert(existing.copy(attachmentsJson = encodeAttachments(list), updatedAt = System.currentTimeMillis()))
        }
    }

    /** 清空整个回收站(先取一次最新快照再逐条永久删除)。 */
    fun clearTrash() {
        viewModelScope.launch {
            val trash = trashFlow.first()
            trash.forEach { dao.deletePermanent(it.id) }
        }
    }

    // ── v1.0.18: 导出 / 导入 ──────────────────────────────────────────────────

    /**
     * v1.0.18: 导出全部正常记录为 Markdown 字符串。
     *
     * 每条记录格式:
     * ```
     * # 标题
     *
     * 正文
     *
     * > 标签: #tag1 #tag2 | 文件夹: folder | 更新时间: yyyy-MM-dd HH:mm
     * ---
     * ```
     */
    suspend fun exportToMarkdown(): String {
        val notes = dao.observeActive(limit = MAX_LIMIT).first()
        if (notes.isEmpty()) return ""
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
        return buildString {
            notes.forEach { note ->
                if (note.title.isNotBlank()) {
                    appendLine("# ${note.title}")
                    appendLine()
                }
                if (note.content.isNotBlank()) {
                    appendLine(note.content)
                    appendLine()
                }
                val meta = buildList {
                    if (note.tags.isNotEmpty()) add(context.getString(R.string.quick_notes_export_tags, note.tags.joinToString(" ") { "#$it" }))
                    if (note.folder.isNotBlank()) add(context.getString(R.string.quick_notes_export_folder, note.folder))
                    add(context.getString(R.string.quick_notes_export_updated_at, fmt.format(java.util.Date(note.updatedAt))))
                }.joinToString(" | ")
                appendLine("> $meta")
                appendLine("---")
                appendLine()
            }
        }.trimEnd()
    }

    /**
     * v1.0.18: 导出全部正常记录为 JSON 字符串。
     * 包含完整字段(folder / contentType / tags / 时间戳等),可用于跨设备迁移。
     */
    suspend fun exportToJson(): String {
        val notes = dao.observeActive(limit = MAX_LIMIT).first()
        val dtos = notes.map { it.toExportDto() }
        return AppJson.encodeToString(ListSerializer(QuickNoteExportDto.serializer()), dtos)
    }

    /**
     * v1.0.18: 从 JSON 字符串导入记录。
     *
     * - JSON 格式由 [exportToJson] 产出([QuickNoteExportDto] 列表)
     * - 已存在的 id 会被覆盖(OnConflictStrategy.REPLACE)
     * - 导入的记录 deleted 标记为 false(强制恢复正常状态)
     *
     * @return 成功导入的记录数量
     */
    suspend fun importFromJson(json: String): Int {
        return try {
            val dtos = AppJson.decodeFromString(ListSerializer(QuickNoteExportDto.serializer()), json)
            dtos.forEach { dto ->
                dao.upsert(dto.toEntity())
            }
            dtos.size
        } catch (e: Exception) {
            Logger.w(TAG, "导入快速记录失败: ${e.message}")
            0
        }
    }

    // ── 内部辅助 ──────────────────────────────────────────────────────────────

    /**
     * 应用搜索关键字 + 标签 + 文件夹过滤。
     *
     * 关键字匹配 title/content/tags(大小写不敏感);
     * 标签匹配 tags 列表(大小写不敏感,精确匹配单个标签);
     * 文件夹匹配 folder 字段(空串 folder 匹配未分类)。
     *
     * v1.0.18(5.10 全文搜索优化): 当存在搜索关键字时,按相关度排序而非保持原顺序。
     * 相关度评分(越高越靠前):
     *  - 标题完全相等(+100)/ 标题前缀匹配(+60)/ 标题包含(+30)
     *  - 内容包含(+10)/ 标签包含(+15)
     *  - 置顶额外加权(+50),让置顶记录在相关度相近时优先
     * 同分时按 updatedAt 降序(新记录优先)。无关键字时保持原顺序(置顶在前 + updatedAt 降序)。
     */
    private fun filterNotes(
        notes: List<QuickNoteEntity>,
        keyword: String,
        tag: String?,
        folder: String?,
    ): List<QuickNoteEntity> {
        var filtered = notes
        if (keyword.isNotBlank()) {
            val kw = keyword.lowercase()
            filtered = filtered.filter {
                it.title.lowercase().contains(kw) ||
                    it.content.lowercase().contains(kw) ||
                    it.tags.any { t -> t.lowercase().contains(kw) }
            }
        }
        if (!tag.isNullOrBlank()) {
            filtered = filtered.filter { note ->
                note.tags.any { it.equals(tag, ignoreCase = true) }
            }
        }
        if (folder != null) {
            filtered = filtered.filter { it.folder == folder }
        }
        // v1.0.18(5.10): 搜索时按相关度排序
        if (keyword.isBlank()) return filtered
        val kw = keyword.lowercase()
        return filtered
            .map { it to relevanceScore(it, kw) }
            .sortedWith(
                compareByDescending<Pair<QuickNoteEntity, Int>> { it.second }
                    .thenByDescending { it.first.pinned }
                    .thenByDescending { it.first.updatedAt },
            )
            .map { it.first }
    }

    /** 计算单条记录对关键字的相关度得分(越大越相关)。 */
    private fun relevanceScore(note: QuickNoteEntity, kw: String): Int {
        var score = 0
        val title = note.title.lowercase()
        val content = note.content.lowercase()
        when {
            title == kw -> score += 100
            title.startsWith(kw) -> score += 60
            title.contains(kw) -> score += 30
        }
        if (content.contains(kw)) score += 10
        if (note.tags.any { it.lowercase().contains(kw) }) score += 15
        if (note.pinned) score += 50
        return score
    }

    /**
     * v1.0.18: 同步提醒闹钟。
     * reminderAt=0 取消闹钟,否则注册精确闹钟。
     */
    private fun syncReminder(id: String, title: String, message: String, reminderAt: Long) {
        if (reminderAt <= 0) {
            cancelAlarm(id)
            return
        }
        // 先存入 ReminderStore(触发后由 ReminderAlarmReceiver 移除)
        reminderStore.list().firstOrNull { it.id == id }?.let { reminderStore.remove(it.id) }
        reminderStore.add(title, message, reminderAt)
        scheduleAlarm(id, title, message, reminderAt)
    }

    /** 通过 AlarmManager 注册闹钟(按 ToolRegistry.scheduleAlarm 实现)。 */
    private fun scheduleAlarm(id: String, title: String, message: String, triggerAtMillis: Long): Boolean {
        return resultOf {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, ReminderAlarmReceiver::class.java).apply {
                putExtra(ReminderAlarmReceiver.EXTRA_ID, id)
                putExtra(ReminderAlarmReceiver.EXTRA_TITLE, title)
                putExtra(ReminderAlarmReceiver.EXTRA_MESSAGE, message)
            }
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val pi = PendingIntent.getBroadcast(context, id.hashCode(), intent, flags)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
                Logger.w(TAG, "无 SCHEDULE_EXACT_ALARM 权限,无法设置精确提醒: id=$id")
                return@resultOf false
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
            } else {
                am.setExact(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
            }
            true
        }.onError { msg, _ -> Logger.w(TAG, "scheduleAlarm failed: $msg") }
            .getOrNull() ?: false
    }

    /** 取消 AlarmManager 闹钟。 */
    private fun cancelAlarm(id: String) {
        resultOf {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, ReminderAlarmReceiver::class.java)
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val pi = PendingIntent.getBroadcast(context, id.hashCode(), intent, flags)
            am.cancel(pi)
            pi.cancel()
        }.onError { msg, _ -> Logger.w(TAG, "cancelAlarm failed: $msg") }
    }

    companion object {
        private const val TAG = "QuickNotesViewModel"
        /** v1.0.18: 首次加载条数(分页起始 limit)。 */
        private const val DEFAULT_LIMIT = 50
        /** v1.0.18: 最大加载条数(observeActive 的 LIMIT 上限)。 */
        private const val MAX_LIMIT = 500

        /** 解析 attachmentsJson 为路径列表。 */
        fun parseAttachments(json: String): List<String> {
            if (json.isBlank()) return emptyList()
            return try {
                AppJson.decodeFromString<List<String>>(json)
            } catch (_: Exception) {
                emptyList()
            }
        }

        /** 编码路径列表为 attachmentsJson。 */
        fun encodeAttachments(list: List<String>): String {
            return if (list.isEmpty()) "" else AppJson.encodeToString(list)
        }
    }
}

/**
 * v1.0.18: 快速记录导出/导入 DTO(JSON 序列化格式)。
 * 与 [QuickNoteEntity] 字段对齐,但不含 Room 注解,便于跨设备迁移。
 */
@Serializable
data class QuickNoteExportDto(
    @SerialName("id") val id: String,
    @SerialName("title") val title: String,
    @SerialName("content") val content: String,
    @SerialName("tags") val tags: List<String>,
    @SerialName("pinned") val pinned: Boolean,
    @SerialName("folder") val folder: String = "",
    @SerialName("content_type") val contentType: String = "plain",
    @SerialName("attachments_json") val attachmentsJson: String = "",
    @SerialName("reminder_at") val reminderAt: Long = 0,
    @SerialName("created_at") val createdAt: Long,
    @SerialName("updated_at") val updatedAt: Long,
    @SerialName("encrypted") val encrypted: Boolean = false,
    @SerialName("encrypted_content") val encryptedContent: String = "",
)

/** 把 [QuickNoteEntity] 转换为导出 DTO。 */
private fun QuickNoteEntity.toExportDto(): QuickNoteExportDto = QuickNoteExportDto(
    id = id,
    title = title,
    content = content,
    tags = tags,
    pinned = pinned,
    folder = folder,
    contentType = contentType,
    attachmentsJson = attachmentsJson,
    reminderAt = reminderAt,
    createdAt = createdAt,
    updatedAt = updatedAt,
    encrypted = encrypted,
    encryptedContent = encryptedContent,
)

/** 把导出 DTO 转换为 [QuickNoteEntity](导入用,deleted=false/encrypted=false)。 */
private fun QuickNoteExportDto.toEntity(): QuickNoteEntity = QuickNoteEntity(
    id = id,
    title = title,
    content = content,
    tags = tags,
    pinned = pinned,
    deleted = false,
    deletedAt = 0,
    createdAt = createdAt,
    updatedAt = updatedAt,
    folder = folder,
    contentType = contentType,
    attachmentsJson = attachmentsJson,
    reminderAt = reminderAt,
    encrypted = encrypted,
    encryptedContent = encryptedContent,
)
