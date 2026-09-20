package io.zer0.muse.data.moment

import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.data.assistant.AssistantRepository
import io.zer0.muse.data.session.SessionRepository
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.asSharedFlow
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
) {
    private val TAG = "MomentInteract"

    /** UI 需要的互动结果(用于横幅提示)。 */
    data class InteractionNotice(
        val momentId: String,
        val likerNames: List<String>,
        val commenterNames: List<String>,
        val replyFailed: Boolean = false,
    )

    private val _notices = kotlinx.coroutines.flow.MutableSharedFlow<InteractionNotice>(extraBufferCapacity = 8)
    /** 互动结果通知(UI 收集后弹横幅)。 */
    val notices: kotlinx.coroutines.flow.SharedFlow<InteractionNotice> = _notices.asSharedFlow()

    /** 单轮互动最多产生多少条消息(点赞+评论合计)。 */
    private val MAX_TOTAL_INTERACTIONS = 5

    /** 已触发互动的动态 id 集合(防重复触发)。 */
    private val interactedMomentIds = mutableSetOf<String>()

    /**
     * 用户发布动态后触发互动(异步,不阻塞 UI)。
     * [source] 用于区分"用户发布"vs"调度器生成",仅影响日志。
     */
    fun triggerOnUserPublish(moment: MomentEntity, source: String = "user_publish") {
        scope.launch {
            runInteraction(
                moment = moment,
                author = null,  // 用户发布,author 为 null
                isUserComment = false,  // 不是评论场景
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
                isUserComment = false,
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

    private suspend fun sendPrivateMessageToMiniPhone(
        moment: MomentEntity,
        author: io.zer0.muse.data.assistant.AssistantEntity,
    ) {
        // 40% 概率触发私信
        if (Random.nextFloat() >= 0.4f) return

        val delayMs = Random.nextLong(5000L, 60000L)
        delay(delayMs)

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

        // 查找或创建该助手的 isMin iPhone 会话
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

        Logger.i(TAG, "[$TAG] 小手机私信已发送: moment=${moment.id}, author=${author.name}, sessionId=$sessionId")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 内部实现
    // ─────────────────────────────────────────────────────────────────────────

    /** 核心互动逻辑:点赞 + 评论(异步,有随机延迟)。 */
    private suspend fun runInteraction(
        moment: MomentEntity,
        author: io.zer0.muse.data.assistant.AssistantEntity?,
        isUserComment: Boolean,
        source: String,
    ) {
        // 防重复:同一动态已互动过则跳过
        if (!interactedMomentIds.add(moment.id)) {
            Logger.d(TAG, "动态 $moment.id 已互动过,跳过 [$source]")
            return
        }

        // P2-6: 停用的助手不参与朋友圈点赞/评论
        val allAssistants = (resultOf { assistantRepository.getAll() }.getOrNull() ?: emptyList()).filter { it.enabled }
        // 排除作者本人
        val others = allAssistants.filter { it.id != author?.id }
        if (others.isEmpty()) {
            Logger.d(TAG, "无可用助手参与互动: moment=${moment.id}")
            return
        }

        val images = moment.images().take(4)
        var totalInteractions = 0
        val landedLikers = mutableListOf<String>()
        val landedCommenters = mutableListOf<String>()

        // 随机挑 1-3 个助手点赞(60% 概率)
        val likers = if (Random.nextFloat() < 0.6f) {
            others.shuffled(Random).take(Random.nextInt(1, minOf(3, others.size) + 1))
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

        // 随机挑 1 个助手评论(30% 概率,LLM 生成)
        if (totalInteractions < MAX_TOTAL_INTERACTIONS && Random.nextFloat() < 0.3f) {
            val commenter = others[Random.nextInt(others.size)]
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
    private suspend fun runAuthorReply(
        moment: MomentEntity,
        userComment: String,
    ): String? {
        val authorId = moment.senderId ?: return null
        val author = resultOf { assistantRepository.getById(authorId) }.getOrNull()
            ?: return null  // 作者不存在,无法回复

        // 防死循环:只处理"用户主动评论",助手回复不触发新回复
        // (由调用方保证 isUserComment=true,此处不重复检查)

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
            return null  // 成功
        }
        Logger.w(TAG, "作者回复落库失败: moment=${moment.id}")
        return "REPLY_FAILED"
    }
}
