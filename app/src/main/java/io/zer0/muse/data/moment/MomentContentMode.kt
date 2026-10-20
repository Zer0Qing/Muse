package io.zer0.muse.data.moment

/**
 * 朋友圈内容模式（用户可在设置页切换）。
 *
 * 背景：旧实现的 prompt 写死在"只依据真实素材，不编造用户没说过的经历"。
 * 这条规则在角色扮演语境下把角色锁死在"只能复述用户"——角色没有自己的生活，
 * 朋友圈就退化成"用户记忆摘要"，失去沉浸感。但反过来完全放开又会变成凭空编造，
 * 同样破坏信任。故做成两档，由用户按自己的玩法选择。
 *
 * - [REAL] 真实系：只发和用户有关的事，克制，像真的在记录生活
 * - [CHARACTER] 角色系：角色有自己的生活、工作、遭遇，用户是旁观者；
 *   涉及用户的部分仍不得编造
 *
 * 默认 [REAL]：更保守，不会冒出用户没经历过的内容，新用户不会被吓到。
 */
enum class MomentContentMode {
    REAL,
    CHARACTER,
    ;

    companion object {
        /** 从持久化字符串还原；未知值退化为 [REAL]。 */
        fun fromStorage(value: String?): MomentContentMode = when (value?.trim()?.lowercase()) {
            "character" -> CHARACTER
            else -> REAL
        }
    }

    /** 持久化字符串。 */
    fun toStorage(): String = when (this) {
        REAL -> "real"
        CHARACTER -> "character"
    }
}
