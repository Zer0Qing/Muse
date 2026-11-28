package io.zer0.muse.ui.speech

import android.media.MediaMetadataRetriever
import io.zer0.common.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

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

    /** 持久化语音条对应的文字稿，供「转文字」展示；默认 no-op 兼容无文字稿假实现。 */
    suspend fun saveTranscript(uri: String, text: String) {}
}

private fun resolveVoiceFile(key: String, storageDir: File): File? {
    val raw = key.removePrefix("file://").trim()
    if (raw.isEmpty()) return null
    val root = runCatching { storageDir.canonicalFile }.getOrNull()
    val candidate = if (File(raw).isAbsolute) File(raw) else File(storageDir, raw)
    val canonical = runCatching { candidate.canonicalFile }.getOrNull()
    return if (root != null && canonical != null && canonical.parentFile == root) {
        canonical.takeIf(File::isFile)
    } else {
        null
    }
}

/** 按音频文件魔数识别真实格式，避免云端 MP3 被误写为 .wav。 */
private fun detectAudioExtension(file: File): String? = runCatching {
    val header = ByteArray(12)
    val count = file.inputStream().use { it.read(header) }
    detectAudioExtension(header, count)
}.getOrNull()

private fun detectAudioExtension(header: ByteArray, count: Int): String? {
    if (count < 4) return null
    val b0 = header[0].toInt() and 0xFF
    val b1 = header[1].toInt() and 0xFF
    val b2 = header[2].toInt() and 0xFF
    val b3 = header[3].toInt() and 0xFF
    return when {
        b0 == 'I'.code && b1 == 'D'.code && b2 == '3'.code -> "mp3"
        b0 == 0xFF && (b1 and 0xE0) == 0xE0 -> "mp3"
        b0 == 'R'.code && b1 == 'I'.code && b2 == 'F'.code && b3 == 'F'.code -> "wav"
        b0 == 'O'.code && b1 == 'g'.code && b2 == 'g'.code && b3 == 'S'.code -> "ogg"
        b0 == 'f'.code && b1 == 'L'.code && b2 == 'a'.code && b3 == 'C'.code -> "flac"
        count >= 8 && header[4].toInt() == 'f'.code && header[5].toInt() == 't'.code &&
            header[6].toInt() == 'y'.code && header[7].toInt() == 'p'.code -> "m4a"
        else -> null
    }
}

private fun sameFile(a: File, b: File): Boolean = runCatching { a.canonicalPath == b.canonicalPath }.getOrDefault(false)

/** 存档文件名 = key 的内容哈希 + 源文件扩展名（默认 wav）。 */
private fun fileNameFor(key: String, extension: String): String {
    val ext = extension.lowercase().filter { it.isLetterOrDigit() }.ifEmpty { "wav" }
    return keyHash(key) + "." + ext
}

private fun keyHash(key: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it) }.take(16)
}

private fun shouldDeleteVoiceFile(file: File, keep: Set<String>, temporarySuffix: String): Boolean {
    if (file.name.endsWith(temporarySuffix)) return file.delete()
    val path = runCatching { file.canonicalPath }.getOrNull() ?: return false
    return path !in keep && file.delete()
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
    private val transcriptCache = ConcurrentHashMap<String, String>()
    private val resolvedFileCache = ConcurrentHashMap<String, File>()
    private val missingUriCache = ConcurrentHashMap.newKeySet<String>()
    private val transcriptDir: File by lazy { File(storageDir.parentFile, TRANSCRIPT_DIR_NAME) }

    override suspend fun persist(tempFile: File, key: String): String? = withContext(Dispatchers.IO) {
        if (!tempFile.isFile || tempFile.length() <= 0L) {
            Logger.w(TAG, "语音落盘跳过：源文件不存在或为空 ${tempFile.name}")
            return@withContext null
        }
        val dir = ensureStorageDir() ?: return@withContext null
        val detectedExt = detectAudioExtension(tempFile) ?: tempFile.extension
        val target = File(dir, fileNameFor(key, detectedExt))
        if (sameFile(tempFile, target)) return@withContext FILE_PREFIX + target.absolutePath
        val staging = File(dir, target.name + TMP_SUFFIX)
        runCatching {
            tempFile.copyTo(staging, overwrite = true)
            if (target.exists() && !target.delete()) error("voice_archive_delete_failed: ${target.name}")
            if (!staging.renameTo(target)) error("voice_archive_rename_failed: ${staging.name}")
        }.onFailure { e ->
            Logger.w(TAG, "语音落盘失败: ${e.message}")
            runCatching { staging.delete() }
        }
        if (!target.isFile) return@withContext null
        val uri = FILE_PREFIX + target.absolutePath
        missingUriCache.remove(uri)
        resolvedFileCache[uri] = target
        uri
    }

    /**
     * 把引用还原成本地文件。
     *
     * 只接受存档目录内的文件：历史数据里出现越界路径时按"不可解析"处理，
     * 不会把任意本地路径当语音播出去。
     *
     * @return 文件不存在/引用非法时返回 null
     */
    /** Returns the already-cached File for [uri], or null without blocking on disk. */
    fun getCachedResolved(uri: String): File? {
        val key = uri.trim()
        if (key.isEmpty() || key in missingUriCache) return null
        return resolvedFileCache[key]?.takeIf { it.isFile }
    }

    /** Returns true if [uri] resolution (hit or miss) is already cached in memory. */
    fun isResolveCached(uri: String): Boolean {
        val key = uri.trim()
        return key.isEmpty() || key in missingUriCache || resolvedFileCache.containsKey(key)
    }

    /** Resolves [uri] off the Main thread with in-memory caching. */
    suspend fun resolveAsync(uri: String): File? = withContext(Dispatchers.IO) { resolve(uri) }

    fun resolve(uri: String): File? {
        val key = uri.trim()
        val resolved = when {
            key.isEmpty() || key in missingUriCache -> null
            else -> resolvedFileCache[key]?.let { cached ->
                if (cached.isFile) cached else null.also { resolvedFileCache.remove(key) }
            } ?: resolveVoiceFile(key, storageDir)
        }
        if (resolved != null) {
            resolvedFileCache[key] = resolved
        } else if (key.isNotEmpty()) {
            missingUriCache.add(key)
        }
        return resolved
    }

    /** 删除单条语音存档及对应文字稿；引用非法或文件已不存在时静默忽略。 */
    override suspend fun delete(uri: String) {
        withContext(Dispatchers.IO) {
            val file = resolve(uri) ?: return@withContext
            val stem = file.nameWithoutExtension
            if (!file.delete()) Logger.w(TAG, "语音存档删除失败: ${file.name}")
            transcriptCache.remove(uri)
            resolvedFileCache.remove(uri.trim())
            missingUriCache.add(uri.trim())
            transcriptFileForStem(stem)?.let { tf ->
                if (tf.exists()) runCatching { tf.delete() }
            }
        }
    }

    /** 保存语音条对应的口语文字稿（存放在旁路 `voice_transcripts/` 目录，不污染音频存档目录）。 */
    override suspend fun saveTranscript(uri: String, text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        withContext(Dispatchers.IO) {
            val file = resolve(uri) ?: return@withContext
            val tDir = ensureTranscriptDir() ?: return@withContext
            val target = File(tDir, "${file.nameWithoutExtension}$TRANSCRIPT_EXT")
            val staging = File(tDir, target.name + TMP_SUFFIX)
            runCatching {
                staging.writeText(clean, Charsets.UTF_8)
                if (target.exists()) target.delete()
                if (!staging.renameTo(target)) staging.copyTo(target, overwrite = true)
            }.onSuccess {
                transcriptCache[uri] = clean
            }.onFailure { e ->
                Logger.w(TAG, "语音文字稿保存失败: ${e.message}")
                runCatching { staging.delete() }
            }
        }
    }

    /** Returns cached transcript text without disk I/O, or null on miss. */
    fun getCachedTranscript(uri: String): String? = transcriptCache[uri]

    /** Loads transcript on [Dispatchers.IO]. */
    suspend fun readTranscriptAsync(uri: String): String? = withContext(Dispatchers.IO) { readTranscript(uri) }

    /** 同步读取或缓存查询某条语音条的文字稿（找不到返回 null）。 */
    fun readTranscript(uri: String): String? {
        val transcript = transcriptCache[uri] ?: resolve(uri)
            ?.let { transcriptFileForStem(it.nameWithoutExtension) }
            ?.takeIf(File::isFile)
            ?.let { file -> runCatching { file.readText(Charsets.UTF_8).trim().takeIf(String::isNotEmpty) }.getOrNull() }
        transcript?.let { transcriptCache[uri] = it }
        return transcript
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
        val removed = files.count { file -> file.isFile && shouldDeleteVoiceFile(file, keep, TMP_SUFFIX) }
        resolvedFileCache.clear()
        missingUriCache.clear()
        val keepStems = keep.mapTo(HashSet()) { File(it).nameWithoutExtension }
        transcriptDir.listFiles()?.forEach { tf ->
            if (tf.isFile && (tf.name.endsWith(TMP_SUFFIX) || tf.nameWithoutExtension !in keepStems)) {
                runCatching { tf.delete() }
            }
        }
        removed
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

    private fun ensureTranscriptDir(): File? {
        if (!transcriptDir.exists() && !transcriptDir.mkdirs()) return null
        return transcriptDir.takeIf { it.isDirectory }
    }

    private fun transcriptFileForStem(stem: String): File? {
        if (stem.isBlank()) return null
        return File(transcriptDir, "$stem$TRANSCRIPT_EXT")
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

        private const val TRANSCRIPT_DIR_NAME = "voice_transcripts"
        private const val TRANSCRIPT_EXT = ".txt"
    }
}
