package io.zer0.muse.data.skill

import io.zer0.common.Logger
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
        private const val TAG = "SkillImportJournal"
    }

    private fun markerFor(skillId: String): File = File(root, "$PREFIX$skillId$SUFFIX")

    /**
     * 导入开始：记录目标目录。写失败不阻断导入（journal 是保险，不是前置条件）。
     *
     * 但**必须留痕**：这一行没写成功，等于这次导入没有崩溃保险 ——
     * 进程真被杀在半路时，残留的半截目录不会被任何机制清掉，
     * 用户下次装同名包会看到"已存在"，而那个目录是坏的。
     */
    fun begin(skillId: String, target: File) {
        runCatching {
            root.mkdirs()
            markerFor(skillId).writeText("${target.absolutePath}\n${System.currentTimeMillis()}")
        }.onFailure { e ->
            Logger.w(TAG, "导入 journal 标记写入失败 skillId=$skillId（本次导入没有崩溃保险）: ${e.message ?: e.javaClass.simpleName}", e)
        }
    }

    /** 导入成功：删掉标记。删不掉也留痕——它会下次被当成半成品去清。 */
    fun commit(skillId: String) {
        val marker = markerFor(skillId)
        // 注意 `File.delete()` 失败是返回 false 而不是抛异常，两种都要算没删掉
        val gone = runCatching { !marker.exists() || marker.delete() }
            .onFailure { e ->
                Logger.w(TAG, "导入 journal 标记删除异常 skillId=$skillId: ${e.message ?: e.javaClass.simpleName}", e)
            }
            .getOrDefault(false)
        if (!gone) {
            Logger.w(TAG, "导入 journal 标记仍在 skillId=$skillId（下次启动会把它当半成品清一遍）")
        }
    }

    /**
     * 清理上次中断留下的半成品。
     *
     * @param deleter 删除动作，**默认就是 `deleteRecursively`**；留出参数是为了能让"删不掉"
     *                这条分支被测试覆盖 —— 在 Windows/Linux 上稳定地造一次目录删除失败
     *                并不现实（只读位、文件占用都不可靠），而这条分支恰恰是最要命的。
     * @return 被清理的 skillId 列表（供日志与界面提示）
     */
    fun recover(deleter: (File) -> Boolean = { !it.exists() || it.deleteRecursively() }): List<String> {
        if (!root.isDirectory) return emptyList()
        val markers = root.listFiles { file -> file.isFile && file.name.startsWith(PREFIX) && file.name.endsWith(SUFFIX) }
            ?: return emptyList()
        val recovered = mutableListOf<String>()
        markers.forEach { marker ->
            val skillId = marker.name.removePrefix(PREFIX).removeSuffix(SUFFIX)
            val targetPath = runCatching { marker.readLines().firstOrNull() }.getOrNull()
            var cleaned = true
            if (!targetPath.isNullOrBlank()) {
                val target = File(targetPath)
                if (isInsideRoot(target)) {
                    cleaned = runCatching { deleter(target) }
                        .onFailure { e ->
                            Logger.w(TAG, "清理半成品技能目录异常 path=${target.path}: ${e.message ?: e.javaClass.simpleName}", e)
                        }
                        .getOrDefault(false)
                    if (!cleaned) {
                        // 关键：没删掉就**保留标记**，下次启动再试。
                        // 旧写法是照删标记、照把 id 记进"已清理"—— 于是半截目录永远不会再被处理，
                        // 而界面已经告诉用户"清理过残留"，下次装同名包仍判"已存在"。
                        Logger.w(TAG, "半成品未清掉，保留标记下次重试 skillId=$skillId path=$targetPath")
                        return@forEach
                    }
                }
            }
            runCatching { marker.delete() }
                .onFailure { e -> Logger.w(TAG, "journal 标记删除失败 name=${marker.name}: ${e.message ?: e.javaClass.simpleName}", e) }
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
