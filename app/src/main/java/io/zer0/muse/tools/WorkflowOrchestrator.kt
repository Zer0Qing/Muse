package io.zer0.muse.tools

import io.zer0.common.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * 工作流编排器(openhanako workflow-tool.ts 移植)。
 *
 * 简化编排:并行 / 流水线 / 顺序,基于 Kotlin 协程。
 */
class WorkflowOrchestrator {

    sealed class Step {
        data class Task(val name: String, val action: suspend () -> String) : Step()
        data class Parallel(val tasks: List<Step>) : Step()
        data class Pipeline(val steps: List<Step>) : Step()
    }

    data class Result(
        val outputs: Map<String, String>,
        val durationMs: Long,
        val errors: List<String> = emptyList(),
        /** v1.0.53 Phase 3: 是否因 token 预算耗尽而跳过部分节点。 */
        val budgetExhausted: Boolean = false,
    )

    /**
     * 执行编排好的步骤列表。
     *
     * v1.0.53 Phase 3: 支持 token 预算硬上限。
     *  - [budgetTokens] 非 null 时,每个节点执行前检查预算是否耗尽,耗尽则跳过并记录错误。
     *  - 注意:[Step.Task] 的 action 是黑盒,本编排器无法自动累加 token 消耗;
     *    如需精确控制,调用方应在 action 内部通过闭包捕获外部 [AgentTokenBudget] 实例并 accumulate,
     *    然后通过 [budgetTokens] 传入同一上限,本方法会创建独立 budget 实例做框架级检查。
     *
     * @param budgetTokens token 预算上限;null=不限制。
     */
    suspend fun execute(steps: List<Step>, budgetTokens: Int? = null): Result = withContext(Dispatchers.IO) {
        val startMs = System.currentTimeMillis()
        val outputs = mutableMapOf<String, String>()
        val errors = mutableListOf<String>()
        val budget = AgentTokenBudget.of(budgetTokens)
        var budgetExhausted = false
        for (step in steps) {
            // v1.0.53 Phase 3: 预算耗尽则跳过剩余节点
            if (budget?.isExhausted == true) {
                budgetExhausted = true
                errors.add("节点被跳过:token 预算耗尽(剩余 ${budget.remaining})")
                continue
            }
            executeStep(step, outputs, errors)
        }
        Result(
            outputs = outputs.toMap(),
            durationMs = System.currentTimeMillis() - startMs,
            errors = errors.toList(),
            budgetExhausted = budgetExhausted,
        )
    }

    private suspend fun executeStep(step: Step, outputs: MutableMap<String, String>, errors: MutableList<String>) {
        when (step) {
            is Step.Task -> {
                try {
                    outputs[step.name] = step.action()
                    Logger.d(TAG, "Task '${step.name}' completed")
                } catch (e: Exception) {
                    errors.add("Task '${step.name}' failed: ${e.message}")
                    Logger.w(TAG, "Task '${step.name}' failed", e)
                }
            }
            is Step.Parallel -> {
                coroutineScope {
                    step.tasks.map { child -> async { executeStep(child, outputs, errors) } }.awaitAll()
                }
            }
            is Step.Pipeline -> {
                for (child in step.steps) executeStep(child, outputs, errors)
            }
        }
    }

    class Builder {
        internal val steps = mutableListOf<Step>()
        fun task(name: String, action: suspend () -> String) { steps.add(Step.Task(name, action)) }
        fun parallel(block: Builder.() -> Unit) {
            val inner = Builder(); inner.block(); steps.add(Step.Parallel(inner.steps.toList()))
        }
        fun pipeline(block: Builder.() -> Unit) {
            val inner = Builder(); inner.block(); steps.add(Step.Pipeline(inner.steps.toList()))
        }
    }

    companion object {
        private const val TAG = "WorkflowOrchestrator"
        fun build(block: Builder.() -> Unit): List<Step> = Builder().apply(block).steps.toList()
    }
}
