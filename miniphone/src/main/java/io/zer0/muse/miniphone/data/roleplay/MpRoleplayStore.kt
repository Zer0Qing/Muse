package io.zer0.muse.miniphone.data.roleplay

import androidx.room.withTransaction
import io.zer0.muse.miniphone.api.MiniPhoneHost
import io.zer0.muse.miniphone.data.db.MiniPhoneDb
import io.zer0.muse.miniphone.data.db.entity.MpRpEndingEntity
import io.zer0.muse.miniphone.data.db.entity.MpRpSaveEntity
import io.zer0.muse.miniphone.data.db.entity.MpRpScriptEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.random.Random

private const val DICE_SIDES = 20
private const val MAX_BRIEF_TURNS = 8
private const val MAX_ENDING_TITLE_CHARS = 40
private const val KEY_CHARACTER_ID = "characterId"
private const val KEY_OPENING = "opening"
private const val KEY_TURNS = "turns"
private const val KEY_TURN = "turn"
private const val KEY_ACTION = "action"
private const val KEY_ROLL = "roll"
private const val KEY_TEXT = "text"

/** 一局跑团里已经发生过的一次推进；[roll] 是那回合的骰果。 */
internal data class MpRoleplayTurn(
    val turn: Int,
    val action: String,
    val roll: Int,
    val text: String,
)

/** 一局跑团的局面快照，落库时编码成 [MpRpSaveEntity.stateJson]。 */
internal data class MpRoleplayState(
    val characterId: String,
    val opening: String,
    val turns: List<MpRoleplayTurn> = emptyList(),
) {
    val latestText: String
        get() = turns.lastOrNull()?.text?.takeIf { it.isNotBlank() } ?: opening

    val latestRoll: Int?
        get() = turns.lastOrNull()?.roll

    fun toJson(): String {
        val turnArray = JSONArray()
        turns.forEach { item ->
            turnArray.put(
                JSONObject()
                    .put(KEY_TURN, item.turn)
                    .put(KEY_ACTION, item.action)
                    .put(KEY_ROLL, item.roll)
                    .put(KEY_TEXT, item.text),
            )
        }
        return JSONObject()
            .put(KEY_CHARACTER_ID, characterId)
            .put(KEY_OPENING, opening)
            .put(KEY_TURNS, turnArray)
            .toString()
    }

    companion object {
        /** 读局面；脏文本返回 null，让调用方明确停在原处而不是假装读到了一份空局面。 */
        fun fromJson(raw: String): MpRoleplayState? = runCatching {
            val state = JSONObject(raw)
            val turnArray = state.optJSONArray(KEY_TURNS) ?: JSONArray()
            val turnList = mutableListOf<MpRoleplayTurn>()
            for (index in 0 until turnArray.length()) {
                val item = turnArray.optJSONObject(index) ?: continue
                turnList += MpRoleplayTurn(
                    turn = item.optInt(KEY_TURN),
                    action = item.optString(KEY_ACTION),
                    roll = item.optInt(KEY_ROLL),
                    text = item.optString(KEY_TEXT),
                )
            }
            MpRoleplayState(
                characterId = state.optString(KEY_CHARACTER_ID),
                opening = state.optString(KEY_OPENING),
                turns = turnList,
            )
        }.getOrNull()
    }
}

private data class MpRoleplayContext(
    val save: MpRpSaveEntity,
    val script: MpRpScriptEntity,
    val state: MpRoleplayState,
    val characterId: String,
)

/**
 * 跑团门面：剧本库、一局进度与结局册。
 *
 * 剧本描述可反复使用的开场和规则；存档记录某一个人在这份规则下走到哪里。
 * 局面快照存在 [MpRpSaveEntity.stateJson]，界面只拿它渲染，不让业务层拼展示文案。
 *
 * 推进与结案共用宿主的单次生成桥。桥没有结果时，回合与结局都不动 ——
 * 一次失败的生成不该被伪装成“故事照常往前走了一步”。
 */
internal class MpRoleplayStore(
    private val db: MiniPhoneDb,
    private val host: MiniPhoneHost,
    private val now: () -> Long = { System.currentTimeMillis() },
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val rollSource: () -> Int = { Random.nextInt(1, DICE_SIDES + 1) },
) {
    fun observeScripts(): Flow<List<MpRpScriptEntity>> = db.rpScriptDao().observeAll()

    fun observeSaves(scriptId: String): Flow<List<MpRpSaveEntity>> = db.rpSaveDao().observeByScript(scriptId)

    fun observeEndings(scriptId: String): Flow<List<MpRpEndingEntity>> = db.rpEndingDao().observeByScript(scriptId)

    /** 新建剧本；标题或正文空白时不落库并返回 null。 */
    suspend fun createScript(title: String, body: String): String? {
        val cleanTitle = title.trim()
        val cleanBody = body.trim()
        if (cleanTitle.isEmpty() || cleanBody.isEmpty()) return null
        val id = newId()
        val at = now()
        db.rpScriptDao().insert(
            MpRpScriptEntity(
                id = id,
                title = cleanTitle,
                body = cleanBody,
                createdAt = at,
                updatedAt = at,
            ),
        )
        return id
    }

    /** 修改既有剧本；空白内容不会把旧正文抹掉。 */
    suspend fun updateScript(scriptId: String, title: String, body: String): Boolean {
        val existing = db.rpScriptDao().get(scriptId) ?: return false
        val cleanTitle = title.trim()
        val cleanBody = body.trim()
        if (cleanTitle.isEmpty() || cleanBody.isEmpty()) return false
        db.rpScriptDao().insert(existing.copy(title = cleanTitle, body = cleanBody, updatedAt = now()))
        return true
    }

    /** 删除剧本时连带它的每一次进度与所有结局，不留下无主存档。 */
    suspend fun deleteScript(scriptId: String) {
        val endings = db.rpEndingDao().observeByScript(scriptId).first()
        db.withTransaction {
            endings.forEach { ending -> db.rpEndingDao().deleteBySave(ending.saveId) }
            db.rpSaveDao().deleteByScript(scriptId)
            db.rpScriptDao().delete(scriptId)
        }
    }

    /** 在某剧本下开一局；标题留空时沿用剧本标题，局面从剧本正文起步。 */
    suspend fun startRun(scriptId: String, characterId: String, title: String = ""): String? {
        val script = db.rpScriptDao().get(scriptId) ?: return null
        val cleanCharacterId = characterId.trim()
        if (cleanCharacterId.isEmpty()) return null
        val id = newId()
        val state = MpRoleplayState(characterId = cleanCharacterId, opening = script.body)
        db.rpSaveDao().insert(
            MpRpSaveEntity(
                id = id,
                scriptId = scriptId,
                title = title.trim().ifEmpty { script.title },
                stateJson = state.toJson(),
                turn = 0,
                updatedAt = now(),
            ),
        )
        return id
    }

    /** 推进一回合：掷骰 → 让角色续写 → 成功后才同时更新回合与快照。 */
    suspend fun advance(saveId: String, action: String = ""): Boolean {
        val context = roleplayContext(saveId) ?: return false
        val roll = rollSource().coerceIn(1, DICE_SIDES)
        val cleanAction = action.trim()
        val narrative = host.composeScene(context.characterId, advanceBrief(context, cleanAction, roll))
        if (narrative.isNullOrBlank()) return false
        val turn = context.save.turn + 1
        val state = context.state.copy(
            turns = context.state.turns + MpRoleplayTurn(turn, cleanAction, roll, narrative.trim()),
        )
        db.rpSaveDao().insert(context.save.copy(stateJson = state.toJson(), turn = turn, updatedAt = now()))
        return true
    }

    /** 结案；生成成功返回结局 id，失败返回 null。同一存档重复结案只保留最新一条。 */
    suspend fun conclude(saveId: String): String? {
        val context = roleplayContext(saveId) ?: return null
        val generated = host.composeScene(context.characterId, endingBrief(context))
        if (generated.isNullOrBlank()) return null
        val text = generated.trim()
        val existing = db.rpEndingDao().forSave(saveId)
        val endingId = existing?.id ?: newId()
        val ending = MpRpEndingEntity(
            id = endingId,
            scriptId = context.script.id,
            saveId = saveId,
            title = endingTitle(context, text),
            text = text,
            createdAt = now(),
        )
        db.withTransaction {
            db.rpEndingDao().deleteBySave(saveId)
            db.rpEndingDao().insert(ending)
        }
        return endingId
    }

    /** 删除一次存档及其唯一结局，剧本本身保留。 */
    suspend fun deleteSave(saveId: String) {
        db.withTransaction {
            db.rpEndingDao().deleteBySave(saveId)
            db.rpSaveDao().delete(saveId)
        }
    }

    private suspend fun roleplayContext(saveId: String): MpRoleplayContext? {
        val save = db.rpSaveDao().get(saveId) ?: return null
        val script = db.rpScriptDao().get(save.scriptId) ?: return null
        val state = MpRoleplayState.fromJson(save.stateJson) ?: return null
        val characterId = state.characterId
        return if (characterId.isBlank()) null else MpRoleplayContext(save, script, state, characterId)
    }

    private fun endingTitle(context: MpRoleplayContext, text: String): String = context.save.title
        .ifBlank { context.script.title }
        .ifBlank { text.lineSequence().firstOrNull()?.trim()?.take(MAX_ENDING_TITLE_CHARS).orEmpty() }

    private fun advanceBrief(context: MpRoleplayContext, action: String, roll: Int): String = buildString {
        appendLine("你正在主持一场跑团。请依据剧本、当前局面和玩家行动，续写下一回合的叙事。")
        appendLine("剧本《${context.script.title}》：")
        appendLine(context.script.body)
        appendLine("当前局面：")
        appendLine(context.state.latestText)
        context.state.turns.takeLast(MAX_BRIEF_TURNS).forEach { item ->
            appendLine("第 ${item.turn} 回合｜行动：${item.action.ifBlank { "未描述" }}｜骰果：${item.roll}｜结果：${item.text}")
        }
        appendLine("玩家行动：${action.ifBlank { "未描述，按当前局面自然推进" }}")
        appendLine("本回合骰果：$roll/$DICE_SIDES。")
        append("只输出叙事正文，不要解释规则，不要替玩家决定下一步。")
    }

    private fun endingBrief(context: MpRoleplayContext): String = buildString {
        appendLine("请为下面这场跑团写一个结局。")
        appendLine("剧本《${context.script.title}》：")
        appendLine(context.script.body)
        appendLine("最终局面：")
        appendLine(context.state.latestText)
        context.state.turns.takeLast(MAX_BRIEF_TURNS).forEach { item ->
            appendLine("第 ${item.turn} 回合｜行动：${item.action.ifBlank { "未描述" }}｜骰果：${item.roll}｜结果：${item.text}")
        }
        append("只写结局正文，要有收束感，不要追加选项或说明。")
    }
}
