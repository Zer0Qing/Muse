package io.zer0.muse.tools

import io.zer0.muse.terminal.TerminalJobRegistry
import java.io.File

/**
 * 后台终端 job 的工具层（对比清单 P1-07）。
 *
 * 与 `terminal_exec` 的分工：
 *  - `terminal_exec` 同步等待、≤60 秒，适合"马上要结果"的命令；
 *  - 本组工具是**长任务合同**：提交后立刻返回 `job_id`，之后轮询状态与输出，必要时终止。
 *
 * 安全边界与 `terminal_exec` 同一条地板（[ShellCommandBoundary] 第一档），
 * 不因为"反正后台跑"就放松 —— 后台反而更容易被遗忘。
 */
object TerminalJobTools {

    /** 进程级台账：job 要在多次工具调用之间存活。 */
    private val registry = TerminalJobRegistry()

    internal fun registryForTests(): TerminalJobRegistry = registry

    fun register(toolRegistry: ToolRegistry, workspaceDir: File) {
        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "terminal_job_start",
                description = "提交一条后台 shell 命令（应用沙盒内，工作目录为应用工作区）。" +
                    "立刻返回 job_id，用 terminal_job_status 查看进度与输出。" +
                    "适合构建、下载、长脚本；需要立刻要结果的短命令仍用 terminal_exec。",
                parameters = mapOf(
                    "command" to "必填。要执行的 shell 命令（管道与重定向可用）",
                    "label" to "可选。便于识别的名字，默认取命令前 40 个字符",
                ),
                required = setOf("command"),
                category = "built-in",
                riskLevel = ToolRiskLevel.HIGH,
            ),
        ) { args ->
            val command = args["command"]?.trim().orEmpty()
            if (command.isEmpty()) return@register "错误:command 不能为空"
            // 与 terminal_exec 同一条地板：后台不是绕过安全边界的理由
            ShellCommandBoundary.forbidReason(command, workspaceDir)?.let { reason ->
                return@register "错误: 命令被安全边界拒绝 — $reason。"
            }
            val label = args["label"]?.takeIf { it.isNotBlank() } ?: command.take(40)
            when (val result = registry.submit(label, command, ShellJobLauncher(workspaceDir))) {
                is TerminalJobRegistry.SubmitResult.Accepted ->
                    "已提交：job_id=${result.job.id}\n命令：${result.job.command}\n" +
                        "用 terminal_job_status(job_id=${result.job.id}) 查看进度"
                is TerminalJobRegistry.SubmitResult.Rejected -> when (result.reason) {
                    TerminalJobRegistry.Rejection.EMPTY_COMMAND -> "错误:command 不能为空"
                    TerminalJobRegistry.Rejection.TOO_MANY_RUNNING ->
                        "已有 ${result.running} 个后台任务在跑（上限 4）。" +
                            "先用 terminal_job_list 看进度，或终止不需要的任务再提交。"
                }
            }
        }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "terminal_job_status",
                description = "查看后台任务的运行状态、退出码与输出尾部。",
                parameters = mapOf("job_id" to "必填。terminal_job_start 返回的 job_id"),
                required = setOf("job_id"),
                category = "built-in",
                riskLevel = ToolRiskLevel.NORMAL,
            ),
        ) { args ->
            val id = args["job_id"]?.trim().orEmpty()
            val snapshot = registry.get(id)
                ?: return@register "没有这个 job（job_id=$id）。可用 terminal_job_list 查看现有任务。"
            buildString {
                appendLine("job_id=${snapshot.id}  状态=${stateLabel(snapshot.state)}")
                appendLine("命令：${snapshot.command}")
                snapshot.exitCode?.let { appendLine("退出码：$it") }
                appendLine("已运行：${(snapshot.endedAt ?: System.currentTimeMillis()) - snapshot.startedAt} ms")
                if (snapshot.tail.isNotBlank()) {
                    appendLine("--- 输出尾部 ---")
                    append(snapshot.tail)
                } else {
                    appendLine("(暂无输出)")
                }
            }
        }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "terminal_job_kill",
                description = "终止一个仍在运行的后台任务。终止后退出码不可信，状态显示为已终止。",
                parameters = mapOf("job_id" to "必填。要终止的 job_id"),
                required = setOf("job_id"),
                category = "built-in",
                riskLevel = ToolRiskLevel.HIGH,
            ),
        ) { args ->
            val id = args["job_id"]?.trim().orEmpty()
            if (registry.kill(id)) {
                "已终止 job_id=$id"
            } else {
                "未能终止 job_id=$id：它可能已经结束，或这个 id 不存在（terminal_job_list 可查）"
            }
        }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "terminal_job_list",
                description = "列出当前台账里的后台任务（含已结束的，最多保留 16 条）。",
                parameters = emptyMap(),
                category = "built-in",
                riskLevel = ToolRiskLevel.NORMAL,
            ),
        ) { _ ->
            val jobs = registry.list()
            if (jobs.isEmpty()) {
                "当前没有后台任务"
            } else {
                jobs.joinToString("\n") { job ->
                    "job_id=${job.id}  ${stateLabel(job.state)}  ${job.label}" +
                        (job.exitCode?.let { "  exit=$it" } ?: "")
                }
            }
        }
    }

    private fun stateLabel(state: TerminalJobRegistry.State): String = when (state) {
        TerminalJobRegistry.State.RUNNING -> "运行中"
        TerminalJobRegistry.State.FINISHED -> "已结束"
        TerminalJobRegistry.State.FAILED -> "失败"
        TerminalJobRegistry.State.KILLED -> "已终止"
    }
}

/** 真实进程的启动器：`sh -c` + 读线程。 */
internal class ShellJobLauncher(private val workspaceDir: File) : TerminalJobRegistry.Launcher {

    override fun launch(command: String, onOutput: (String) -> Unit): TerminalJobRegistry.Handle {
        val process = ProcessBuilder("/system/bin/sh", "-c", command)
            .directory(workspaceDir)
            .redirectErrorStream(true)
            .start()
        val reader = Thread {
            runCatching {
                val buffer = CharArray(4096)
                process.inputStream.bufferedReader().use { reader ->
                    while (true) {
                        val n = reader.read(buffer)
                        if (n < 0) break
                        onOutput(String(buffer, 0, n))
                    }
                }
            }
        }.apply {
            isDaemon = true
            name = "muse-terminal-job"
            start()
        }
        return object : TerminalJobRegistry.Handle {
            override fun waitFor(): Int? {
                process.waitFor()
                reader.join(2_000)
                return process.exitValue()
            }

            override fun kill() {
                runCatching { process.destroyForcibly() }
            }
        }
    }
}
