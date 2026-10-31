package io.zer0.muse.ui.speech

import android.media.MediaMetadataRetriever
import io.zer0.common.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * 音频时长探测器。
 *
 * 单独抽成一个接口是为了让存储层能在纯 JVM 单测里跑：真机/模拟器上用
 * [MediaMetadataDurationProbe]，测试里换成固定值。
 */
fun interface VoiceDurationProbe {
    /** @return 时长（毫秒）；无法判定时返回 0 */
    fun probeMs(file: File): Long
}

/** 用 Android 原生 [MediaMetadataRetriever] 读音频时长（不新增依赖）。 */
object MediaMetadataDurationProbe : VoiceDurationProbe {
    private const val TAG = "MediaMetadataDurationProbe"

    override fun probeMs(file: File): Long {
        if (!file.isFile || file.length() <= 0L) return 0L
        val retriever = MediaMetadataRetriever()
        val duration = runCatching {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?.coerceAtLeast(0L)
                ?: 0L
        }.getOrElse { e ->
            // 探测失败不影响消息可用性：时长只是 UI 显示用的元数据
            Logger.d(TAG, "音频时长探测失败: ${e.message}")
            0L
        }
        runCatching { retriever.release() }
        return duration
    }
}

/**
 * 语音存档端口。
 *
 * 工具层（`send_voice`）只用到「落盘 + 探测时长」两件事，抽成接口后单测不必落真实文件。
 */
interface VoiceMessageArchiver {
    /** 见 [VoiceMessageStore.persist]。 */
    suspend fun persist(tempFile: File, key: String): String?

    /** 见 [VoiceMessageStore.probeDurationMs]。 */
    fun probeDurationMs(file: File): Long

    /** 见 [VoiceMessageStore.delete]。 */
    suspend fun delete(uri: String)
}

/**
 * 语音消息音频存储。
 *
 * TTS 合成的音频原本落在 `cacheDir`（系统随时可能清理，播完即删），语音消息需要
 * 长期可回放的引用，所以由本类把成品搬进 `filesDir/voice_messages/`，数据库只存
 * 引用字符串（`file://` + 绝对路径），与图片落盘的做法一致。
 *
 * 文件命名取 [persist] 的 `key` 的内容哈希（默认前缀 16 位十六进制），因此：
 *  - 同一段文字重复合成时命中同名文件，直接覆盖，不会堆出多份等价音频；
 *  - 文件名不含用户文本，避免磁盘上出现可读的聊天内容。
 *
 * 所有磁盘操作都在 [Dispatchers.IO] 上执行，方法本身可安全地在协程里直接调用。
 *
 * @param storageDir 持久化目录（约定为 `filesDir/voice_messages/`）
 * @param durationProbe 时长探测器；单测可替换
 */
class VoiceMessageStore(
    internal val storageDir: File,
    private val durationProbe: VoiceDurationProbe = MediaMetadataDurationProbe,
) : VoiceMessageArchiver {

    /**
     * 把合成得到的临时音频搬进持久目录。
     *
     * @param tempFile TTS 写出的临时文件（调用方负责它原本的生命周期）
     * @param key 内容键（消息 id 或文本哈希），决定存档文件名
     * @return 存档引用（`file://` + 绝对路径）；源文件缺失/为空或落盘失败时返回 null
     */
    override suspend fun persist(tempFile: File, key: String): String? = withContext(Dispatchers.IO) {
        if (!tempFile.isFile || tempFile.length() <= 0L) {
            Logger.w(TAG, "语音落盘跳过：源文件不存在或为空 ${tempFile.name}")
            return@withContext null
        }
        val dir = ensureStorageDir() ?: return@withContext null
        val target = File(dir, fileNameFor(key, tempFile.extension))
        if (sameFile(tempFile, target)) return@withContext FILE_PREFIX + target.absolutePath
        val staging = File(dir, target.name + TMP_SUFFIX)
        runCatching {
            tempFile.copyTo(staging, overwrite = true)
            if (target.exists() && !target.delete()) error("旧存档删除失败: ${target.name}")
            if (!staging.renameTo(target)) error("改名失败: ${staging.name}")
        }.onFailure { e ->
            Logger.w(TAG, "语音落盘失败: ${e.message}")
            runCatching { staging.delete() }
        }
        if (!target.isFile) return@withContext null
        FILE_PREFIX + target.absolutePath
    }

    /**
     * 把引用还原成本地文件。
     *
     * 只接受存档目录内的文件：历史数据里出现越界路径时按"不可解析"处理，
     * 不会把任意本地路径当语音播出去。
     *
     * @return 文件不存在/引用非法时返回 null
     */
    fun resolve(uri: String): File? {
        val raw = uri.trim().removePrefix(FILE_PREFIX).trim()
        if (raw.isEmpty()) return null
        val root = runCatching { storageDir.canonicalFile }.getOrNull()
        val candidate = if (File(raw).isAbsolute) File(raw) else File(storageDir, raw)
        val canonical = runCatching { candidate.canonicalFile }.getOrNull()
        if (root == null || canonical == null || canonical.parentFile != root) return null
        return canonical.takeIf { it.isFile }
    }

    /** 删除单条语音存档；引用非法或文件已不存在时静默忽略。 */
    override suspend fun delete(uri: String) {
        withContext(Dispatchers.IO) {
            val file = resolve(uri) ?: return@withContext
            if (!file.delete()) Logger.w(TAG, "语音存档删除失败: ${file.name}")
        }
    }

    /**
     * 清理没有任何消息引用的存档（孤儿文件）。
     *
     * 引用集合由调用方从数据库取（`MessageDao.getReferencedVoiceUris()`），
     * 这样存储层不依赖数据库，也能在单测里直接验证。
     * 落盘中断留下的 `.tmp` 暂存文件一并清掉。
     *
     * @param referencedUris 数据库里仍被引用的语音引用
     * @return 删掉的文件数
     */
    suspend fun cleanupOrphans(referencedUris: Collection<String>): Int = withContext(Dispatchers.IO) {
        val files = storageDir.listFiles() ?: return@withContext 0
        val keep = referencedUris.mapNotNull { canonicalPathOf(it) }.toSet()
        files.count { file -> file.isFile && shouldDelete(file, keep) }
    }

    /** 按引用探测时长；引用不可解析时返回 0。 */
    suspend fun durationMs(uri: String): Long = withContext(Dispatchers.IO) {
        val file = resolve(uri) ?: return@withContext 0L
        probeDurationMs(file)
    }

    /** 按文件探测时长；失败返回 0（不会抛异常）。 */
    override fun probeDurationMs(file: File): Long = runCatching { durationProbe.probeMs(file) }
        .getOrDefault(0L)
        .coerceAtLeast(0L)

    private fun shouldDelete(file: File, keep: Set<String>): Boolean {
        if (file.name.endsWith(TMP_SUFFIX)) return file.delete()
        val path = runCatching { file.canonicalPath }.getOrNull() ?: return false
        return path !in keep && file.delete()
    }

    private fun canonicalPathOf(uri: String): String? = resolve(uri)?.let {
        runCatching { it.canonicalPath }.getOrNull()
    }

    private fun ensureStorageDir(): File? {
        if (!storageDir.exists() && !storageDir.mkdirs()) {
            Logger.w(TAG, "语音存档目录创建失败: ${storageDir.absolutePath}")
            return null
        }
        return storageDir.takeIf { it.isDirectory }
    }

    private fun sameFile(a: File, b: File): Boolean =
        runCatching { a.canonicalPath == b.canonicalPath }.getOrDefault(false)

    /** 存档文件名 = key 的内容哈希 + 源文件扩展名（默认 wav）。 */
    private fun fileNameFor(key: String, extension: String): String {
        val ext = extension.lowercase().filter { it.isLetterOrDigit() }.ifEmpty { DEFAULT_EXTENSION }
        return keyHash(key) + "." + ext
    }

    private fun keyHash(key: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(HASH_CHARS)
    }

    private companion object {
        private const val TAG = "VoiceMessageStore"

        /** 引用前缀，与图片落盘（MessageImageStore）保持同一种写法。 */
        private const val FILE_PREFIX = "file://"

        /** 落盘中转后缀，最终会被改名成正式文件。 */
        private const val TMP_SUFFIX = ".tmp"

        /** 无扩展名时的兜底（系统 TTS 写 wav，云端多数返回 mp3）。 */
        private const val DEFAULT_EXTENSION = "wav"

        /** 哈希取前 16 位十六进制（64 bit），足够避免同一目录内碰撞。 */
        private const val HASH_CHARS = 16
    }
}
