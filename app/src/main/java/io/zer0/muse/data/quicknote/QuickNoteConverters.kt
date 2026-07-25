package io.zer0.muse.data.quicknote

import androidx.room.TypeConverter

/**
 * v1.0.17: 快速记录标签 TypeConverter。
 *
 * 把 List<String> 序列化为逗号分隔字符串存储(tags 列),便于 SQL LIKE 标签检索。
 * 反序列化时按逗号切分并 trim,空字符串返回 emptyList。
 *
 * 注意:标签值本身不应包含逗号(QuickNotesScreen UI 仅用 #标签 语法,无逗号),
 * 因此无需转义。若未来需要支持含逗号的标签,改用 JSON 序列化(参考 assistants.tagsJson)。
 */
class QuickNoteConverters {
    @TypeConverter
    fun fromStringList(value: List<String>): String = value.joinToString(",")

    @TypeConverter
    fun toStringList(value: String): List<String> =
        if (value.isBlank()) emptyList() else value.split(",").map { it.trim() }.filter { it.isNotBlank() }
}
