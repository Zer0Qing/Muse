package io.zer0.muse.data.skill

import io.zer0.muse.util.SafePaths
import java.io.File

/**
 * 技能包内**资源文件**的只读访问（对比清单 P1-09）。
 *
 * 标准 `SKILL.md` 包除了入口清单，通常还带 `references/`、`scripts/`、`assets/` 之类的目录。
 * 导入时它们会被整包复制过来，但此前**没有任何入口能让模型读到** ——
 * 技能正文说"详见 references/schema.md"，模型却打不开那个文件。
 *
 * 三条约束：
 *  1. **路径必须留在包目录内**：`../../` 一律拒绝（交给 [SafePaths.safeResolve] 判定）；
 *  2. **有大小上限**：资源是参考资料，不是数据集，塞进上下文既没意义又贵；
 *  3. **只读**：技能资源不提供写入，要改就重新导入一个包。
 */
class SkillPackageResources(private val root: File) {

    companion object {
        /** 单文件上限。 */
        const val MAX_RESOURCE_BYTES = 64 * 1024

        /** 列表上限：目录里塞一万个文件时不该把上下文撑爆。 */
        const val MAX_RESOURCE_FILES = 200

        /** 清单文件名：它是入口，正文已经随技能调用注入过，不算资源。 */
        const val MANIFEST_FILE = "SKILL.md"
    }

    /** 读取结果。用显式类型而不是裸 String，避免"空文件"与"失败"混在一起。 */
    sealed interface Outcome {
        data class Text(val content: String) : Outcome

        data class Failed(val reason: String) : Outcome
    }

    private fun dirFor(skillId: String): File = File(root, skillId)

    /** 列出包内资源（相对路径，`/` 分隔，按名称排序）。 */
    fun list(skillId: String): List<String> {
        val dir = dirFor(skillId)
        if (!dir.isDirectory) return emptyList()
        return dir.walkTopDown()
            .filter { it.isFile }
            .map { it.relativeTo(dir).invariantSeparatorsPath }
            .filter { it != MANIFEST_FILE }
            .sorted()
            .take(MAX_RESOURCE_FILES)
            .toList()
    }

    /** 读一个资源文件的文本内容。 */
    fun read(skillId: String, relativePath: String): Outcome {
        val trimmed = relativePath.trim()
        val file = if (trimmed.isEmpty()) {
            null
        } else {
            SafePaths.safeResolve(dirFor(skillId), trimmed)
        }
        return when {
            trimmed.isEmpty() -> Outcome.Failed("path 不能为空")
            file == null -> Outcome.Failed("路径不合法：只能读取技能包内的文件")
            !file.isFile -> Outcome.Failed("文件不存在：$trimmed")
            file.length() > MAX_RESOURCE_BYTES ->
                Outcome.Failed("文件过大（上限 ${MAX_RESOURCE_BYTES / 1024} KB）：$trimmed")
            else -> runCatching { file.readText() }.fold(
                onSuccess = { Outcome.Text(it) },
                onFailure = { Outcome.Failed("读取失败：${it.message ?: it.javaClass.simpleName}") },
            )
        }
    }
}
