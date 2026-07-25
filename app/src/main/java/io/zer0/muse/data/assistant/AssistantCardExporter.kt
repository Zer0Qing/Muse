package io.zer0.muse.data.assistant

import android.content.Context
import android.net.Uri
import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.muse.util.readZipEntryWithLimit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.uuid.Uuid

/**
 * 角色卡导入/导出。
 *
 * 把 [AssistantEntity](及可选头像图片)打包成 zip(.muse-assistant 文件),
 * 便于跨设备/跨安装分享角色配置。仅用 java.util.zip,不引入新依赖。
 *
 * zip 结构:
 *  - assistant.json      序列化的 AssistantEntity(导出时 id 清空,导入时重新生成)
 *  - avatar.<ext>        可选,头像图片字节(从头像路径推断扩展名)
 */
object AssistantCardExporter {

    private const val TAG = "AssistantCardExporter"
    private const val ENTRY_JSON = "assistant.json"
    private const val ENTRY_AVATAR_PREFIX = "avatar."

    // M-EXP1: 头像扩展名白名单(只允许常见图片格式),防止恶意卡片写入 .exe 等可执行扩展名
    private val ALLOWED_AVATAR_EXTS = setOf("jpg", "jpeg", "png", "webp")

    // v1.113: 限制单条目最大 10MB,防 ZIP 炸弹
    private const val MAX_ENTRY_BYTES = 10L * 1024 * 1024

    /** M-EXP1: 校验扩展名是否在白名单内,不在则回退 "jpg"。 */
    private fun sanitizeAvatarExt(raw: String): String {
        val lower = raw.lowercase().trim()
        return if (lower in ALLOWED_AVATAR_EXTS) lower else "jpg"
    }

    /**
     * 导出角色卡到 [outputUri]。
     *
     * - id 清空为 ""(导入时重新生成),其余字段原样保留(本地卡片,敏感字段不清除)
     * - 若 [AssistantEntity.avatarImageUrl] 指向存在的本地文件,把头像字节一并打包
     */
    suspend fun export(context: Context, assistant: AssistantEntity, outputUri: Uri) =
        withContext(Dispatchers.IO) {
            // 导出副本:清除 id
            val exportEntity = assistant.copy(id = "")
            val json = AppJson.encodeToString(AssistantEntity.serializer(), exportEntity)

            // 头像字节(可选):仅当 avatarImageUrl 指向存在的本地文件时打包
            var avatarExt: String? = null
            var avatarBytes: ByteArray? = null
            if (assistant.avatarImageUrl.isNotBlank()) {
                val avatarFile = File(assistant.avatarImageUrl)
                if (avatarFile.exists()) {
                    // M-EXP1: 扩展名经白名单清洗,防御本地数据被篡改后写出可疑扩展名
                    avatarExt = sanitizeAvatarExt(avatarFile.extension.ifBlank { "jpg" })
                    avatarBytes = avatarFile.readBytes()
                }
            }

            context.contentResolver.openOutputStream(outputUri)?.use { os ->
                ZipOutputStream(os).use { zos ->
                    zos.putNextEntry(ZipEntry(ENTRY_JSON))
                    zos.write(json.toByteArray(Charsets.UTF_8))
                    zos.closeEntry()
                    val bytes = avatarBytes
                    val ext = avatarExt
                    if (bytes != null && ext != null) {
                        zos.putNextEntry(ZipEntry("$ENTRY_AVATAR_PREFIX$ext"))
                        zos.write(bytes)
                        zos.closeEntry()
                    }
                }
            } ?: Logger.w(TAG, "openOutputStream failed for $outputUri")
        }

    /**
     * 从 [inputUri] 导入角色卡。
     *
     * - 解析 "assistant.json" 为 [AssistantEntity]
     * - 若有 avatar entry,写回 filesDir/avatars/ 并更新 avatarImageUrl
     * - 重新生成 id([Uuid.random]),设 createdAt/updatedAt 为当前时间
     * - 用 [repo].upsert 存入数据库
     *
     * @return 导入后的 [AssistantEntity];若文件格式无效或解析失败返回 null
     */
    suspend fun import(
        context: Context,
        repo: AssistantRepository,
        inputUri: Uri,
    ): AssistantEntity? = withContext(Dispatchers.IO) {
        var jsonStr: String? = null
        var avatarExt: String? = null
        var avatarBytes: ByteArray? = null

        context.contentResolver.openInputStream(inputUri)?.use { input ->
            ZipInputStream(input).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val name = entry.name
                    when {
                        name == ENTRY_JSON -> {
                            jsonStr = readZipEntryWithLimit(zis, MAX_ENTRY_BYTES, name).toString(Charsets.UTF_8)
                        }
                        name.startsWith(ENTRY_AVATAR_PREFIX) -> {
                            // M-EXP1: 恶意卡片可能塞 avatar.exe 等条目,扩展名经白名单清洗后再写盘
                            avatarExt = sanitizeAvatarExt(name.removePrefix(ENTRY_AVATAR_PREFIX))
                            avatarBytes = readZipEntryWithLimit(zis, MAX_ENTRY_BYTES, name)
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        } ?: return@withContext null

        val json = jsonStr ?: run {
            Logger.w(TAG, "no assistant.json in $inputUri")
            return@withContext null
        }

        val parsed = runCatching {
            AppJson.decodeFromString(AssistantEntity.serializer(), json)
        }.getOrElse {
            Logger.w(TAG, "parse assistant.json failed: ${it.message}")
            return@withContext null
        }

        val newId = Uuid.random().toString()
        val now = System.currentTimeMillis()

        // 头像写回内部存储(若有)
        val newAvatarUrl = run {
            val bytes = avatarBytes ?: return@run null
            val ext = avatarExt?.takeIf { it.isNotBlank() } ?: "jpg"
            val dir = File(context.filesDir, "avatars").apply { mkdirs() }
            val target = File(dir, "assistant_${newId}_$now.$ext")
            target.writeBytes(bytes)
            target.absolutePath
        }

        val entity = parsed.copy(
            id = newId,
            createdAt = now,
            updatedAt = now,
            avatarImageUrl = newAvatarUrl ?: parsed.avatarImageUrl,
        )
        repo.upsert(entity)
        entity
    }
}
