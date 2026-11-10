package io.zer0.muse.miniphone.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.zer0.muse.miniphone.data.db.entity.MpCheckupEvidenceEntity
import kotlinx.coroutines.flow.Flow

/** 案卷证据读写：按案卷看证据，解读回填只改 verdict。 */
@Dao
interface MpCheckupEvidenceDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(evidence: MpCheckupEvidenceEntity)

    @Query("SELECT * FROM mp_checkup_evidence WHERE caseId = :caseId ORDER BY createdAt ASC")
    fun observeByCase(caseId: String): Flow<List<MpCheckupEvidenceEntity>>

    @Query("UPDATE mp_checkup_evidence SET verdict = :verdict WHERE id = :id")
    suspend fun setVerdict(id: String, verdict: String)

    @Query("DELETE FROM mp_checkup_evidence WHERE caseId = :caseId")
    suspend fun deleteByCase(caseId: String)
}
