package io.zer0.muse.data.assistant

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.muse.util.ShareIntentHelper
import io.zer0.muse.util.readZipEntryWithLimit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.uuid.Uuid

/**
 * 角色卡导入/导出/分享。
 *
 * 把 [AssistantEntity](及可选头像图片)打包成 zip(.muse-assistant 文件),
 * 便于跨设备/跨安装分享角色配置。仅用 java.util.zip,不引入新依赖。
 *
 * zip 结构:
 *  - assistant.json      序列化的 AssistantEntity(导出时 id 清空,导入时重新生成)
 *  - avatar.<ext>        可选,头像图片字节(从头像路径推断扩展名)
 *
 * v2.x: 新增一键分享([share],系统分享面板直发角色包)与导入预览
 * ([parse] 解析 → 用户确认 → [commitParsed] 写入)。
 */
object AssistantCardExporter {

    private const val TAG = "AssistantCardExporter"
    private const val ENTRY_JSON = "assistant.json"

    /**
     * E3:人设卡条目。导出只写这一份(严格 schema、不含凭据与本地绑定);
     * `assistant.json` 保留为**只读**的历史条目。
     */
    private const val ENTRY_PERSONA = "persona.json"
    private const val ENTRY_AVATAR_PREFIX = "avatar."

    /** v2.x: 角色包 MIME — 与 AndroidManifest 的 VIEW intent-filter 对齐(分享/接收唯一标识)。 */
    const val MIME_TYPE = "application/x-muse-assistant"

    /** v2.x: 角色包文件扩展名。 */
    const val FILE_EXTENSION = "muse-assistant"

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
     * v2.x: 解析出的角色卡内容(尚未写入磁盘/数据库)。
     *
     * 供导入预览确认使用:用户确认后由 [commitParsed] 落盘入库。
     */
    class ParsedCard(
        /** 已经是「可入库」的形状:凭据、模型绑定、使用统计都是空的,不是原样搬过来的实体。 */
        val entity: AssistantEntity,
        val avatarBytes: ByteArray?,
        val avatarExt: String?,
        /** 解析出来的人设(差分预览要用)。 */
        val persona: PersonaCard,
        /** true = 旧版整份实体包;导入时只取人设,并要在预览里说清楚。 */
        val legacy: Boolean,
    )

    // ── 打包(导出与一键分享共用) ─────────────────────────────────────────

    /** 把 [assistant] 打包为角色包 zip 写入 [os](导出副本:id 清空;头像字节随包)。 */
    private fun writeZip(os: OutputStream, assistant: AssistantEntity) {
        // E3:导出的是人设卡,不是实体副本。id / providerId / modelId / toolModelId /
        // customHeadersJson(常见放法是 Authorization)/ 使用统计都不再进入包 ——
        // 角色卡会被转发到群聊,以前"敏感字段不清除"的注释等于在教人泄漏。
        val json = PersonaCardCodec.encode(PersonaCardCodec.fromEntity(assistant))

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

        ZipOutputStream(os).use { zos ->
            zos.putNextEntry(ZipEntry(ENTRY_PERSONA))
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
    }

    /** v2.x: 角色包文件名安全化(去除路径分隔/保留字符/空白)。 */
    private fun safePackName(name: String): String = name.ifBlank { "assistant" }
        .replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_")
        .take(60)

    /**
     * 导出角色卡到 [outputUri](SAF 保存)。
     *
     * - id 清空为 ""(导入时重新生成),其余字段原样保留(本地卡片,敏感字段不清除)
     * - 若 [AssistantEntity.avatarImageUrl] 指向存在的本地文件,把头像字节一并打包
     */
    suspend fun export(context: Context, assistant: AssistantEntity, outputUri: Uri) = withContext(Dispatchers.IO) {
        context.contentResolver.openOutputStream(outputUri)?.use { os ->
            writeZip(os, assistant)
        } ?: Logger.w(TAG, "openOutputStream failed for $outputUri")
    }

    /**
     * v2.x: 一键分享 — 打包角色包到 cacheDir 并经系统分享面板发送(ACTION_SEND)。
     *
     * 接收方(装了 Muse 的设备)点开文件可按 MIME 直接进入导入;其他渠道作为普通文件传递。
     *
     * @return 是否成功发起分享(打包/拉起失败返回 false)
     */
    suspend fun share(context: Context, assistant: AssistantEntity): Boolean = withContext(Dispatchers.IO) {
        try {
            val dir = File(context.cacheDir, "assistant_share").apply { mkdirs() }
            val file = File(dir, "${safePackName(assistant.name)}.$FILE_EXTENSION")
            file.outputStream().use { os -> writeZip(os, assistant) }
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file,
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = MIME_TYPE
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            withContext(Dispatchers.Main) {
                ShareIntentHelper.startChooserSafely(context, intent, "Share Character Pack")
            }
            true
        } catch (e: Exception) {
            Logger.w(TAG, "share failed: ${e.message}")
            false
        }
    }

    // ── 解析 / 提交(导入预览确认) ───────────────────────────────────────

    /**
     * v2.x: 解析角色卡但不写入 — 供导入预览确认。
     *
     * 读取 zip 中的 assistant.json 与头像字节;不写磁盘、不入库。
     *
     * @return 解析结果;格式无效或解析失败返回 null
     */
    suspend fun parse(context: Context, inputUri: Uri): ParsedCard? = withContext(Dispatchers.IO) {
        var personaStr: String? = null
        var legacyStr: String? = null
        var avatarExt: String? = null
        var avatarBytes: ByteArray? = null

        context.contentResolver.openInputStream(inputUri)?.use { input ->
            ZipInputStream(input).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val name = entry.name
                    when {
                        name == ENTRY_PERSONA -> {
                            personaStr = readZipEntryWithLimit(zis, MAX_ENTRY_BYTES, name).toString(Charsets.UTF_8)
                        }
                        // 历史包:仍然能导入,但只取人设字段
                        name == ENTRY_JSON -> {
                            legacyStr = readZipEntryWithLimit(zis, MAX_ENTRY_BYTES, name).toString(Charsets.UTF_8)
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

        // 新卡优先:严格 schema 解析,失败就拒绝,不退回"尽力解释"。
        personaStr?.let { text ->
            return@withContext when (val outcome = PersonaCardCodec.decode(text)) {
                is PersonaParse.Ok -> ParsedCard(
                    entity = PersonaCardCodec.toNewEntity(outcome.card),
                    avatarBytes = avatarBytes,
                    avatarExt = avatarExt,
                    persona = outcome.card,
                    legacy = false,
                )

                is PersonaParse.Invalid -> {
                    Logger.w(TAG, "persona.json 被拒绝 ${outcome.reason} (field=${outcome.field})")
                    null
                }
            }
        }

        val json = legacyStr ?: run {
            Logger.w(TAG, "neither persona.json nor assistant.json in $inputUri")
            return@withContext null
        }

        val legacyEntity = runCatching {
            AppJson.decodeFromString(AssistantEntity.serializer(), json)
        }.getOrElse {
            Logger.w(TAG, "parse assistant.json failed: ${it.message}")
            return@withContext null
        }
        // 旧包同样只取人设:凭据与本地绑定不会被一次导入带进来。
        val card = PersonaCardCodec.fromEntity(legacyEntity)
        ParsedCard(
            entity = PersonaCardCodec.toNewEntity(card),
            avatarBytes = avatarBytes,
            avatarExt = avatarExt,
            persona = card,
            legacy = true,
        )
    }

    /**
     * v2.x: 把已解析的角色卡写入磁盘与数据库(预览确认后调用)。
     *
     * - 重新生成 id([Uuid.random]),设 createdAt/updatedAt 为当前时间
     * - 头像字节写回 filesDir/avatars/ 并更新 avatarImageUrl
     * - 用 [repo].upsert 存入数据库
     */
    suspend fun commitParsed(context: Context, repo: AssistantRepository, card: ParsedCard): AssistantEntity = withContext(Dispatchers.IO) {
        val newId = Uuid.random().toString()
        val now = System.currentTimeMillis()

        // 头像写回内部存储(若有)
        val newAvatarUrl = run {
            val bytes = card.avatarBytes ?: return@run null
            val ext = card.avatarExt?.takeIf { it.isNotBlank() } ?: "jpg"
            val dir = File(context.filesDir, "avatars").apply { mkdirs() }
            val target = File(dir, "assistant_${newId}_$now.$ext")
            target.writeBytes(bytes)
            target.absolutePath
        }

        val entity = card.entity.copy(
            id = newId,
            createdAt = now,
            updatedAt = now,
            avatarImageUrl = newAvatarUrl ?: card.entity.avatarImageUrl,
        )
        repo.upsert(entity)
        entity
    }

    /**
     * 从 [inputUri] 导入角色卡(解析 + 提交,一步到位)。
     *
     * 保留给不经过预览确认的调用方;带预览的导入请用 [parse] + [commitParsed]。
     *
     * @return 导入后的 [AssistantEntity];若文件格式无效或解析失败返回 null
     */
    suspend fun import(context: Context, repo: AssistantRepository, inputUri: Uri): AssistantEntity? {
        val card = parse(context, inputUri) ?: return null
        return commitParsed(context, repo, card)
    }
}
