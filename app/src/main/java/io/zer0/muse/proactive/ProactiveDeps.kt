package io.zer0.muse.proactive

import io.zer0.memory.fact.FactStore
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.assistant.AssistantRepository
import io.zer0.muse.data.proactive.ProactiveOutcomeRepository
import io.zer0.muse.data.proactive.ProactiveRelationRepository
import io.zer0.muse.data.proactive.ProactiveThoughtRepository
import io.zer0.muse.data.session.SessionRepository
import io.zer0.muse.notification.MuseNotificationManager
import io.zer0.muse.schedule.ChatGenerationManager

/**
 * 主动消息各层的依赖聚合。
 *
 * 拆成三个小包而不是一个大包，是因为这三组东西的变化原因完全不同：
 *  - [ProactiveStores]  存储层，换表结构时才动
 *  - [ProactiveSources] 燃料来源，加新的牵挂推导来源时才动
 *  - [ProactiveRuntime] 宿主环境，换会话模型或通知方式时才动
 *
 * 顺带让每个构造函数的参数数量都落回可读范围 —— 十来个参数的构造函数
 * 每次调用都得数位置，错了编译器还不一定报。
 */
class ProactiveStores(
    val thoughts: ProactiveThoughtRepository,
    val relations: ProactiveRelationRepository,
    val outcomes: ProactiveOutcomeRepository,
)

class ProactiveSources(
    val factStore: FactStore,
    val milestoneDao: io.zer0.muse.data.milestone.MilestoneDao,
)

class ProactiveRuntime(
    val settings: SettingsRepository,
    val sessions: SessionRepository,
    val assistants: AssistantRepository,
    val notifier: MuseNotificationManager,
    val generations: ChatGenerationManager,
)
