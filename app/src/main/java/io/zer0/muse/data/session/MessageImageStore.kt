package io.zer0.muse.data.session

import io.zer0.common.Logger
import java.io.File
import java.util.Base64

/**
 * v1.134 P1-2: 消息图片存储服务 — base64 ↔ 文件路径双向转换。
 *
 * **背景**:Phase 8.6 起消息表 [MessageEntity.imageBase64Json] 直接存图片 base64 字符串,
 * 单张 4K 图 base64 约 1MB+,长会话 / 多图会话会让 messages 表行体积膨胀,
 * 影响 SQLite 查询 / 索引 / 备份性能。
 *
 * **改造**:此服务负责把 base64 落盘到 [storageDir],DB 只存 "file://xxx" 形式的路径;
 * 读取时识别 "file://" 前缀读文件返回 base64,否则当作旧数据直接返回 base64(向后兼容)。
 *
 * **触发阈值**:仅当 base64 长度 > [MIN_LENGTH_TO_PERSIST](默认 1024) 时才落盘,
 * 短数据(如 1x1 占位图)仍以 base64 形式存 DB,避免小文件碎片化。
 *
 * **文件命名**:`{messageId}_{index}.bin`(base64 原文,不含 MIME 前缀),
 * 同 messageId + index 覆盖式写入,避免重复。
 *
 * **线程安全**:所有方法在 [Dispatchers.IO] 上调用(由调用方保证),
 * 内部不做同步(单条消息的写入 / 读取不会并发)。
 *
 * **失败容错**:写入失败返回原始 base64(降级为旧行为),读取失败返回空字符串,
 * 避免图片存储故障阻塞主对话流。
 *
 * @param storageDir 持久化目录(filesDir/muse_images/)
 */
class MessageImageStore(
    private val storageDir: File,
) {
    /** base64 长度阈值,超过此值才落盘(短数据仍存 base64)。 */
    private val minLengthToPersist: Int = MIN_LENGTH_TO_PERSIST

    /**
     * 把 base64 列表转为可持久化形式(路径或原始 base64)。
     *
     * - base64 长度 ≤ [minLengthToPersist]:原样返回(存 DB)
     * - base64 长度 > [minLengthToPersist]:落盘,返回 "file://{absolutePath}"
     * - 已是 "file://" 前缀:原样返回(已落盘,避免重复写)
     *
     * @param messageId 消息 id(用作文件名前缀)
     * @param base64List 原始 base64 列表(可能含 data:image/...;base64, 前缀)
     * @return 可持久化形式列表(路径或 base64)
     */
    fun toPersistable(messageId: String, base64List: List<String>): List<String> {
        if (base64List.isEmpty()) return emptyList()
        return base64List.mapIndexed { idx, value ->
            // 已是路径,原样返回(避免重复写)
            if (value.startsWith(FILE_PREFIX)) return@mapIndexed value
            // 短数据,原样返回
            if (value.length < minLengthToPersist) return@mapIndexed value
            // 长数据,落盘
            runCatching {
                val file = File(storageDir, "${messageId}_${idx}.bin")
                if (!storageDir.exists()) storageDir.mkdirs()
                // 剥离 data:image/...;base64, 前缀后写入
                val raw = stripDataUriPrefix(value)
                file.writeBytes(Base64.getDecoder().decode(raw))
                FILE_PREFIX + file.absolutePath
            }.getOrElse { e ->
                Logger.w(TAG, "图片落盘失败,降级为 base64: ${e.message}")
                value
            }
        }
    }

    /**
     * 把持久化形式(路径或 base64)转为 UI 用的 base64 列表。
     *
     * - "file://" 前缀:读文件返回 base64(含 data:image/...;base64, 前缀,兼容 UI 渲染)
     * - 其他:当作 base64 直接返回(向后兼容旧数据)
     *
     * 读取失败返回空字符串(不抛异常,避免阻塞消息列表加载)。
     */
    fun toBase64List(persistableList: List<String>): List<String> {
        if (persistableList.isEmpty()) return emptyList()
        return persistableList.map { value ->
            if (!value.startsWith(FILE_PREFIX)) return@map value
            runCatching {
                val path = value.removePrefix(FILE_PREFIX)
                val bytes = File(path).readBytes()
                // 文件存的是原始 base64 解码后的二进制,重新编码为 base64
                // 不加 data: 前缀(UI 层 Picasso/Coil 等会自动识别纯 base64)
                Base64.getEncoder().encodeToString(bytes)
            }.getOrElse { e ->
                Logger.w(TAG, "图片读取失败: ${e.message}")
                ""
            }
        }
    }

    /**
     * 删除指定消息的所有图片文件(删除消息时调用,避免孤儿文件)。
     * 文件不存在静默忽略。
     */
    fun deleteByMessageId(messageId: String) {
        runCatching {
            if (!storageDir.exists()) return@runCatching
            val prefix = "${messageId}_"
            storageDir.listFiles { f -> f.isFile && f.name.startsWith(prefix) }
                ?.forEach { f -> runCatching { f.delete() } }
        }.onFailure { e ->
            Logger.w(TAG, "删除消息图片失败: $messageId", e)
        }
    }

    /** 剥离 data:image/...;base64, 前缀,返回纯 base64 字符串。 */
    private fun stripDataUriPrefix(value: String): String {
        val commaIdx = value.indexOf(',')
        // data:image/png;base64,xxx 形式
        if (value.startsWith("data:") && commaIdx > 0 && commaIdx < 100) {
            return value.substring(commaIdx + 1)
        }
        return value
    }

    private companion object {
        private const val TAG = "MessageImageStore"
        /** base64 长度阈值,超过此值才落盘(约 1KB,对应 ~750 字节二进制)。 */
        private const val MIN_LENGTH_TO_PERSIST = 1024
        /** 文件路径前缀,用于识别已落盘的图片。 */
        private const val FILE_PREFIX = "file://"
    }
}
