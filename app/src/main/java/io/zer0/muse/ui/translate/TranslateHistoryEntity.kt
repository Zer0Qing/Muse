package io.zer0.muse.ui.translate

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * v1.0.17: 翻译历史持久化。
 */
@Entity(
    tableName = "translate_history",
    indices = [
        Index(value = ["created_at"]),
        Index(value = ["source_language", "target_language"]),
        Index(value = ["favorite"]),
    ],
)
data class TranslateHistoryEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "source_text") val sourceText: String,
    @ColumnInfo(name = "translated_text") val translatedText: String,
    @ColumnInfo(name = "source_language") val sourceLanguage: String,
    @ColumnInfo(name = "target_language") val targetLanguage: String,
    @ColumnInfo(name = "style") val style: String = "通用",
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    // v1.0.30 gap4.3: 翻译收藏夹,1=已收藏,0=未收藏
    @ColumnInfo(name = "favorite") val favorite: Boolean = false,
)
