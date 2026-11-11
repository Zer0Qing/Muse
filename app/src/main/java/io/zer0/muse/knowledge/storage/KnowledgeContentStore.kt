package io.zer0.muse.knowledge.storage

import io.zer0.common.Logger
import java.io.File
import java.security.MessageDigest

/**
 * R2 原件存储 — 内容寻址 blob 库(计划 R2 步骤 2 的六断点协议)。
 *
 * 协议(每步失败都不得破坏不变式「不引用不存在文件、不删已有原件」):
 *  1. STAGING: 同目录写入 `<hash>.staging`(与发布目录同分区,rename 原子);
 *  2. HASH: 字节 SHA-256(当前实现为字节全量计算;流式为超大小文本的后续优化);
 *  3. CLOSE_VERIFY: 关闭+大小校验(staging 文件字节数必须与输入一致);
 *  4. PUBLISH: 原子 rename → `<hash>`(已存在同 hash 文件 = 同内容,直接复用);
 *  5. DB_REFERENCE: 调用方落库(store 返回引用后由仓储层登记;本类内 DB 注入失败
 *     模拟为「发布成功但调用方未登记」→ 进入未引用登记,可被孤儿清理回收);
 *  6. CLEANUP: 孤儿清理只回收「未引用登记 + 过期 staging」,绝不触碰其他 hash 的发布文件。
 *
 * 不变式(由 KnowledgeContentStoreTest 钉住):
 *  - put 失败一律返回 null,调用方不得把 null 当引用;
 *  - 任何断点失败都不得删除已发布的其他原件;
 *  - exists(ref) 为 true 时 read(ref) 必须返回与 hash 一致的字节。
 *
 * 测试注入:[injectedFailures] 在指定断点抛出模拟 IO 失败;生产构造不传(空集)。
 */
class KnowledgeContentStore(
    val rootDirectory: File,
    private val injectedFailures: Set<FailurePoint> = emptySet(),
) {

    enum class FailurePoint { STAGING_WRITE, CLOSE_VERIFY, PUBLISH_RENAME, DB_REFERENCE }

    private val publishedDir = File(rootDirectory, "published")
    private val stagingDir = File(rootDirectory, "staging")

    /** 未引用登记(发布成功但调用方未登记 DB 引用的文件名;孤儿清理的回收候选)。 */
    private val unreferenced = mutableSetOf<String>()

    init {
        publishedDir.mkdirs()
        stagingDir.mkdirs()
    }

    /**
     * 写入原件,返回内容 hash 引用;任一断点失败返回 null(不变式由测试钉住)。
     */
    fun put(scheme: String, bytes: ByteArray): String? {
        // 失败清理需要知道本次 staging 文件名;hash 在任何 IO 之前计算(协议顺序 2 提前),
        // catch 分支据此精确清理本次 staging,不批量删其他文件。
        val pendingHash = hashOf(bytes)
        return try {
            val published = File(publishedDir, pendingHash)

            // 1-2. staging + hash(hash 已算)
            if (FailurePoint.STAGING_WRITE in injectedFailures) {
                throw java.io.IOException("injected staging failure")
            }
        val staging = File(stagingDir, "$pendingHash.staging")
        staging.writeBytes(bytes)

        // 3. 关闭校验
        if (FailurePoint.CLOSE_VERIFY in injectedFailures) {
            throw java.io.IOException("injected close-verify failure")
        }
        if (staging.length() != bytes.size.toLong()) {
            throw java.io.IOException("staging size mismatch: ${staging.length()} != ${bytes.size}")
        }

        // 4. 原子发布(同 hash 已存在 = 同内容,幂等复用)
        if (FailurePoint.PUBLISH_RENAME in injectedFailures) {
            throw java.io.IOException("injected publish failure")
        }
        if (!published.exists()) {
            if (!staging.renameTo(published)) {
                throw java.io.IOException("publish rename failed: ${staging.name}")
            }
        } else {
            staging.delete()
        }

        // 5. DB 引用(本类不直接依赖 DAO;仓储层登记失败时调用方调用 markUnreferenced。
        //    注入 DB_REFERENCE 失败模拟「登记链路失败」:文件已发布但视作未引用。)
        if (FailurePoint.DB_REFERENCE in injectedFailures) {
            unreferenced.add(pendingHash)
            return null
        }

        pendingHash
        } catch (e: java.io.IOException) {
            // 失败清理:只删除本次写入的 staging 文件(名字即本次 hash),绝不批量删除
            // 其他 staging(可能属于并发中的其他写入,由 cleanupOrphans 按龄回收),
            // 绝不触碰 published 下的任何文件。
            runCatching {
                val pending = File(stagingDir, "$pendingHash.staging")
                if (pending.isFile) pending.delete()
            }.onFailure { t ->
                Logger.w("KnowledgeContentStore", "staging 清理失败(hash=$pendingHash): ${t.message}", t)
            }
            Logger.w("KnowledgeContentStore", "put 失败(scheme=$scheme): ${e.message}", e)
            null
        }
    }

    /** 发布文件是否存在。 */
    fun exists(ref: String): Boolean = File(publishedDir, ref).isFile

    /** 读取发布文件字节;不存在返回 null。 */
    fun read(ref: String): ByteArray? = File(publishedDir, ref).takeIf { it.isFile }?.readBytes()

    /** 当前 staging 残留(供测试与诊断)。 */
    fun listStaging(): List<String> = stagingDir.listFiles()?.map { it.name } ?: emptyList()

    /** 未引用登记(供测试与孤儿清理)。 */
    fun listUnreferenced(): List<String> = unreferenced.toList()

    /** 调用方登记 DB 引用失败时显式登记未引用(第 5 断点的手动路径)。 */
    fun markUnreferenced(ref: String) {
        unreferenced.add(ref)
    }

    /** 调用方确认 DB 引用已登记(从未引用集合移除)。 */
    fun markReferenced(ref: String) {
        unreferenced.remove(ref)
    }

    /**
     * 孤儿清理:只回收 (a) 过期 staging;(b) 未引用登记的发布文件。
     * 绝不删除未登记的发布文件(它们可能正被其他调用方引用)。
     */
    fun cleanupOrphans(maxAgeMs: Long, nowMs: Long = System.currentTimeMillis()) {
        stagingDir.listFiles()?.forEach { f ->
            if (nowMs - f.lastModified() >= maxAgeMs) f.delete()
        }
        unreferenced.toList().forEach { ref ->
            File(publishedDir, ref).takeIf { it.isFile }?.delete()
            unreferenced.remove(ref)
        }
    }

    /** 测试辅助:手动放置一个 staging 残留(模拟历史中断)。 */
    fun stageRaw(name: String) {
        File(stagingDir, "$name.staging").writeBytes("stale".toByteArray())
    }

    companion object {
        fun hashOf(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
