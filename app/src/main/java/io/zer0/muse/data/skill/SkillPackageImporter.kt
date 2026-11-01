package io.zer0.muse.data.skill

import android.content.Context
import android.net.Uri
import io.zer0.muse.tools.SkillImporter
import java.io.File
import java.io.FileOutputStream

/**
 * 技能包导入的**唯一入口**：把"一个文件/目录 → 一条可运行的技能记录"这件事收在一处。
 *
 * 为什么单独一个类：解析在 [SkillPackageStore]、落库在 [SkillRepository]、编码在 [SkillImporter]，
 * 三者的先后顺序与失败回滚才是用户会感知的部分 —— 放在 UI 里就变成"每个入口各写一遍"，
 * 而各写一遍的那几遍里，一定会有一遍忘了回滚。
 *
 * 三条不成文但重要的规矩：
 *  1. **落库失败要删掉已解包的目录**，否则磁盘上会留一份没有主人、界面也看不到的技能；
 *  2. **不覆盖同名包**（[SkillPackageStore] 已经拒了），宁可让用户先删；
 *  3. 指令正文**不过注入关键词黑名单** —— 这与既有提示词技能是同一口径（产品取舍见
 *     [SkillImporter] 的 safetyReview 注释）；长度上限仍然生效。
 */
class SkillPackageImporter(
    context: Context,
    private val repository: SkillRepository,
) {

    private val appContext = context.applicationContext
    private val store = SkillPackageStore(appContext)

    /** 导入结果。[Failed.reason] 必须能直接显示给用户。 */
    sealed interface Outcome {
        data class Installed(val skill: SkillEntity, val packageDir: File) : Outcome
        data class Failed(val reason: String) : Outcome
    }

    /** 从 SAF 等外部 Uri 导入：先整体落到缓存再解包，避免半读状态。 */
    suspend fun importUri(uri: Uri): Outcome {
        val cache = uriToCacheFile(uri)
            ?: return Outcome.Failed(
                "无法导入：文件读不出来，或超过 ${MAX_IMPORT_BYTES / (1024 * 1024)}MB 上限" +
                    "（技能包应只含清单与少量参考文件）",
            )
        return try {
            importFile(cache)
        } finally {
            cache.delete()
            cache.parentFile?.delete()
        }
    }

    /**
     * 导入"单个 `SKILL.md` 文件"这种最常见的情形：
     * 用户从聊天里收到一份 md，直接选它就行，不必自己压成 ZIP。
     */
    suspend fun importManifestText(text: String): Outcome {
        val staging = File(appContext.cacheDir, "skill-md-" + System.currentTimeMillis())
        return try {
            staging.mkdirs()
            File(staging, SkillPackageStore.SKILL_FILE_NAME).writeText(text)
            importFile(staging)
        } finally {
            staging.deleteRecursively()
        }
    }

    /** 从目录或 `.zip` 导入。 */
    suspend fun importFile(source: File): Outcome {
        val imported = store.import(source)
        val packageDir = imported.imported?.dir
        if (packageDir == null) {
            return Outcome.Failed(imported.reason ?: "技能包导入失败")
        }
        val definition = requireNotNull(imported.imported).definition
        val skillId = requireNotNull(imported.imported).skillId
        if (skillId in SkillImporter.RESERVED_IDS) {
            store.delete(skillId)
            return Outcome.Failed("技能名与内置工具重名（id=$skillId），请改 SKILL.md 里的 name 后重试")
        }
        val skill = entityFor(skillId, definition)
        return try {
            repository.upsert(skill)
            Outcome.Installed(skill, packageDir)
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            // 取消也要回收：不然会留下一份界面看不见、也没人引用它的包目录
            store.delete(skillId)
            throw cancellation
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            // 落库失败必须把已解包的目录收回去：留下它就是一份界面看不见的孤儿数据
            store.delete(skillId)
            Outcome.Failed("保存技能失败：${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** 技能被删除时一并回收它的包目录（非技能包是 no-op）。 */
    suspend fun deletePackageFor(skill: SkillEntity) {
        if (SkillImporter.promptFilePath(skill.implementationKotlin) == null) return
        store.delete(skill.id)
    }

    private fun entityFor(skillId: String, definition: SkillPackage.Parsed): SkillEntity = SkillEntity(
        id = skillId,
        name = definition.name,
        description = definition.description,
        parametersJson = SkillImporter.EMPTY_PARAMETERS_JSON,
        implementationKotlin = SkillImporter.encodePromptFile(store.relativeManifestPath(skillId)),
        category = CATEGORY,
    )

    /**
     * 把外部 Uri 落到缓存目录。
     *
     * 限额必须**边读边算**：先整份复制完再检查长度，等于让一个 10GB 的文件先把磁盘写满
     * 才想起说"太大了" —— 那不是拒绝，是自伤。
     */
    private fun uriToCacheFile(uri: Uri): File? {
        val staging = File(appContext.cacheDir, "skill-import-" + System.currentTimeMillis())
        val target = File(staging, displayName(uri))
        val copied = runCatching {
            staging.mkdirs()
            appContext.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(target).use { output -> copyWithLimit(input, output) }
            }
        }.getOrElse {
            target.delete(); staging.delete()
            null
        }
        if (copied == null || copied > MAX_IMPORT_BYTES) {
            target.delete(); staging.delete()
            return null
        }
        return target
    }

    private fun copyWithLimit(input: java.io.InputStream, output: java.io.OutputStream): Long {
        val buffer = ByteArray(CHUNK_BYTES)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            total += read
            if (total > MAX_IMPORT_BYTES) return total
            output.write(buffer, 0, read)
        }
        return total
    }

    private fun displayName(uri: Uri): String {
        val fromUri = uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() && !it.contains('/') }
        return fromUri ?: "skill-package"
    }

    private companion object {
        const val CATEGORY = "custom"
        /** 与 [SkillPackageStore] 的常量不同名，避免这里再复制一份路径拼接逻辑。 */
        const val MAX_IMPORT_BYTES = 32L * 1024 * 1024
        const val CHUNK_BYTES = 64 * 1024
    }
}
