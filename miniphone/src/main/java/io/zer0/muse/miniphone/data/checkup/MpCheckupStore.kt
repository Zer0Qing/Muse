package io.zer0.muse.miniphone.data.checkup

import io.zer0.muse.miniphone.api.MiniPhoneHost
import io.zer0.muse.miniphone.data.db.MiniPhoneDb
import io.zer0.muse.miniphone.data.db.MpActorType
import io.zer0.muse.miniphone.data.db.entity.MpCheckupCaseEntity
import io.zer0.muse.miniphone.data.db.entity.MpCheckupEvidenceEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/** 查岗方向：本机用户查角色，或角色查本机用户。落库用 [wire]。 */
internal enum class MpCheckupDirection(val wire: String) {
    OUTGOING("outgoing"),
    INCOMING("incoming"),
    ;

    companion object {
        fun fromWire(wire: String): MpCheckupDirection = entries.firstOrNull { it.wire == wire } ?: OUTGOING
    }
}

/** 取证强度：决定从既有记录里取多少条证据。 */
internal enum class MpCheckupIntensity(val wire: String, val messages: Int, val moments: Int, val calls: Int) {
    LIGHT("light", messages = 3, moments = 1, calls = 0),
    STANDARD("standard", messages = 10, moments = 3, calls = 3),
    DEEP("deep", messages = 30, moments = 5, calls = 5),
    ;

    companion object {
        fun fromWire(wire: String): MpCheckupIntensity = entries.firstOrNull { it.wire == wire } ?: STANDARD
    }
}

/**
 * 查岗案卷门面：立案 → 从既有记录取证 → 让角色解读 → 结案。
 *
 * 取证只读小手机自己的库（消息/动态/通话/备忘录），不碰宿主数据。
 * 证据正文只存「原文或记号」，不存任何面向用户的成品文案 ——
 * 通话证据存 `call_voice` / `call_video` 加秒数，由界面按当前语言渲染，
 * 与 [io.zer0.muse.miniphone.ui.chat.MpSentinel] 同一约定。
 *
 * 解读走宿主的通用一次性生成桥；拿不到结论就把案卷标成「未完成」并留空结论，
 * 不编一句看起来像结论的话 —— 用户要能分清「查过了没发现」和「根本没查成」。
 */
internal class MpCheckupStore(
    private val db: MiniPhoneDb,
    private val host: MiniPhoneHost,
    private val now: () -> Long = { System.currentTimeMillis() },
    private val newId: () -> String = { java.util.UUID.randomUUID().toString() },
) {
    fun observeRecent(limit: Int = 50): Flow<List<MpCheckupCaseEntity>> = db.checkupCaseDao().observeRecent(limit)

    fun observeByCharacter(characterId: String): Flow<List<MpCheckupCaseEntity>> = db.checkupCaseDao().observeByCharacter(characterId)

    fun observeEvidence(caseId: String): Flow<List<MpCheckupEvidenceEntity>> = db.checkupEvidenceDao().observeByCase(caseId)

    /** 立案并取证；返回案卷 id。取证为空也照样立案，让「没查到」这件事本身可见。 */
    suspend fun openCase(
        characterId: String,
        direction: MpCheckupDirection,
        intensity: MpCheckupIntensity,
        initiatorId: String = "",
    ): String {
        val id = newId()
        val createdAt = now()
        db.checkupCaseDao().insert(
            MpCheckupCaseEntity(
                id = id,
                characterId = characterId,
                initiatorId = initiatorId,
                direction = direction.wire,
                intensity = intensity.wire,
                status = STATUS_RUNNING,
                createdAt = createdAt,
            ),
        )
        collectEvidence(id, characterId, intensity, createdAt)
        return id
    }

    /** 让角色逐条解读证据并给出结论；拿不到结论就标未完成。 */
    suspend fun interpret(caseId: String) {
        val case = db.checkupCaseDao().get(caseId) ?: return
        val evidence = db.checkupEvidenceDao().observeByCase(caseId).first()
        evidence.forEach { item ->
            val verdict = host.composeScene(
                case.characterId,
                "有人在查你的岗，看到这条记录：「${item.excerpt}」。用一句话说明它意味着什么，不要替自己辩解。",
            )
            if (!verdict.isNullOrBlank()) db.checkupEvidenceDao().setVerdict(item.id, verdict.trim())
        }
        val summary = host.composeScene(
            case.characterId,
            buildString {
                appendLine("有人查了你的岗，取到 ${evidence.size} 条证据：")
                evidence.take(MAX_SUMMARY_EVIDENCE).forEach { appendLine("- ${it.excerpt}") }
                appendLine("用两三句话给出你的结论。")
            },
        )
        val finishedAt = now()
        if (summary.isNullOrBlank()) {
            db.checkupCaseDao().finish(caseId, STATUS_FAILED, "", finishedAt)
        } else {
            db.checkupCaseDao().finish(caseId, STATUS_DONE, summary.trim(), finishedAt)
        }
    }

    suspend fun deleteCase(caseId: String) {
        db.checkupEvidenceDao().deleteByCase(caseId)
        db.checkupCaseDao().delete(caseId)
    }

    private suspend fun collectEvidence(caseId: String, characterId: String, intensity: MpCheckupIntensity, createdAt: Long) {
        val threads = db.threadDao().observeThreads().first()
            .filter { it.characterId == characterId || it.isGroup }
        threads.forEach { thread ->
            db.messageDao().pageMessages(thread.id, intensity.messages, 0)
                .filter { it.sender == MpActorType.CHARACTER && it.characterId == characterId }
                .forEach { message -> addEvidence(caseId, KIND_MESSAGE, message.content, message.id, createdAt) }
        }
        db.momentDao().observeByAuthor(characterId).first()
            .take(intensity.moments)
            .forEach { moment -> addEvidence(caseId, KIND_MOMENT, moment.content, moment.id, createdAt) }
        if (intensity.calls > 0) {
            db.callSessionDao().observeHistory(intensity.calls).first()
                .filter { it.characterId == characterId }
                .forEach { call ->
                    val kind = if (call.kind == "video") KIND_CALL_VIDEO else KIND_CALL_VOICE
                    addEvidence(caseId, kind, (call.durationMs / 1000).toString(), call.id, createdAt)
                }
        }
        if (intensity == MpCheckupIntensity.DEEP) {
            db.memoDao().observeForCharacter(characterId).first()
                .forEach { memo -> addEvidence(caseId, KIND_MEMO, memo.content, memo.id, createdAt) }
        }
    }

    private suspend fun addEvidence(caseId: String, kind: String, excerpt: String, refId: String, createdAt: Long) {
        val text = excerpt.trim()
        if (text.isEmpty()) return
        db.checkupEvidenceDao().insert(
            MpCheckupEvidenceEntity(
                id = newId(),
                caseId = caseId,
                kind = kind,
                excerpt = text,
                refId = refId,
                createdAt = createdAt,
            ),
        )
    }

    companion object {
        const val KIND_MESSAGE: String = "message"
        const val KIND_MOMENT: String = "moment"
        const val KIND_MEMO: String = "memo"
        const val KIND_CALL_VOICE: String = "call_voice"
        const val KIND_CALL_VIDEO: String = "call_video"

        const val STATUS_RUNNING: String = "running"
        const val STATUS_DONE: String = "done"
        const val STATUS_FAILED: String = "failed"

        private const val MAX_SUMMARY_EVIDENCE = 12
    }
}
