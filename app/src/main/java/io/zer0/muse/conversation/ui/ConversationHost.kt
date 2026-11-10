package io.zer0.muse.conversation.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import io.zer0.common.resultOf
import io.zer0.muse.conversation.core.CommandResult
import io.zer0.muse.conversation.core.ConversationCommand
import kotlinx.coroutines.launch

@Composable
fun ConversationHost(
    sessionId: String,
    loadSession: suspend (String) -> ConversationPageModel,
    submit: suspend (ConversationCommand.Submit) -> CommandResult,
    commandId: (String) -> String,
    screen: @Composable (ConversationPageModel, (String) -> Unit) -> Unit,
) {
    require(sessionId.isNotBlank()) { "session id must not be blank" }
    val currentLoadSession by rememberUpdatedState(loadSession)
    val currentSubmit by rememberUpdatedState(submit)
    val currentCommandId by rememberUpdatedState(commandId)
    var state by remember(sessionId) { mutableStateOf(ConversationHostState(sessionId)) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(sessionId) {
        state = ConversationHostState(sessionId)
        // `resultOf` 是仓库自己的包装：它**照样重抛 CancellationException**（见 common/Result.kt），
        // 所以取消语义与原来逐字相同；差别只是"每个边界都手写一遍 catch(Throwable)"
        // 收敛回一处实现。错误文案仍取原始异常的 message，兜底句保持原样。
        resultOf { state.copy(model = currentLoadSession(sessionId)) }
            .onSuccess { next -> state = next }
            .onError { _, error -> state = state.copy(error = error?.message ?: "conversation load failed") }
    }

    state.model?.let { model ->
        screen(model) { text ->
            if (!state.isSubmitting) {
                state = state.copy(isSubmitting = true, error = null)
                scope.launch {
                    // 命令构造要放在 try 里面：`ConversationCommand.Submit` 的入参校验
                    // （空白文本、空会话 id 等）是 `require` 抛出的，放在外面等于
                    // 把一次"提交没成功"变成协程里的未捕获异常 —— 用户什么也看不到。
                    // 下面那个 catch 本来就负责显示原因，让它有机会工作。
                    // 命令构造也放进块里：`ConversationCommand.Submit` 的入参校验是 `require`
                    // 抛出的，放外面等于把一次"提交没成功"变成协程里的未捕获异常 ——
                    // 用户点下去什么也看不到。现在它落到下面的 onError，原因显示在界面上。
                    resultOf {
                        val command =
                            ConversationCommand.Submit(
                                commandId = currentCommandId(sessionId),
                                sessionId = sessionId,
                                text = text,
                                selectionSetId = "legacy:$sessionId",
                            )
                        currentSubmit(command)
                        if (model.state.value.input == text) {
                            model.updateInput("")
                        }
                        state.copy(isSubmitting = false)
                    }.onSuccess { next -> state = next }
                        .onError { _, error ->
                            state = state.copy(
                                isSubmitting = false,
                                error = error?.message ?: "conversation submit failed",
                            )
                        }
                }
            }
        }
    }
}
