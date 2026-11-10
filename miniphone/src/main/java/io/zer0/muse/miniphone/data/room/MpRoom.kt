package io.zer0.muse.miniphone.data.room

import io.zer0.muse.miniphone.R

/**
 * 38: 小手机的 2D 房间。
 *
 * 房间与家具位置都是**预置**的，不是用户画的：角色要能可靠地说「我在沙发那儿」，
 * 靠的是有限的、有名字的锚点，而不是任意像素坐标。家具坐标用相对比例，
 * 这样小屏与平板上摆放关系不变。
 */
data class MpRoomAnchor(
    val id: String,
    val labelRes: Int,
    val xFraction: Float,
    val yFraction: Float,
)

data class MpRoom(
    val id: String,
    val labelRes: Int,
    val anchors: List<MpRoomAnchor>,
)

object MpRooms {

    const val DEFAULT_ROOM_ID: String = "living_room"

    /** 按 id 找房间；存到不认识的值（改名、降级）回落到默认房间，不崩。 */
    fun find(roomId: String?): MpRoom = all.firstOrNull { it.id == roomId } ?: all.first()

    private fun anchor(id: String, labelRes: Int, x: Float, y: Float) = MpRoomAnchor(id, labelRes, x, y)

    val all: List<MpRoom> = listOf(
        MpRoom(
            id = "living_room",
            labelRes = R.string.mp_room_living_room,
            anchors = listOf(
                anchor("sofa", R.string.mp_anchor_seat, 0.26f, 0.68f),
                anchor("coffee_table", R.string.mp_anchor_table, 0.52f, 0.74f),
                anchor("window", R.string.mp_anchor_window, 0.80f, 0.30f),
                anchor("plant", R.string.mp_anchor_plant, 0.90f, 0.70f),
            ),
        ),
        MpRoom(
            id = "bedroom",
            labelRes = R.string.mp_room_bedroom,
            anchors = listOf(
                anchor("bed", R.string.mp_anchor_bed, 0.32f, 0.62f),
                anchor("desk", R.string.mp_anchor_desk, 0.70f, 0.58f),
                anchor("window", R.string.mp_anchor_window, 0.84f, 0.28f),
                anchor("plant", R.string.mp_anchor_plant, 0.14f, 0.34f),
            ),
        ),
        MpRoom(
            id = "study",
            labelRes = R.string.mp_room_study,
            anchors = listOf(
                anchor("desk", R.string.mp_anchor_desk, 0.38f, 0.66f),
                anchor("shelf", R.string.mp_anchor_shelf, 0.78f, 0.36f),
                anchor("seat", R.string.mp_anchor_seat, 0.22f, 0.42f),
                anchor("window", R.string.mp_anchor_window, 0.55f, 0.24f),
            ),
        ),
        MpRoom(
            id = "kitchen",
            labelRes = R.string.mp_room_kitchen,
            anchors = listOf(
                anchor("table", R.string.mp_anchor_table, 0.44f, 0.70f),
                anchor("door", R.string.mp_anchor_door, 0.86f, 0.52f),
                anchor("window", R.string.mp_anchor_window, 0.26f, 0.28f),
                anchor("plant", R.string.mp_anchor_plant, 0.62f, 0.30f),
            ),
        ),
        MpRoom(
            id = "balcony",
            labelRes = R.string.mp_room_balcony,
            anchors = listOf(
                anchor("plant", R.string.mp_anchor_plant, 0.24f, 0.60f),
                anchor("seat", R.string.mp_anchor_seat, 0.52f, 0.72f),
                anchor("table", R.string.mp_anchor_table, 0.72f, 0.66f),
                anchor("door", R.string.mp_anchor_door, 0.16f, 0.32f),
            ),
        ),
    )
}
