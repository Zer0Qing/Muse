package io.zer0.muse.miniphone.data.room

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import io.zer0.muse.miniphone.data.settings.miniPhoneSettingsDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

/**
 * 谁在哪个房间的哪个位置。
 *
 * [occupants] 的键是参与者标识：本机用户固定用 [MpRoomState.LOCAL_USER]，角色用其 id。
 * 值只存锚点 id —— 家具位置由 [MpRooms] 决定，存坐标的话改一次布局就得迁一次数据。
 */
data class MpRoomState(
    val roomId: String,
    val occupants: Map<String, String>,
) {
    companion object {
        /** 本机用户在房间里的固定标识。 */
        const val LOCAL_USER: String = "local"
    }
}

interface MpRoomStore {
    val stateFlow: Flow<MpRoomState>

    suspend fun state(): MpRoomState

    /** 挪到某个锚点；锚点不属于当前房间时忽略（不装作挪成功了）。 */
    suspend fun moveTo(anchorId: String, participant: String = MpRoomState.LOCAL_USER)

    suspend fun switchRoom(roomId: String)

    /** 角色进出房间：离开时把它从occupants里摘掉，不留幽灵位置。 */
    suspend fun leave(participant: String)
}

/**
 * 复用模块内那一份 `miniphone_settings` DataStore。
 *
 * DataStore 不允许在同一文件上建第二个实例，另开一个 `preferencesDataStore` 会崩，
 * 所以这里只加一个键。整张状态编码成一个字符串存，是为了不引入序列化依赖：
 * 房间状态就 5 个房间 × 若干锚点，读失败或格式不认识时回落默认值比报错好。
 */
class DataStoreMpRoomStore(
    private val dataStore: DataStore<Preferences>,
) : MpRoomStore {

    private val preferences: Flow<Preferences> = dataStore.data.catch { throwable ->
        if (throwable is IOException) emit(emptyPreferences()) else throw throwable
    }

    override val stateFlow: Flow<MpRoomState> = preferences.map { prefs ->
        decode(prefs[KEY_ROOM_STATE])
    }

    override suspend fun state(): MpRoomState = stateFlow.first()

    override suspend fun moveTo(anchorId: String, participant: String) {
        val current = state()
        val room = MpRooms.find(current.roomId)
        if (room.anchors.none { it.id == anchorId }) return
        write(current.copy(occupants = current.occupants + (participant to anchorId)))
    }

    override suspend fun switchRoom(roomId: String) {
        val target = MpRooms.find(roomId)
        val current = state()
        // 换房间时把所有人的位置按锚点名尽力带过去；带不过去的就离开，
        // 避免出现「人在客厅却记着卧室的床」这种角色自己都会说错的状态。
        val carried = current.occupants.mapNotNull { (participant, anchorId) ->
            val matched = target.anchors.firstOrNull { it.id == anchorId }
                ?: target.anchors.firstOrNull { it.labelRes == roomLabelOf(current.roomId, anchorId) }
            matched?.let { participant to it.id }
        }.toMap()
        write(MpRoomState(target.id, carried))
    }

    override suspend fun leave(participant: String) {
        val current = state()
        write(current.copy(occupants = current.occupants - participant))
    }

    private suspend fun write(state: MpRoomState) {
        val encoded = buildString {
            append(state.roomId)
            append(SEPARATOR)
            append(
                state.occupants.entries.joinToString(PAIR_JOINER) { (participant, anchor) ->
                    "$participant$KEY_JOINER$anchor"
                },
            )
        }
        dataStore.edit { prefs -> prefs[KEY_ROOM_STATE] = encoded }
    }

    private fun decode(raw: String?): MpRoomState {
        if (raw.isNullOrBlank()) return MpRoomState(MpRooms.DEFAULT_ROOM_ID, emptyMap())
        val parts = raw.split(SEPARATOR, limit = 2)
        val roomId = parts.firstOrNull().orEmpty()
        val occupants = parts.getOrNull(1).orEmpty()
            .split(PAIR_JOINER)
            .filter { it.isNotBlank() }
            .mapNotNull { entry ->
                val kv = entry.split(KEY_JOINER, limit = 2)
                val participant = kv.firstOrNull().orEmpty()
                val anchor = kv.getOrNull(1).orEmpty()
                if (participant.isEmpty() || anchor.isEmpty()) null else participant to anchor
            }
            .toMap()
        return MpRoomState(MpRooms.find(roomId).id, occupants)
    }

    private fun roomLabelOf(roomId: String, anchorId: String): Int =
        MpRooms.find(roomId).anchors.firstOrNull { it.id == anchorId }?.labelRes ?: 0

    private companion object {
        val KEY_ROOM_STATE = stringPreferencesKey("room_state")

        const val SEPARATOR = "|"
        const val PAIR_JOINER = ","
        const val KEY_JOINER = ":"
    }
}

/** 进程内同一份 DataStore 委托，取用方式与 [io.zer0.muse.miniphone.data.settings.miniPhoneMomentAutoStore] 一致。 */
fun miniPhoneRoomStore(context: Context): MpRoomStore = DataStoreMpRoomStore(context.applicationContext.miniPhoneSettingsDataStore)
