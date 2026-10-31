package io.zer0.muse.miniphone.data.db

import androidx.room.TypeConverter

/**
 * Room 枚举 ↔ 文本列的转换。
 *
 * 库里存小写字符串(而不是序号),便于直接看 SQL、也便于跨版本增删枚举项;
 * 反序列化遇到未知值一律退回该枚举的首项,保证旧库里读到脏值时不崩。
 */
class MpConverters {
    @TypeConverter
    fun actorToString(value: MpActorType): String = value.value

    @TypeConverter
    fun stringToActor(value: String): MpActorType = MpActorType.fromValue(value)

    @TypeConverter
    fun messageTypeToString(value: MpMessageType): String = value.value

    @TypeConverter
    fun stringToMessageType(value: String): MpMessageType = MpMessageType.fromValue(value)

    @TypeConverter
    fun messageStatusToInt(value: MpMessageStatus): Int = value.value

    @TypeConverter
    fun intToMessageStatus(value: Int): MpMessageStatus = MpMessageStatus.fromValue(value)

    @TypeConverter
    fun momentSourceToString(value: MpMomentSource): String = value.value

    @TypeConverter
    fun stringToMomentSource(value: String): MpMomentSource = MpMomentSource.fromValue(value)

    @TypeConverter
    fun albumSourceToString(value: MpAlbumSource): String = value.value

    @TypeConverter
    fun stringToAlbumSource(value: String): MpAlbumSource = MpAlbumSource.fromValue(value)
}
