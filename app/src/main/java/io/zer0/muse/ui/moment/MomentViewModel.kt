package io.zer0.muse.ui.moment

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.zer0.ai.ChatService
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.memory.fact.FactStore
import io.zer0.muse.data.moment.MomentCommentEntity
import io.zer0.muse.data.moment.MomentEntity
import io.zer0.muse.data.moment.MomentRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * v1.0.72: 朋友圈 ViewModel — 动态列表/点赞/评论/删除。
 *
 * 评论回复:用户评论后调 LLM 基于记忆生成一条 AI 回复(一条动态只回一轮)。
 */
class MomentViewModel(
    application: Application,
    private val repository: MomentRepository,
    private val chatService: ChatService?,
    private val factStore: FactStore?,
) : AndroidViewModel(application) {

    private val TAG = "MomentVM"

    data class MomentUiState(
        val moments: List<MomentEntity> = emptyList(),
        val comments: Map<String, List<MomentCommentEntity>> = emptyMap(),
        val isLoading: Boolean = true,
    )

    private val _state = MutableStateFlow(MomentUiState())
    val state: StateFlow<MomentUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true)
            val moments = withContext(Dispatchers.IO) {
                repository.getAll(100)
            }
            // 加载所有动态的评论
            val commentsMap = mutableMapOf<String, List<MomentCommentEntity>>()
            moments.forEach { m ->
                commentsMap[m.id] = repository.getComments(m.id)
            }
            _state.value = MomentUiState(
                moments = moments,
                comments = commentsMap,
                isLoading = false,
            )
        }
    }

    /** 点赞/取消点赞。 */
    fun toggleLike(moment: MomentEntity) {
        viewModelScope.launch {
            val updated = repository.toggleLike(moment)
            _state.value = _state.value.copy(
                moments = _state.value.moments.map { if (it.id == moment.id) updated else it },
            )
        }
    }

    /** 用户评论(LLM 回复一轮)。 */
    fun addComment(moment: MomentEntity, text: String) {
        viewModelScope.launch {
            // 用户评论入列
            val userComment = repository.insertComment(moment.id, "user", text)
            updateComments(moment.id, userComment)

            // AI 回复(基于记忆)
            val reply = generateReply(moment.content, text)
            if (reply != null) {
                val aiComment = repository.insertComment(moment.id, "assistant", reply)
                updateComments(moment.id, aiComment)
            }
        }
    }

    /** 删除动态。 */
    fun deleteMoment(moment: MomentEntity) {
        viewModelScope.launch {
            repository.deleteMoment(moment.id)
            _state.value = _state.value.copy(
                moments = _state.value.moments.filterNot { it.id == moment.id },
                comments = _state.value.comments - moment.id,
            )
        }
    }

    private fun updateComments(momentId: String, comment: MomentCommentEntity) {
        _state.value = _state.value.copy(
            comments = _state.value.comments.toMutableMap().apply {
                put(momentId, (this[momentId] ?: emptyList()) + comment)
            },
        )
    }

    /** LLM 生成评论回复(失败返回 null,不阻塞用户评论显示)。 */
    private suspend fun generateReply(momentContent: String, userComment: String): String? {
        val service = chatService ?: return null
        val facts = resultOf { factStore?.getAll("main") }.getOrNull() ?: emptyList()
        val recentFacts = facts.take(5).joinToString("; ") { it.fact }

        val prompt = buildString {
            appendLine("你在朋友圈发了一条动态:\"$momentContent\"")
            appendLine("用户评论:\"$userComment\"")
            appendLine("请基于下面的记忆,用一句话自然回复这条评论(15-40 字,口语化,不要官方腔):")
            if (recentFacts.isNotBlank()) {
                appendLine("记忆素材: $recentFacts")
            }
        }

        return resultOf {
            withTimeoutOrNull(15_000L) {
                service.completeText(
                    messages = listOf(
                        io.zer0.ai.core.UIMessage(
                            role = io.zer0.ai.core.MessageRole.USER,
                            content = prompt,
                            createdAt = System.currentTimeMillis(),
                        ),
                    ),
                    temperature = 0.8f,
                    maxTokens = 80,
                ).text.trim()
            }
        }.onError { msg, t ->
            Logger.w(TAG, "评论回复生成失败: ${t?.message ?: msg}")
        }.getOrNull()?.takeIf { it.isNotBlank() && it != "null" }
    }
}
