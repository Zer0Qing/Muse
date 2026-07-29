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
    )

    suspend fun execute(steps: List<Step>): Result = withContext(Dispatchers.IO) {
        val startMs = System.currentTimeMillis()
        val outputs = mutableMapOf<String, String>()
        val errors = mutableListOf<String>()
        for (step in steps) {
            executeStep(step, outputs, errors)
        }
        Result(outputs = outputs.toMap(), durationMs = System.currentTimeMillis() - startMs, errors = errors.toList())
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
