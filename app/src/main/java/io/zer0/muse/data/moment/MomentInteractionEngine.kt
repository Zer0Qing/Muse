package io.zer0.muse.data.moment

import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.data.assistant.AssistantRepository
import io.zer0.muse.data.session.SessionRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * v1.0.75: 朋友圈互动引擎。
 *
 * 职责:在用户发布动态或评论后,异步触发其他助手的点赞/评论互动。
 *
 * 触发规则:
 *  - 用户发动态 → 随机 1-3 个助手点赞(概率 60%) + 随机 1 个助手评论(必回,allowSkip=false)
 *  - 用户评论助手动态 → 作者助手回复该评论(必回,allowSkip=false)
 *  - 助手发动态 → 其他助手概率触发点赞+评论(同用户发动态逻辑,额外防重复标记)
 *
 * 防死循环:
 *  - 助手回复只处理"用户主动评论"场景,助手之间的回复不再触发新回复
 *  - 每条评论最多被回复一次(通过 [interactedMomentIds] 集合去重)
 *  - 单轮互动总条数 ≤ MAX_TOTAL_INTERACTIONS(默认 5)
 *  - LLM 调用失败 catch 住,不阻塞调度器
 */
class MomentInteractionEngine(
    private val repository: MomentRepository,
    private val generator: MomentGenerator,
    private val assistantRepository: AssistantRepository,
    private val sessionRepository: SessionRepository,
    private val scope: CoroutineScope,
    /** v2.6.7（P1-1）: 关系状态 — 私信概率与互动挑人不再写死。 */
    private val relationshipRepository: io.zer0.muse.data.relationship.AssistantRelationshipRepository? = null,
    /** v2.6.7（P1-2）: 读主对话免打扰配置，小手机主动私信共用同一时段。 */
    private val settingsRepository: io.zer0.muse.data.SettingsRepository? = null,
) {

    /** UI 需要的互动结果(用于横幅提示)。 */
    data class InteractionNotice(
        val momentId: String,
        val likerNames: List<String>,
        val commenterNames: List<String>,
        val replyFailed: Boolean = false,
    )

    private val _notices = kotlinx.coroutines.flow.MutableSharedFlow<InteractionNotice>(extraBufferCapacity = 8)

    /**
     * v2.6.7（P1-3）: 正在输入状态广播。
     *
     * 私信发送前有 5-60 秒随机延迟（模拟真实节奏），旧实现用户看不到任何反馈，
     * 像卡住了。这里单独开一个轻量通道，只传“哪个助手在写”，
     * 不挤进 [notices]（那是完成后的事，语义不同）。
     */
    private val _typing = kotlinx.coroutines.flow.MutableSharedFlow<TypingState>(extraBufferCapacity = 8)

    /** 正在输入状态流：assistantId → 是否在输入。 */
    val typing: kotlinx.coroutines.flow.SharedFlow<TypingState> = _typing.asSharedFlow()

    /** 正在输入状态。 */
    data class TypingState(val assistantId: String, val isTyping: Boolean)

    /** 互动结果通知(UI 收集后弹横幅)。 */
    val notices: kotlinx.coroutines.flow.SharedFlow<InteractionNotice> = _notices.asSharedFlow()

    /** 已触发互动的动态 id 集合(防重复触发)。 */
    private val interactedMomentIds = mutableSetOf<String>()

    /**
     * v2.6.7（P2-4）: 进程重启后的重复互动防护。
     *
     * 旧实现只用内存 Set，进程重启即清空，同一条动态会被重新点赞/评论一轮。
     * 这里改为“查持久化痕迹”：已有 AI 点赞或评论即视为已互动。
     * 不新增表 —— 点赞/评论本身就是互动痕迹。
     */
    private suspend fun alreadyInteracted(momentId: String): Boolean {
        if (momentId in interactedMomentIds) return true
        val likes = resultOf { repository.getLikes(momentId) }.getOrNull().orEmpty()
        if (likes.any { it.likerType == "assistant" }) return true
        val comments = resultOf { repository.getComments(momentId) }.getOrNull().orEmpty()
        return comments.any { it.sender == "assistant" }
    }

    /**
     * 用户发布动态后触发互动(异步,不阻塞 UI)。
     * [source] 用于区分"用户发布"vs"调度器生成",仅影响日志。
     */
    fun triggerOnUserPublish(moment: MomentEntity, source: String = "user_publish") {
        scope.launch {
            runInteraction(
                moment = moment,
                // 用户发布,author 为 null
                author = null,
                source = source,
            )
        }
    }

    /**
     * 用户评论后触发作者回复(异步)。
     * 仅当 [moment.senderType == "assistant"] 时才触发。
     */
    fun triggerOnUserComment(moment: MomentEntity, userComment: String) {
        // 只有助手发布的动态才触发作者回复
        if (moment.senderType != "assistant") return
        scope.launch {
            runAuthorReply(moment = moment, userComment = userComment)
        }
    }

    /**
     * 助手发布动态后触发其他助手互动(异步)。
     * [author] 必须为非 null 的助手实体。
     */
    fun triggerOnAssistantPublish(moment: MomentEntity, author: io.zer0.muse.data.assistant.AssistantEntity) {
        scope.launch {
            runInteraction(
                moment = moment,
                author = author,
                source = "assistant_publish",
            )
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 小手机私信
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * v1.138: 助手发动态后,有概率给主人发一条私信(小手机私信空间)。
     *
     * 触发规则:
     *  - 仅当互动已触发过(点赞/评论)才尝试私信
     *  - 40% 概率触发
     *  - 每条动态最多 1 条私信
     *  - 延迟 5~60 秒随机
     *  - 失败不影响调度器
     */
    fun trySendMiniPhoneMessage(moment: MomentEntity, author: io.zer0.muse.data.assistant.AssistantEntity) {
        scope.launch {
            resultOf { sendPrivateMessageToMiniPhone(moment, author) }.onError { msg, t ->
                Logger.w(TAG, "[$TAG] 小手机私信发送失败: ${t?.message ?: msg}")
            }
        }
    }

    private suspend fun sendPrivateMessageToMiniPhone(moment: MomentEntity, author: io.zer0.muse.data.assistant.AssistantEntity) {
        // v2.6.7（P1-1）: 概率不再写死 40%。
        // 旧逻辑与角色关系无关，陌生人跟女朋友一样粘人。
        val relationship = relationshipRepository?.getOrDefault(author.id)
        if (relationship != null) {
            if (!relationship.proactiveEnabled) return
            val limit = relationship.dailyProactiveLimit
            if (limit > 0 && countTodayProactive(author.id) >= limit) {
                Logger.d(TAG, "助手 ${author.name} 今日主动私信已达上限($limit)，跳过")
                return
            }
        }
        // v2.6.7（P1-2）: 复用主对话同一套免打扰时段（跨夜/全天开关语义一致），
        // 避免“主对话安静、小手机半夜发私信”。
        if (!isWithinQuietHoursAllowance()) {
            Logger.d(TAG, "当前处于免打扰时段，跳过主动私信")
            return
        }
        val probability = relationship?.let { relationshipRepository.proactiveProbability(it) } ?: 0.4f
        if (Random.nextFloat() >= probability) return

        val delayMs = Random.nextLong(5000L, 60000L)
        // v2.6.7（P1-3）: 先告诉 UI “这个助手在写”，用户才不会以为卡住了。
        _typing.tryEmit(TypingState(author.id, true))
        try {
            delay(delayMs)
        } finally {
            // 取消也要清状态，否则小球会一直转下去
            _typing.tryEmit(TypingState(author.id, false))
        }

        // 生成私信内容(与动态相关)
        val images = moment.images().take(4)
        val message = resultOf {
            generator.generateReply(
                momentContent = moment.content,
                userComment = "(看了你的动态)",
                assistant = author,
                images = images,
                allowSkip = false,
            )
        }.getOrNull()

        if (message.isNullOrBlank()) {
            Logger.w(TAG, "小手机私信内容生成失败: moment=${moment.id}, author=${author.id}")
            return
        }

        // 查找或创建该助手的 isMiniPhone 会话
        val existingSession = sessionRepository.getMiniPhoneSessions()
            .firstOrNull { it.assistantId == author.id }

        val sessionId = if (existingSession != null) {
            existingSession.id
        } else {
            sessionRepository.createMiniPhoneSession(author.id)
        }

        // 发送消息
        val now = System.currentTimeMillis()
        val uiMessage = UIMessage(
            id = kotlin.uuid.Uuid.random(),
            role = MessageRole.ASSISTANT,
            content = message,
            createdAt = now,
        )
        sessionRepository.appendMessage(sessionId, uiMessage)

        // v2.6.7（P1-1）: 记录一次真实互动，亲密度微量累加
        resultOf { relationshipRepository?.recordInteraction(author.id) }

        Logger.i(TAG, "[$TAG] 小手机私信已发送: moment=${moment.id}, author=${author.name}, sessionId=$sessionId")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 内部实现
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 读取主对话的免打扰配置，判断当前是否允许主动私信。
     *
     * 读不到配置时放行（不阻断正常行为）。
     */
    private suspend fun isWithinQuietHoursAllowance(): Boolean {
        val settings = settingsRepository ?: return true
        val config = resultOf { settings.proactiveMessageConfigFlow.first() }.getOrNull()
            ?: return true
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        return io.zer0.muse.data.proactive.QuietHoursPolicy.isWithinAllowedWindow(
            currentHour = hour,
            allowedHourStart = config.allowedHourStart,
            allowedHourEnd = config.allowedHourEnd,
            allDayAllowed = config.allDayAllowed,
        )
    }

    /** v2.6.9: 解析可见范围（逗号分隔的助手 id；空串 = 公开）。 */
    private fun parseVisibleTo(json: String): Set<String> {
        if (json.isBlank()) return emptySet()
        return json.split(',', ';')
            .map { it.trim().trim('"', '[', ']') }
            .filter { it.isNotEmpty() }
            .toSet()
    }

    /**
     * 粗略统计今天与某助手的小手机会话活动量，用作每日主动私信上限的参照。
     *
     * 口径说明：这里用“今日有更新的小手机会话数”，不是精确消息条数。
     * 精确计数需要遍历每个会话的消息表，在互动触发路径上开销不划算；
     * 而本上限的作用是防扰而非计费，粗口径已能拦住“一天刷十次”的情形。
     * 取不到会话时返回 0，不阻断正常发送。
     */
    private suspend fun countTodayProactive(assistantId: String): Int {
        val todayStart = java.time.LocalDate.now()
            .atStartOfDay(java.time.ZoneId.systemDefault())
            .toInstant().toEpochMilli()
        val sessions = resultOf { sessionRepository.getMiniPhoneSessions() }.getOrNull().orEmpty()
        return sessions.count { it.assistantId == assistantId && it.updatedAt >= todayStart }
    }

    /** 核心互动逻辑:点赞 + 评论(异步,有随机延迟)。 */
    private suspend fun runInteraction(moment: MomentEntity, author: io.zer0.muse.data.assistant.AssistantEntity?, source: String) {
        // 防重复:同一动态已互动过则跳过（v2.6.7: 兼查持久化痕迹，不只靠内存 Set）
        if (alreadyInteracted(moment.id)) {
            Logger.d(TAG, "动态 $moment.id 已互动过,跳过 [$source]")
            return
        }
        interactedMomentIds.add(moment.id)

        // P2-6: 停用的助手不参与朋友圈点赞/评论
        val allAssistants = (resultOf { assistantRepository.getAll() }.getOrNull() ?: emptyList()).filter { it.enabled }
        // v2.6.9: 可见范围 —— 非空时只有列表内的角色能互动
        val visibleTo = parseVisibleTo(moment.visibleToJson)
        val scopedAssistants = if (visibleTo.isEmpty()) {
            allAssistants
        } else {
            allAssistants.filter { it.id in visibleTo }
        }
        // 排除作者本人
        val others = scopedAssistants.filter { it.id != author?.id }
        if (others.isEmpty()) {
            Logger.d(TAG, "无可用助手参与互动: moment=${moment.id}")
            return
        }

        val images = moment.images().take(4)
        var totalInteractions = 0
        val landedLikers = mutableListOf<String>()
        val landedCommenters = mutableListOf<String>()

        // v2.6.7（P1-1）: 按亲密度加权挑人，不再纯随机。
        // 旧实现 others[Random.nextInt] 让“从没聊过的人”与“天天聊的人”一样频繁来互动。
        val relationships = resultOf { relationshipRepository?.getAll() }.getOrNull()
            .orEmpty()
            .associateBy { it.assistantId }

        // 随机挑 1-3 个助手点赞(60% 概率，概率不变，但挑人改成加权)
        val likers = if (Random.nextFloat() < 0.6f) {
            val pool = others.toMutableList()
            buildList {
                val count = Random.nextInt(1, minOf(3, pool.size) + 1)
                repeat(count) {
                    val picked = relationshipRepository?.pickWeighted(pool.map { it.id }, relationships)
                    val pickedAssistant = pool.firstOrNull { it.id == picked } ?: return@repeat
                    pool.remove(pickedAssistant)
                    add(pickedAssistant)
                }
            }
        } else {
            emptyList()
        }

        for (liker in likers) {
            if (totalInteractions >= MAX_TOTAL_INTERACTIONS) break
            val before = moment.likes
            val updated = repository.likeBy(moment, "assistant", liker.id, liker.name)
            if (updated.likes > before) {
                totalInteractions++
                landedLikers.add(liker.name)
                // 延迟 3-40 秒,模拟真人节奏
                delay(Random.nextLong(3000L, 40000L))
            }
        }

        // 随机挑 1 个助手评论(30% 概率,LLM 生成) — v2.6.7: 同样改为亲密度加权
        if (totalInteractions < MAX_TOTAL_INTERACTIONS && Random.nextFloat() < 0.3f) {
            val commenterId = relationshipRepository?.pickWeighted(others.map { it.id }, relationships)
            val commenter = others.firstOrNull { it.id == commenterId } ?: others[Random.nextInt(others.size)]
            val reply = resultOf {
                generator.generateReply(
                    momentContent = moment.content,
                    userComment = "(看了你的动态)",
                    assistant = commenter,
                    images = images,
                    allowSkip = false,
                )
            }.getOrNull()
            if (!reply.isNullOrBlank()) {
                val comment = repository.insertComment(
                    momentId = moment.id,
                    sender = "assistant",
                    content = reply,
                    senderId = commenter.id,
                    senderName = commenter.name,
                )
                if (comment != null) {
                    totalInteractions++
                    landedCommenters.add(commenter.name)
                    Logger.i(TAG, "[$source] 助手 ${commenter.name} 评论了动态 ${moment.id}: ${reply.take(30)}")
                }
            } else {
                Logger.w(TAG, "[$source] LLM 生成评论失败,跳过: moment=${moment.id}")
            }
        }

        // v1.138: 互动后尝试发私信给主人(小手机私信空间)
        if (author != null && totalInteractions > 0) {
            trySendMiniPhoneMessage(moment, author)
        }

        Logger.i(TAG, "[$source] 完成互动,共 $totalInteractions 条,动态=${moment.id}")
        // v1.0.90: 把结果回传 UI —— 少了这一步,用户除了刷新页面根本不知道有人来互动
        if (landedLikers.isNotEmpty() || landedCommenters.isNotEmpty()) {
            _notices.tryEmit(
                InteractionNotice(
                    momentId = moment.id,
                    likerNames = landedLikers.toList(),
                    commenterNames = landedCommenters.toList(),
                ),
            )
        }
    }

    /**
     * 用户评论 → 作者助手回复(必回,allowSkip=false)。
     * 失败时返回非 null,调用方可显示 Toast。
     */
    private suspend fun runAuthorReply(moment: MomentEntity, userComment: String): String? {
        val authorId = moment.senderId ?: return null
        val author = resultOf { assistantRepository.getById(authorId) }.getOrNull()
            ?: return null // 作者不存在,无法回复

        // 防死循环:只处理用户主动评论,助手回复不会重新触发互动管线。
        val images = moment.images().take(4)
        val reply = resultOf {
            generator.generateReply(
                momentContent = moment.content,
                userComment = userComment,
                assistant = author,
                images = images,
                allowSkip = false,
            )
        }.getOrNull()

        if (reply.isNullOrBlank()) {
            Logger.w(TAG, "作者回复生成失败: moment=${moment.id}, author=${author.name}")
            _notices.tryEmit(InteractionNotice(moment.id, emptyList(), emptyList(), replyFailed = true))
            return "REPLY_FAILED"
        }

        val comment = repository.insertComment(
            momentId = moment.id,
            sender = "assistant",
            content = reply,
            senderId = author.id,
            senderName = author.name,
        )
        if (comment != null) {
            Logger.i(TAG, "作者回复成功: moment=${moment.id}, author=${author.name}")
            return null // 成功
        }
        Logger.w(TAG, "作者回复落库失败: moment=${moment.id}")
        return "REPLY_FAILED"
    }

    companion object {
        private const val TAG = "MomentInteract"

        /** 单轮互动最多产生多少条消息(点赞+评论合计)。 */
        private const val MAX_TOTAL_INTERACTIONS = 5
    }
}
