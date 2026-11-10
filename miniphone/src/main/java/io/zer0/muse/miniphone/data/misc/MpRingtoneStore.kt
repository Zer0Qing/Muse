package io.zer0.muse.miniphone.data.misc

import io.zer0.muse.miniphone.data.db.MiniPhoneDb
import io.zer0.muse.miniphone.data.db.entity.MpRingtoneEntity
import kotlinx.coroutines.flow.Flow

/**
 * 通话铃声门面：按角色与通话种类取可用铃声、添加、删除。
 *
 * 取用直接走 DAO 的 observeFor，它把「空角色」的通用铃声一并带出来 ——
 * 一段通用铃声在语音/视频通话里都该被选到，不因没绑角色而消失。
 * 空白名字或空白素材地址不落库；kind 只接受 voice/video。
 */
internal class MpRingtoneStore(
    private val db: MiniPhoneDb,
    private val now: () -> Long = { System.currentTimeMillis() },
    private val newId: () -> String = { java.util.UUID.randomUUID().toString() },
) {
    /** 某角色某通话种类能用的铃声：专属的 + 通用的（空角色）。 */
    fun observeFor(characterId: String, kind: String): Flow<List<MpRingtoneEntity>> = db.ringtoneDao().observeFor(characterId, kind)

    fun observeAll(): Flow<List<MpRingtoneEntity>> = db.ringtoneDao().observeAll()

    /**
     * 加一段铃声：[characterId] 传空串即为通用。
     * 名字或素材地址空白、kind 非法都不落库，返回 null。
     */
    suspend fun add(characterId: String, name: String, uri: String, kind: String): String? {
        val trimmedName = name.trim()
        val trimmedUri = uri.trim()
        if (trimmedName.isEmpty() || trimmedUri.isEmpty() || kind !in VALID_KINDS) return null
        val id = newId()
        db.ringtoneDao().insert(
            MpRingtoneEntity(
                id = id,
                characterId = characterId,
                name = trimmedName,
                uri = trimmedUri,
                kind = kind,
                createdAt = now(),
            ),
        )
        return id
    }

    suspend fun delete(id: String) {
        db.ringtoneDao().delete(id)
    }

    companion object {
        const val KIND_VOICE: String = "voice"
        const val KIND_VIDEO: String = "video"

        private val VALID_KINDS = setOf(KIND_VOICE, KIND_VIDEO)
    }
}
