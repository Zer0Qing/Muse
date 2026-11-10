package io.zer0.muse.miniphone.data.misc

import io.zer0.muse.miniphone.data.db.MiniPhoneDb
import io.zer0.muse.miniphone.data.db.entity.MpRedPacketEntity
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray

/**
 * 群红包门面：发红包、领红包。
 *
 * 金额只是角色扮演的展示文案，不做任何真实账务，也不接任何支付通道。
 * 领取名单 [MpRedPacketEntity.claimedByJson] 是一段 JSON 数组文本，
 * 同一个人不能重复领；群红包允许多人各领一次，领取后仍保持可领状态。
 * 空白金额不发；坏掉的名单文本一律当「没人领过」处理，不让一条脏数据卡住整个红包。
 */
internal class MpRedPacketStore(
    private val db: MiniPhoneDb,
    private val now: () -> Long = { System.currentTimeMillis() },
    private val newId: () -> String = { java.util.UUID.randomUUID().toString() },
) {
    fun observeByThread(threadId: String): Flow<List<MpRedPacketEntity>> = db.redPacketDao().observeByThread(threadId)

    /** 发一个红包，初始为可领、名单为空；金额空白时不落库，返回 null。 */
    suspend fun send(threadId: String, senderId: String, amount: String, note: String = ""): String? {
        val text = amount.trim()
        if (text.isEmpty()) return null
        val id = newId()
        db.redPacketDao().insert(
            MpRedPacketEntity(
                id = id,
                threadId = threadId,
                senderId = senderId,
                amount = text,
                note = note.trim(),
                state = STATE_OPEN,
                claimedByJson = EMPTY_CLAIMERS,
                createdAt = now(),
            ),
        )
        return id
    }

    /**
     * [claimerId] 领这个红包：领过就拒绝，红包不存在或已不可领也拒绝。
     * 成功返回 true 并把领取者追加进名单；同一人重复领返回 false，名单不变。
     */
    suspend fun claim(id: String, claimerId: String): Boolean {
        val packet = db.redPacketDao().get(id) ?: return false
        if (packet.state != STATE_OPEN || claimerId.isBlank()) return false
        val claimers = decodeClaimers(packet.claimedByJson)
        if (claimerId in claimers) return false
        db.redPacketDao().setState(id, packet.state, encodeClaimers(claimers + claimerId))
        return true
    }

    suspend fun deleteByThread(threadId: String) {
        db.redPacketDao().deleteByThread(threadId)
    }

    companion object {
        const val STATE_OPEN: String = "open"
        const val STATE_CLAIMED: String = "claimed"
        const val STATE_EXPIRED: String = "expired"

        private const val EMPTY_CLAIMERS = "[]"

        /** 领取者列表 → JSON 文本；空列表存 `[]`。 */
        fun encodeClaimers(ids: List<String>): String {
            val array = JSONArray()
            ids.forEach { id -> array.put(id) }
            return array.toString()
        }

        /** JSON 文本 → 领取者列表；空串、空数组、坏文本都得到空列表。 */
        fun decodeClaimers(json: String?): List<String> {
            if (json.isNullOrBlank()) return emptyList()
            return runCatching {
                val array = JSONArray(json)
                (0 until array.length()).mapNotNull { index ->
                    array.optString(index).takeIf { id -> id.isNotBlank() }
                }
            }.getOrDefault(emptyList())
        }
    }
}
