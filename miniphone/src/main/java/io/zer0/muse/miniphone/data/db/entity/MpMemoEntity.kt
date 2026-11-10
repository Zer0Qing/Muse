package io.zer0.muse.miniphone.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 一条备忘录。[characterId] 为空串表示不挂在任何角色下的通用便签。
 * 与日记分开：日记按天自动记，备忘录是随手写的、可反复改的短条目。
 */
@Entity(
    tableName = "mp_memo",
    indices = [Index("characterId"), Index("updatedAt")],
)
data class MpMemoEntity(
    @PrimaryKey val id: String,
    val characterId: String,
    val content: String,
    val createdAt: Long,
    val updatedAt: Long,
)
