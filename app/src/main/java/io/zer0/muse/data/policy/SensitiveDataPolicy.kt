package io.zer0.muse.data.policy

import java.io.File

/**
 * 本地敏感内容的**保存政策**（对比清单 P2-05）。
 *
 * 现状是各写各的：插件侧有 `chat_read` 只给最近 30 条、`model_call` 输入 8KB/输出 4KB、
 * 审计环形缓冲；而**工具输出落盘没有任何保留期限** —— 一个跑过几十次构建的设备上，
 * `tool_outputs/` 会留下几十 MB 的命令输出、文件片段、日志，其中可能包含令牌、路径、个人内容。
 *
 * 这份政策只回答三个问题，且都做成纯函数，便于在没有文件系统的环境里验证：
 *  1. 多大的内容可以**内联**给模型（超了就落盘 + 预览）；
 *  2. 落盘内容**保留多久**；
 *  3. 预览要不要**脱敏**（去掉可能的凭据形状）。
 */
object SensitiveDataPolicy {

    /**
     * 数据类别。**一张表定死"这类内容能留多久、能不能进备份/诊断"** ——
     * 分散在各个消费方里各写一遍，迟早出现"工具输出 7 天、MCP 结果永久"这种不一致。
     */
    enum class Category(
        val wire: String,
        val inlineLimitChars: Int,
        val retentionDays: Int,
        /** 是否允许进入用户备份（导出/云备份）。 */
        val mayEnterBackup: Boolean,
        /** 是否允许进入诊断日志。 */
        val mayEnterDiagnostics: Boolean,
    ) {
        /** 命令/工具输出：短、敏感、可重跑 → 不进备份，进诊断。 */
        TOOL_OUTPUT("tool_output", inlineLimitChars = 400, retentionDays = 7, mayEnterBackup = false, mayEnterDiagnostics = true),

        /** MCP 工具结果：可能含第三方系统数据 → 最短保留、两处都不进。 */
        MCP_RESULT("mcp_result", inlineLimitChars = 800, retentionDays = 3, mayEnterBackup = false, mayEnterDiagnostics = false),

        /** 记忆原文：**是用户资产**，允许备份，保留最长。 */
        MEMORY_RAW("memory_raw", inlineLimitChars = 1200, retentionDays = 30, mayEnterBackup = true, mayEnterDiagnostics = false),

        /** 通知历史：内容是别人发来的私信摘要，**不进备份、不进诊断**，只留三天。 */
        NOTIFICATION("notification", inlineLimitChars = 300, retentionDays = 3, mayEnterBackup = false, mayEnterDiagnostics = false),

        /** 系统上下文快照（读屏/通知等）：时效性极强，隔天就没意义。 */
        SYSTEM_CONTEXT("system_context", inlineLimitChars = 600, retentionDays = 1, mayEnterBackup = false, mayEnterDiagnostics = true),
        ;

        companion object {
            fun parse(raw: String?): Category? = entries.firstOrNull { it.wire == raw?.trim()?.lowercase() }
        }
    }

    /** 按类别取规则。 */
    fun rulesFor(category: Category): Category = category

    /** 按类别决定是否清理。 */
    fun shouldPurge(category: Category, lastModifiedMillis: Long, nowMillis: Long): Boolean =
        shouldPurge(lastModifiedMillis, nowMillis, category.retentionDays)

    /** 按类别的内联上限做预览。 */
    fun previewFor(category: Category, text: String): String = preview(text, category.inlineLimitChars)

    /** 落盘输出的保留天数：够复现问题，又不至于长期堆着。 */
    const val TOOL_OUTPUT_RETENTION_DAYS = 7

    /** 给模型看的预览长度上限（字符）。 */
    const val PREVIEW_MAX_CHARS = 400

    /** 是否该删掉这个落盘文件。 */
    fun shouldPurge(
        lastModifiedMillis: Long,
        nowMillis: Long,
        retentionDays: Int = TOOL_OUTPUT_RETENTION_DAYS,
    ): Boolean {
        if (retentionDays <= 0) return false
        val age = nowMillis - lastModifiedMillis
        // 时钟回拨（文件比现在还"新"）时不删：宁可多留，也不要因为一次时间跳变清空用户数据
        if (age < 0) return false
        return age >= retentionDays * 24L * 60 * 60 * 1000
    }

    /**
     * 预览：压成单行并截断。
     *
     * 只做"少给"，不做"改内容" —— 预览里出现的原文就是文件里的原文，
     * 这样模型不会因为看不到关键行而误判。
     */
    fun preview(text: String, maxChars: Int = PREVIEW_MAX_CHARS): String {
        val singleLine = text.replace(Regex("[\\r\\n]+"), " ").trim()
        if (singleLine.length <= maxChars) return singleLine
        return singleLine.take(maxChars.coerceAtLeast(0)) + "…"
    }

    /**
     * 疑似凭据的形状：只用于**提示**（例如"这段内容可能含凭据，已落盘不内联"），
     * 不做自动改写 —— 静默替换会让用户以为原文如此。
     */
    fun looksLikeCredential(text: String): Boolean {
        val patterns = listOf(
            Regex("(?i)bearer\\s+[A-Za-z0-9._-]{16,}"),
            Regex("(?i)(api[_-]?key|token|secret|password)\\s*[=:]\\s*[A-Za-z0-9._-]{12,}"),
            Regex("sk-[A-Za-z0-9]{20,}"),
        )
        return patterns.any { it.containsMatchIn(text) }
    }
}

/**
 * 落盘输出的过期清理。
 *
 * 与 [SensitiveDataPolicy.shouldPurge] 配合：政策决定"该不该删"，这里负责"真的删"。
 * 清理是**尽力而为**的：删不掉（占用中）就跳过，不能让一次清理失败影响工具执行。
 */
object ToolOutputRetention {

    /** 每个进程只扫一次：工具输出目录可能很大，没必要每次落盘都遍历。 */
    @Volatile
    private var purgedThisProcess = false

    /** @return 删掉的文件数 */
    fun purgeOnce(
        directory: File,
        nowMillis: Long,
        retentionDays: Int = SensitiveDataPolicy.TOOL_OUTPUT_RETENTION_DAYS,
    ): Int {
        if (purgedThisProcess) return 0
        purgedThisProcess = true
        return purge(directory, nowMillis, retentionDays)
    }

    /** 直接执行一次清理（测试与"手动清理"入口用）。 */
    fun purge(
        directory: File,
        nowMillis: Long,
        retentionDays: Int = SensitiveDataPolicy.TOOL_OUTPUT_RETENTION_DAYS,
    ): Int {
        if (!directory.isDirectory) return 0
        val expired = directory.listFiles { file ->
            file.isFile && SensitiveDataPolicy.shouldPurge(file.lastModified(), nowMillis, retentionDays)
        } ?: return 0
        return expired.count { file -> runCatching { file.delete() }.getOrDefault(false) }
    }

    /** 仅测试用：重置"本进程已清理"标记。 */
    internal fun resetForTests() {
        purgedThisProcess = false
    }
}
