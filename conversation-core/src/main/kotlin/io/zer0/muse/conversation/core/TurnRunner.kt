package io.zer0.muse.conversation.core

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch

/**
 * Runs one provider turn through the draft reducer and a serial checkpoint
 * writer. Tool execution and rich content are separate ports added around this
 * core loop; a provider-only turn still has a complete terminal contract.
 */
class TurnRunner(
    private val provider: ProviderPort,
    private val checkpointPort: CheckpointPort,
    dispatcher: CoroutineDispatcher,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val draftStore = DraftStore()
    private val checkpointWriter = CheckpointWriter(checkpointPort, dispatcher)

    @Volatile
    var failureMessage: String? = null
        private set

    @Volatile
    var terminalCommits: Int = 0
        private set

    fun run(request: ProviderRequest): Job {
        return scope.launch {
            var sequence = 0L
            draftStore.apply(DraftEvent.Start(++sequence, request.identity, System.currentTimeMillis()))
            var terminal = TerminalState.Completed
            provider.stream(request)
                .onCompletion { error ->
                    if (error != null && failureMessage == null) {
                        failureMessage = error.message
                        terminal = TerminalState.Failed
                    }
                }
                .collect { event ->
                    sequence += 1
                    when (event) {
                        is ProviderEvent.Content ->
                            draftStore.apply(
                                DraftEvent.AppendText(sequence, request.identity, TEXT_PART, event.text),
                            )
                        is ProviderEvent.Reasoning ->
                            draftStore.apply(
                                DraftEvent.AppendReasoning(sequence, request.identity, REASONING_PART, event.text),
                            )
                        is ProviderEvent.Failed -> {
                            failureMessage = event.message
                            terminal = TerminalState.Failed
                        }
                        is ProviderEvent.Interrupted -> {
                            failureMessage = event.message
                            terminal = TerminalState.Interrupted
                        }
                        is ProviderEvent.Completed -> Unit
                        is ProviderEvent.ToolCall,
                        is ProviderEvent.Image,
                        is ProviderEvent.Fallback,
                        is ProviderEvent.Usage,
                        is ProviderEvent.Citation,
                        -> Unit
                    }
                }
            draftStore.apply(DraftEvent.Finish(sequence + 1, request.identity, terminal))
            val snapshot = draftStore.snapshot()
            checkpointWriter.schedule(
                CheckpointSnapshot(
                    identity = request.identity,
                    sequence = sequence,
                    text = snapshot.parts.filter { it.kind == PartKind.Text }.joinToString("") { it.text },
                    parts = snapshot.parts,
                ),
            )
            checkpointWriter.terminal(
                CheckpointSnapshot(
                    identity = request.identity,
                    sequence = sequence + 1,
                    text = snapshot.parts.filter { it.kind == PartKind.Text }.joinToString("") { it.text },
                    parts = snapshot.parts,
                ),
            ).join()
            terminalCommits++
        }
    }

    private companion object {
        const val TEXT_PART = "assistant:text"
        const val REASONING_PART = "assistant:reasoning"
    }
}
