package io.zer0.muse.miniphone.data.roam

import io.zer0.muse.miniphone.api.MiniPhoneHost
import io.zer0.muse.miniphone.data.db.MiniPhoneDb
import io.zer0.muse.miniphone.data.db.entity.MpRoamNpcEntity
import io.zer0.muse.miniphone.data.db.entity.MpRoamStateEntity
import kotlinx.coroutines.flow.Flow

/**
 * 漫游门面：让某个角色去一个虚拟地点、看谁在哪里、记下在这一带遇到的路人。
 *
 * 这里的位置全是虚构的，不接任何真实地图或系统定位，也不申请定位权限 ——
 * 地点名要么由用户输入、要么从既有列表里选，落库时以 [MpRoamStateEntity.characterId] 为主键，
 * 所以「同一角色换地点」是整体覆盖而不是新增一条，一个角色同一时刻只会在一个地方。
 *
 * 路人是「在某个位置遇到的 AI 角色」：[MpRoamNpcEntity.persona] 存性格设定，用来接着生成对话；
 * [MpRoamNpcEntity.characterId] 非空表示这一位其实是可深聊的既有角色，空串表示只属于这个位置。
 *
 * 生成路人走宿主的通用一次性生成桥 [MiniPhoneHost.composeScene]，只在真正「遇见」时才取宿主：
 * 门面本身不依赖宿主，读位置、记录路人、清路人都只用数据库。拿不到生成结果就不写、不编，
 * 让「没遇到」这件事如实留空，而不是造一句看起来像招呼的话。
 */
internal class MpRoamStore(
    private val db: MiniPhoneDb,
    private val now: () -> Long = { System.currentTimeMillis() },
    private val newId: () -> String = { java.util.UUID.randomUUID().toString() },
) {
    /** 全部角色的当前位置，按最近更新排。一人一行，同一角色换地点只改这一行。 */
    fun observeLocations(): Flow<List<MpRoamStateEntity>> = db.roamStateDao().observeAll()

    /** 某个角色此刻在哪儿；从没出过门就是 null。 */
    suspend fun currentLocation(characterId: String): MpRoamStateEntity? = db.roamStateDao().get(characterId)

    /**
     * 让 [characterId] 去 [locationName]，覆盖他上一次的位置。
     *
     * [locationId] 留空时用地点名兜底，这样同一个名字始终指向同一处、路人才能累计在同一带里。
     * 地点名去掉首尾空白后为空就不落库，返回 false —— 免得凭空写出一条「在『 』」的荒诞记录。
     */
    suspend fun travel(characterId: String, locationName: String, locationId: String = "", note: String = ""): Boolean {
        val name = locationName.trim()
        if (name.isEmpty()) return false
        db.roamStateDao().insert(
            MpRoamStateEntity(
                characterId = characterId,
                locationId = locationId.trim().ifEmpty { name },
                locationName = name,
                note = note.trim(),
                updatedAt = now(),
            ),
        )
        return true
    }

    /** 某个位置这一带遇到过谁，按遇见时间倒序。 */
    fun observeNpcs(locationId: String): Flow<List<MpRoamNpcEntity>> = db.roamNpcDao().observeByLocation(locationId)

    /**
     * 直接在 [locationId] 记一位路人；名字去掉首尾空白后为空就不写，返回 null。
     *
     * [characterId] 非空表示这一位是可深聊的既有角色，默认空串表示只属于这个位置。
     */
    suspend fun recordNpc(locationId: String, name: String, persona: String = "", characterId: String = ""): String? {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return null
        val id = newId()
        db.roamNpcDao().insert(
            MpRoamNpcEntity(
                id = id,
                locationId = locationId,
                name = trimmed,
                persona = persona.trim(),
                characterId = characterId,
                metAt = now(),
            ),
        )
        return id
    }

    /**
     * 在 [locationName] 一带「遇见」一位路人：让 [characterId] 生成一位陌生人的性格与招呼，再落库。
     *
     * 生成的文本原样存进 persona，第一行当名字用 —— 名字与性格都来自宿主，门面不自己编。
     * 宿主没给结果（未实现、失败、或只给了空白）就返回 null 并什么都不写。
     */
    suspend fun encounter(host: MiniPhoneHost, characterId: String, locationId: String, locationName: String): String? {
        val generated = host.composeScene(characterId, buildEncounterBrief(locationName))
        if (generated.isNullOrBlank()) return null
        val text = generated.trim()
        return recordNpc(locationId, text.lineSequence().first().trim(), text, characterId = "")
    }

    /** 清掉某个位置记下的路人，换一片地方时不必手动逐个删。 */
    suspend fun clearNpcs(locationId: String) {
        db.roamNpcDao().deleteByLocation(locationId)
    }

    private fun buildEncounterBrief(locationName: String): String = buildString {
        appendLine("你正在「$locationName」一带闲逛，迎面遇到一位素不相识的路人。")
        appendLine("第一行只写这位路人的名字，随后用一两句话写清他的性格，以及他开口对你说的第一句话。")
        append("不要替用户做决定，不要解释你在做什么，只输出这位路人本身。")
    }
}
