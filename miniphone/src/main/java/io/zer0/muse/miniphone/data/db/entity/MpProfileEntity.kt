package io.zer0.muse.miniphone.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 本机用户在小手机里的资料。全库只存一行,主键固定为 [SINGLE_ROW_ID]。
 */
@Entity(tableName = "mp_profile")
data class MpProfileEntity(
    @PrimaryKey val id: Int = SINGLE_ROW_ID,
    val nickname: String,
    val avatarUri: String? = null,
    val signature: String? = null,
    val wallpaperUri: String? = null,
    val coverImageUri: String? = null,
) {
    companion object {
        const val SINGLE_ROW_ID: Int = 1
    }
}
