package io.zer0.muse.tools

import android.content.Context
import io.zer0.muse.R
import io.zer0.muse.data.quicknote.QuickNoteEntity
import io.zer0.muse.data.session.MuseDb

/**
 * P1-3b 拆域：快速记录工具实现。
 * 由 QuickNoteToolsRegistrar 注册到 ToolRegistry。
 */
class QuickNoteToolsImpl(private val context: Context) {

    suspend fun add(args: Map<String, String>): String {
        val title = args["title"]?.takeIf { it.isNotBlank() }
            ?: return context.getString(R.string.tool_quick_note_missing_title)
        val content = args["content"] ?: ""
        val tags = args["tags"]?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList()
        val id = java.util.UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        MuseDb.get(context).quickNoteDao().upsert(
            QuickNoteEntity(
                id = id,
                title = title,
                content = content,
                tags = tags,
                pinned = false,
                deleted = false,
                deletedAt = 0,
                createdAt = now,
                updatedAt = now,
            ),
        )
        return context.getString(R.string.tool_quick_note_added, id, title)
    }

    suspend fun list(args: Map<String, String>): String {
        val keyword = args["keyword"]
        val tag = args["tag"]
        val limit = args["limit"]?.toIntOrNull()?.takeIf { it > 0 } ?: 20
        val list = MuseDb.get(context).quickNoteDao().search(keyword, tag, limit)
        return formatList(list)
    }

    suspend fun search(args: Map<String, String>): String {
        val keyword = args["keyword"]
        val limit = args["limit"]?.toIntOrNull()?.takeIf { it > 0 } ?: 20
        val list = MuseDb.get(context).quickNoteDao().search(keyword, null, limit)
        return formatList(list)
    }

    suspend fun get(args: Map<String, String>): String {
        val id = args["id"]?.takeIf { it.isNotBlank() }
            ?: return context.getString(R.string.tool_quick_note_missing_id)
        val note = MuseDb.get(context).quickNoteDao().getById(id)
            ?: return context.getString(R.string.tool_quick_note_not_found, id)
        return formatNote(note)
    }

    suspend fun update(args: Map<String, String>): String {
        val id = args["id"]?.takeIf { it.isNotBlank() }
            ?: return context.getString(R.string.tool_quick_note_missing_id)
        val dao = MuseDb.get(context).quickNoteDao()
        val existing = dao.getById(id)
            ?: return context.getString(R.string.tool_quick_note_not_found, id)
        val title = args["title"]
        val content = args["content"]
        val tags = args["tags"]?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }
        dao.upsert(
            existing.copy(
                title = title ?: existing.title,
                content = content ?: existing.content,
                tags = tags ?: existing.tags,
                updatedAt = System.currentTimeMillis(),
            ),
        )
        return context.getString(R.string.tool_quick_note_updated, id)
    }

    suspend fun delete(args: Map<String, String>): String {
        val id = args["id"]?.takeIf { it.isNotBlank() }
            ?: return context.getString(R.string.tool_quick_note_missing_id)
        val dao = MuseDb.get(context).quickNoteDao()
        val existing = dao.getById(id)
            ?: return context.getString(R.string.tool_quick_note_not_found, id)
        if (!existing.deleted) {
            dao.moveToTrash(id)
        }
        return context.getString(R.string.tool_quick_note_deleted, id)
    }

    suspend fun pin(args: Map<String, String>): String {
        val id = args["id"]?.takeIf { it.isNotBlank() }
            ?: return context.getString(R.string.tool_quick_note_missing_id)
        val dao = MuseDb.get(context).quickNoteDao()
        dao.getById(id)
            ?: return context.getString(R.string.tool_quick_note_not_found, id)
        val pinned = args["pinned"]?.equals("true", ignoreCase = true) ?: false
        dao.setPinned(id, pinned)
        return context.getString(
            R.string.tool_quick_note_pinned,
            id,
            if (pinned) context.getString(R.string.tool_yes) else context.getString(R.string.tool_no),
        )
    }

    private fun formatList(list: List<QuickNoteEntity>): String {
        if (list.isEmpty()) return context.getString(R.string.tool_quick_note_list_empty)
        val sb = StringBuilder(context.getString(R.string.tool_quick_note_list_header, list.size))
        list.forEach {
            sb.appendLine(
                context.getString(
                    R.string.tool_quick_note_list_item,
                    it.id,
                    if (it.pinned) "[顶]" else "",
                    it.title,
                    it.tags.joinToString(","),
                ),
            )
        }
        return sb.toString().trimEnd()
    }

    private fun formatNote(note: QuickNoteEntity): String {
        return context.getString(
            R.string.tool_quick_note_item_detail,
            note.id,
            note.title,
            if (note.pinned) context.getString(R.string.tool_yes) else context.getString(R.string.tool_no),
            note.tags.joinToString(","),
            note.content,
        )
    }
}
