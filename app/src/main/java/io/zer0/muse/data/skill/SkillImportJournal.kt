package io.zer0.muse.data.skill

import java.io.File

/**
 * 技能包导入的**恢复 journal**（对比清单 P1-09）。
 *
 * 现有"失败即回滚"只覆盖**同一次调用内**的失败。真正难缠的是进程被杀：
 * 复制到一半的目录留在 `workspace/skills/<id>` 下，下次导入同名包会被判成"已存在"，
 * 用户看到的是"明明没装成功，却说我已安装"——而且那个目录是残缺的。
 *
 * 做法：导入前写一个标记文件，成功后删掉；下次导入前扫一遍标记，
 * 把没有对应成功记录的半成品目录清掉。
 *
 * 一条安全约束：清理时**只删根目录之下的路径**。标记文件是本地文件，理论上可被篡改，
 * 一个写着 `/sdcard` 的标记不该让应用去删别人的东西。
 */
class SkillImportJournal(private val root: File) {

    companion object {
        /** 标记文件前缀；以点开头，不会和技能 id 冲突（id 不以点开头）。 */
        private const val PREFIX = ".importing-"
        private const val SUFFIX = ".journal"
    }

    private fun markerFor(skillId: String): File = File(root, "$PREFIX$skillId$SUFFIX")

    /** 导入开始：记录目标目录。写失败不阻断导入（journal 是保险，不是前置条件）。 */
    fun begin(skillId: String, target: File) {
        runCatching {
            root.mkdirs()
            markerFor(skillId).writeText("${target.absolutePath}\n${System.currentTimeMillis()}")
        }
    }

    /** 导入成功：删掉标记。 */
    fun commit(skillId: String) {
        runCatching { markerFor(skillId).delete() }
    }

    /**
     * 清理上次中断留下的半成品。
     *
     * @return 被清理的 skillId 列表（供日志与界面提示）
     */
    fun recover(): List<String> {
        if (!root.isDirectory) return emptyList()
        val markers = root.listFiles { file -> file.isFile && file.name.startsWith(PREFIX) && file.name.endsWith(SUFFIX) }
            ?: return emptyList()
        val recovered = mutableListOf<String>()
        markers.forEach { marker ->
            val skillId = marker.name.removePrefix(PREFIX).removeSuffix(SUFFIX)
            val targetPath = runCatching { marker.readLines().firstOrNull() }.getOrNull()
            if (!targetPath.isNullOrBlank()) {
                val target = File(targetPath)
                if (isInsideRoot(target)) {
                    runCatching { target.deleteRecursively() }
                }
            }
            runCatching { marker.delete() }
            recovered += skillId
        }
        return recovered
    }

    /** 目标必须真的在根目录之下（规范化后比较，挡住 `../` 与符号链接式拼接）。 */
    private fun isInsideRoot(target: File): Boolean = runCatching {
        val rootPath = root.canonicalFile.path + File.separator
        target.canonicalFile.path.startsWith(rootPath)
    }.getOrDefault(false)
}
