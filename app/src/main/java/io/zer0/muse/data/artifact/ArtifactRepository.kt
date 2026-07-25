package io.zer0.muse.data.artifact

import kotlinx.coroutines.flow.Flow

/**
 * Artifact 仓库:封装 [ArtifactDao],暴露同名的增删查方法。
 */
class ArtifactRepository(
    private val dao: ArtifactDao,
) {
    suspend fun upsert(entity: ArtifactEntity) = dao.upsert(entity)

    suspend fun delete(id: String) = dao.deleteById(id)

    fun observeBySession(sessionId: String): Flow<List<ArtifactEntity>> = dao.observeBySession(sessionId)

    suspend fun getByMessage(messageId: String): List<ArtifactEntity> = dao.getByMessage(messageId)

    fun observeByMessage(messageId: String): Flow<List<ArtifactEntity>> = dao.observeByMessage(messageId)

    suspend fun getById(id: String): ArtifactEntity? = dao.getById(id)
}
