package io.zer0.muse.data.proactive

/**
 * 牵挂的性质。
 *
 * 主动消息的原料不是「时间到了」，是「有件事想跟他说」。
 * 这个枚举回答的就是「那件事是什么性质」。
 *
 * 五种性质在生成 prompt 里的语气完全不同：
 * 提醒要简短直接，关心要留白，分享要有信息量。
 */
enum class ThoughtKind {
    /** 用户说过要做的具体事。有明确时间点或截止日。 */
    REMINDER,

    /** 对话里没闭环的话题。用户提过一嘴，后来没下文。 */
    FOLLOW_UP,

    /** 关于这个人状态的事。睡不好、生病、压力大。 */
    CARE,

    /** 我这边的事，想分享。要有信息量，不能是空洞的问候。 */
    SHARE,

    /** 关系或使用上的节点。克制使用，多了就是成就播报。 */
    MILESTONE,
}

/**
 * 牵挂的时效类型。
 *
 * 这是整套调度系统里最关键的一个区分：
 * 「过了这个点说就没意义了」和「什么时候说都行」是完全不同的东西，
 * 而旧实现把两者当成同一种，于是产生了「补发过期提醒」这种负分行为。
 */
enum class ThoughtUrgency {
    /**
     * 过了窗口就没意义。到点即过期，**绝不补发**。
     *
     * 典型：「今晚 8 点提醒他看直播」。第二天补一句「昨晚的直播你看了吗」
     * 比不发还糟 —— 它暴露了系统在假装自己有记性。
     */
    IMMEDIATE,

    /** 一段时间内说都合适。窗口内自由选择，出窗过期。 */
    WINDOW,

    /** 什么时候说都行。不会过期，但会随 surfaceCount 衰减。 */
    EVERGREEN,
}

/** 牵挂的生命周期状态。 */
enum class ThoughtStatus {
    /** 还没说出去。只有这个状态会被调度到。 */
    OPEN,

    /** 已经说过了，事情完成。 */
    DONE,

    /** 时效窗口过了，没来得及说。 */
    EXPIRED,

    /** 主动放弃。比如同一条被提过太多次还没反应。 */
    DROPPED,
}

/** 牵挂的来源，用于回溯「这是怎么惦记上的」。 */
enum class ThoughtSource {
    /** 对话里助手自己记下的。主力来源。 */
    CONVERSATION,

    /** 从记忆系统按规则推导的。 */
    MEMORY,

    /** 从里程碑推导的。 */
    MILESTONE,

    /** 用户主动约定的。优先级最高。 */
    USER_DEFINED,
}

/**
 * 主动等级 —— 用户只需要表达「想让它多主动」。
 *
 * 这个枚举控制两件事：
 *  1. 预算区间（[floor] / [cap]）
 *  2. 允许说出口的牵挂类型（[allowedKinds]）
 *
 * 第二条是关键区别：「安静」不是一个更小的频次，而是一种不同的语义 ——
 * 只说该说的事，不说想说的话。所以它直接砍掉 SHARE/MILESTONE 这类
 * 「想说话」的类型，而不是把它们的频次降到很低。
 *
 * 这里曾经还有一个 `threshold`（fitness 阈值），用于把评分压在阈值下的内容筛掉。
 * 评分体系在主动消息重建时被整体移除（改为把最近对话交给模型、由它决定说不说），
 * 阈值随之失去消费方，留着只会让后来人以为还有个评分在起作用。
 *
 * 与长期学习的 [ProactiveRelationEntity.intimacy] 分工：
 * 本枚举直接给出每日条数上限（安静 3 / 克制 8 / 标准 15 / 主动 30 / 黏人不限），
 * 就是用户选的那一档。早期版本曾用「等级地板 + 亲密度学习值」拼额度，导致
 * 用户选了黏人却每天只能发 5 条，已废弃。
 */
enum class ProactiveIntensity(
    /** 每天最多能发多少条（黏人档为 Int.MAX_VALUE，即不限）。 */
    val cap: Int,
    /** 该等级允许说出口的牵挂类型。 */
    val allowedKinds: Set<ThoughtKind>,
) {
    /** 只要提醒，不要寒暄。每天最多 3 条。 */
    QUIET(
        cap = 3,
        allowedKinds = setOf(ThoughtKind.REMINDER),
    ),

    /** 偶尔被想到就好。每天最多 8 条。 */
    RESERVED(
        cap = 8,
        allowedKinds = setOf(ThoughtKind.REMINDER, ThoughtKind.FOLLOW_UP, ThoughtKind.CARE),
    ),

    /** 大多数人的默认。每天最多 15 条。 */
    STANDARD(
        cap = 15,
        allowedKinds = ThoughtKind.entries.toSet(),
    ),

    /** 喜欢互动。每天最多 30 条。 */
    ACTIVE(
        cap = 30,
        allowedKinds = ThoughtKind.entries.toSet(),
    ),

    /** 要陪伴感：不限次数。 */
    CLINGY(
        cap = Int.MAX_VALUE,
        allowedKinds = ThoughtKind.entries.toSet(),
    ),

    ;

    /** 是否不限次数（目前只有黏人档）。 */
    val unlimited: Boolean get() = cap == Int.MAX_VALUE

    /** 该类型是否允许在此等级下说出口。 */
    fun allows(kind: ThoughtKind): Boolean = kind in allowedKinds

    /** 这个等级是不是「只说该说的事」—— 没有具体事由就不该开口。 */
    val onlyWhenNeeded: Boolean get() = this == QUIET

    companion object {
        /** 默认档位。 */
        val DEFAULT = STANDARD

        /**
         * 从持久化字符串还原，非法值回落到默认档。
         *
         * 用 name 存而不是序号：序号会随枚举增删而错位，
         * 用户升级后可能从「标准」变成「黏人」。
         */
        fun fromName(name: String?): ProactiveIntensity =
            entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}
