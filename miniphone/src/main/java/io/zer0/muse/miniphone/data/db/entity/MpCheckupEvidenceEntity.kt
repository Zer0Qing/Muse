package io.zer0.muse.miniphone.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 案卷里的一条证据。
 *
 * [kind] 取 "message" / "moment" / "call" / "memo" / "location"；[refId] 指向来源那一行的 id，
 * 来源被删时证据文本仍留在案卷里（案卷是对话之外的历史，不跟着级联消失）。
 * [verdict] 是 AI 对这条证据的一句话解读，未生成时为空串。
 */
@Entity(
    tableName = "mp_checkup_evidence",
    indices = [Index("caseId")],
)
data class MpCheckupEvidenceEntity(
    @PrimaryKey val id: String,
    val caseId: String,
    val kind: String,
    val excerpt: String,
    @ColumnInfo(defaultValue = "''") val refId: String = "",
    @ColumnInfo(defaultValue = "''") val verdict: String = "",
    val createdAt: Long,
)
