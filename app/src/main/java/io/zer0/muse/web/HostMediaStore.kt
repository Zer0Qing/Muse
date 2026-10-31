package io.zer0.muse.web

import io.zer0.common.Logger
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import java.util.LinkedHashMap
import java.util.Locale
import java.util.UUID

/**
 * 媒体文件访问账本：不透明 token ↔ 本机媒体文件路径。
 *
 * 浏览器只拿到 `/api/media/<token>`，拿不到绝对路径。把路径本身当查询参数会直接把
 * 目录穿越交给请求方（`?path=../../shared_prefs/x.xml`）；随机 token 猜不到、不含路径信息，
 * 且只在服务端登记的范围内有效。
 *
 * **只认三个目录**：[imageDir]（消息图片落盘目录）、[voiceDir]（语音存档目录）、
 * [avatarDir]（助手头像目录）。
 * 登记与读取各校验一次「规范路径落在允许目录内」：登记侧决定哪些引用能被换成链接，
 * 读取侧是最后一道闸 —— 目录可能被改名、替换或链接到别处，登记时的判断不代表此刻的判断。
 *
 * **LRU 上限**：默认 [DEFAULT_MAX_ENTRIES] 条。账本每条只存 token 与路径字符串（约 200 字节），
 * 2048 条约 400KB；再放大不会更安全（浏览器同一时刻只引用最近一屏的媒体），
 * 却让早就翻过去的消息一直占位。超限淘汰最久未使用的一条，它的 token 随即 404。
 *
 * **同一文件复用同一 token**：以规范路径为主键。重复下发不会产生新 token，
 * 浏览器因此能命中自己的 HTTP 缓存，滚动消息列表时不会反复回源。
 *
 * **裸 base64**：图片以 base64 形式留在消息里时（未落盘的短数据、`data:` URI），
 * 浏览器同样取不到。这里按内容哈希落盘到 [imageDir] 再登记，同名文件已存在就不重复写，
 * 所以同一张图只付一次写盘成本。内容识别不出媒体格式的一律拒绝，避免把任意文本写成媒体文件。
 */
internal class HostMediaStore(
    private val imageDir: File,
    private val voiceDir: File,
    /** 助手头像目录（`filesDir/avatar`）；null 表示不对外提供头像。 */
    private val avatarDir: File? = null,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
) {

    private val lock = Any()

    /** 规范路径 → token；accessOrder=true 即 LRU，读取也会续期。 */
    private val pathToToken = LinkedHashMap<String, String>(64, 0.75f, true)

    /** token → 规范路径，供路由反查。与 [pathToToken] 同步淘汰。 */
    private val tokenToPath = HashMap<String, String>()

    /** 允许的目录锚点（规范路径），构造后固定。 */
    private val anchors: List<File> = listOfNotNull(imageDir, voiceDir, avatarDir).mapNotNull { dir ->
        runCatching { dir.canonicalFile }.getOrNull()
    }

    /**
     * 把一条 `file://` 引用或绝对路径换成 token。
     *
     * @return 路径越界、文件不存在或不是普通文件时返回 null（调用方据此剔除该引用）
     */
    fun tokenForFile(reference: String): String? {
        val raw = reference.trim().removePrefix(FILE_SCHEME).trim()
        if (raw.isEmpty()) return null
        val candidate = anchored(raw) ?: return null
        return register(candidate)
    }

    /**
     * 把内联 base64 图片落盘并登记。
     *
     * 文件名取 `{ownerMessageId}_inline_{contentHash}.{ext}`：前缀与 [MessageImageStore] 的
     * 落盘文件同一个 `{messageId}_` 形状，删消息时 [MessageImageStore.deleteByMessageId]
     * 的现有前缀扫描会把它们一并清掉，不留孤儿。代价是同一张图出现在两条消息里会存两份，
     * 但换来的是删除路径不需要跨层记账。
     *
     * @param declaredMime `data:` URI 里声明的类型，null 表示裸 base64（只能靠文件头判定）
     * @param ownerMessageId 归属消息 id，决定清理时的前缀
     * @return 无法解码、识别不出媒体格式或写盘失败时返回 null
     */
    fun tokenForInlineData(base64: String, declaredMime: String?, ownerMessageId: String): String? {
        val bytes = decodeInline(base64) ?: return null
        val mime = HostMediaContentType.detect(bytes) ?: normalizedMime(declaredMime)
        val extension = mime?.let(HostMediaContentType::extensionFor) ?: return null
        val file = File(imageDir, "${safeOwner(ownerMessageId)}_${INLINE_PREFIX}${contentHash(bytes)}.$extension")
        if (!file.isFile && !writeInline(file, bytes)) return null
        return anchored(file.absolutePath)?.let(::register)
    }

    /** 归属 id 只留文件名安全字符（消息 id 是 UUID，正常不会被改动）。 */
    private fun safeOwner(ownerMessageId: String): String =
        ownerMessageId.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(MAX_OWNER_CHARS)
            .ifEmpty { SHARED_OWNER }

    /** 长度在区间内、可解码且非空的 base64 才值得落盘。 */
    private fun decodeInline(base64: String): ByteArray? {
        if (base64.length < MIN_INLINE_BASE64_LENGTH || base64.length > MAX_INLINE_BASE64_LENGTH) return null
        val bytes = runCatching { Base64.getMimeDecoder().decode(base64) }.getOrNull() ?: return null
        return bytes.takeIf { it.isNotEmpty() && it.size <= MAX_INLINE_BYTES }
    }

    private fun normalizedMime(declared: String?): String? =
        declared?.lowercase(Locale.ROOT)?.substringBefore(';')

    /**
     * 路由侧反查：token → 此刻仍存在于允许目录内的文件。
     *
     * 未知 token、文件已被清理、规范路径已不在允许目录内，都返回 null（对外统一 404，
     * 不区分「不存在」与「越界」，免得给探测者反馈）。
     */
    fun resolve(token: String): File? {
        val path = synchronized(lock) { tokenToPath[token] } ?: return null
        // 反查仍走 anchored():此刻的目录校验与存在性校验都在里面，登记时的结论不被信任。
        val candidate = anchored(path) ?: return null
        return candidate.also { synchronized(lock) { pathToToken[candidate.path] } }
    }

    /** 判定文件是否落在允许目录内（含子目录），按规范路径比较。 */
    private fun isWithinAllowedDirs(canonicalFile: File): Boolean = anchors.any { anchor ->
        val base = anchor.path
        canonicalFile.path == base || canonicalFile.path.startsWith(base + File.separator)
    }

    /**
     * 单测预置映射：直接把 [path] 挂到 [token] 上，不做目录与存在性校验。
     *
     * 存在的意义是让「映射已建立、但此刻路径越界／文件已消失」这类读取侧分支可被验证 ——
     * 走公开接口构造不出这种映射（登记侧就会拒掉），而读取侧的校验正是为这种状态兜底。
     */
    internal fun seedMapping(token: String, path: String) {
        synchronized(lock) {
            tokenToPath[token] = path
            pathToToken[path] = token
        }
    }

    private fun register(canonical: File): String? {
        if (!isWithinAllowedDirs(canonical)) return null
        return synchronized(lock) {
            val existing = pathToToken[canonical.path]
            if (existing != null) {
                tokenToPath[existing] = canonical.path
                existing
            } else {
                val token = newToken()
                pathToToken[canonical.path] = token
                tokenToPath[token] = canonical.path
                evictOverflow()
                token
            }
        }
    }

    /** 淘汰 LRU 超出 [maxEntries] 的条目，同时摘掉它的 token。 */
    private fun evictOverflow() {
        while (pathToToken.size > maxEntries) {
            val eldest = pathToToken.entries.firstOrNull() ?: return
            pathToToken.remove(eldest.key)
            tokenToPath.remove(eldest.value)
        }
    }

    /** 规范路径 + 普通文件 + 目录校验，任一不过返回 null。 */
    private fun anchored(path: String): File? {
        val candidate = runCatching { File(path).canonicalFile }.getOrNull() ?: return null
        if (!candidate.isFile) return null
        if (!isWithinAllowedDirs(candidate)) return null
        return candidate
    }

    private fun writeInline(file: File, bytes: ByteArray): Boolean = runCatching {
        if (!imageDir.isDirectory && !imageDir.mkdirs()) error("图片目录创建失败")
        file.writeBytes(bytes)
        true
    }.getOrElse { error ->
        Logger.w(TAG, "内联媒体落盘失败: ${error.message}")
        false
    }

    private fun newToken(): String = UUID.randomUUID().toString().replace("-", "")

    private fun contentHash(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
            .take(HASH_CHARS)

    internal companion object {
        private const val TAG = "HostMediaStore"

        /** LRU 条目上限。见类注释「LRU 上限」。 */
        const val DEFAULT_MAX_ENTRIES = 2048

        private const val FILE_SCHEME = "file://"

        /** 内联媒体文件名中段，区别于 `{messageId}_{idx}_{hash}.bin` 的普通落盘图。 */
        private const val INLINE_PREFIX = "inline_"

        /** 归属 id 缺省时的前缀（仍然落在图片目录内，不会被任何消息删不到）。 */
        private const val SHARED_OWNER = "inline"

        /** 归属 id 在文件名里保留的最大字符数。 */
        private const val MAX_OWNER_CHARS = 64

        /** 短于此长度的 base64 不可能是图片，也不值得落盘。 */
        private const val MIN_INLINE_BASE64_LENGTH = 32

        /** 上限约 12MB base64（对应 9MB 二进制），超出视为异常数据。 */
        private const val MAX_INLINE_BASE64_LENGTH = 12 * 1024 * 1024

        /** 解码后字节上限（16MB），与上一条互相兜底，防止超长输入拖住解码。 */
        private const val MAX_INLINE_BYTES = 16L * 1024 * 1024

        /** 内联文件名的内容哈希长度（64 bit），同图必同名，避免重复落盘。 */
        private const val HASH_CHARS = 16
    }
}
