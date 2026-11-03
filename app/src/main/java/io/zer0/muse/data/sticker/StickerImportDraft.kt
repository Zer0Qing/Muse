package io.zer0.muse.data.sticker

import io.zer0.common.AppJson
import io.zer0.common.Logger
import kotlinx.serialization.Serializable
import java.io.File
import java.util.UUID

/**
 * 导入草稿（表情包重做 P3）：解析出来的图先落在一个"待确认"目录里，
 * 用户在向导里看过、勾过，再进正式库。
 *
 * 为什么必须有这一段（而不是"解析完直接入库"）：
 * 自动切格**准不准，数字判不出来** —— 真实目录页上量过：切准的格子占页 0.7%，
 * 切碎了的也是 0.6%，面积、数量、离散度都分不开。既然判不出来，
 * 就必须在入库前让人看一眼。没有这一段，PDF 导入等于"往库里扔一批可能切错的图，
 * 让用户自己去找哪些是碎的"。
 *
 * 三条约束：
 *  1. 草稿目录带**自己的清单**（draft.json），只存 token/文件名/配文，不存图片字节；
 *  2. 草稿**有过期清理**：用户中途杀掉应用、或者取消导入，不能留下永远占空间的目录；
 *  3. 提交是"移动 + 写正式清单"，取消是"整目录删除"，两条路都不留半份。
 */

/** 草稿里的每一条（清单侧车文件里的形态）。 */
@Serializable
data class DraftItemMeta(
    val token: String,
    val fileName: String,
    val caption: String? = null,
)

/** 一个草稿的元数据（draft.json）。 */
@Serializable
data class DraftRecord(
    val id: String,
    val category: String,
    val createdAtMillis: Long,
    val items: List<DraftItemMeta>,
)

/** 交给界面用的草稿：文件已经解析成 File。 */
data class StickerDraft(
    val id: String,
    val category: String,
    val items: List<StickerDraftEntry>,
)

/** 草稿里的一项。 */
data class StickerDraftEntry(
    val token: String,
    val fileName: String,
    val caption: String?,
    val file: File,
)

/** 建草稿时的输入：一条待确认的图。 */
data class DraftSeed(
    val fileName: String,
    val sourceFile: File,
    val caption: String? = null,
)

/**
 * 草稿仓库。
 *
 * @param draftsRoot 草稿根目录（`filesDir/stickers/.drafts`）
 */
class StickerDraftStore(private val draftsRoot: File) {

    private companion object {
        const val TAG = "StickerDraftStore"
        const val RECORD_FILE = "draft.json"

        /** 超过这个时长的草稿视为遗弃（一次导入正常在几分钟内完成）。 */
        const val STALE_AFTER_MS = 60 * 60 * 1000L
    }

    /** 把临时文件收进一个草稿目录。返回的 file 已在草稿目录内。 */
    init {
        // 建好就清一次：没人会专门去点"清理草稿"，而遗弃的草稿是**用户看不见也删不掉**的占用。
        // 放在这儿（而不是应用启动流程里）是因为仓库本身按需构造，效果一样且不用加接线。
        purgeStale()
    }

    fun create(category: String, seeds: List<DraftSeed>): StickerDraft? {
        if (seeds.isEmpty()) return null
        val id = UUID.randomUUID().toString().take(12)
        val dir = File(draftsRoot, id)
        return runCatching {
            dir.mkdirs()
            val metas = seeds.map { seed ->
                val token = UUID.randomUUID().toString().take(8)
                val target = File(dir, token + "_" + seed.fileName.substringAfterLast('/'))
                seed.sourceFile.copyTo(target, overwrite = false)
                DraftItemMeta(token, seed.fileName, seed.caption)
            }
            val record = DraftRecord(id, category, System.currentTimeMillis(), metas)
            File(dir, RECORD_FILE).writeText(AppJson.encodeToString(DraftRecord.serializer(), record))
            load(id)
        }.onFailure { Logger.w(TAG, "建草稿失败: ${it.message}") }
            .getOrNull()
    }

    fun load(id: String): StickerDraft? {
        val dir = File(draftsRoot, id)
        val record = File(dir, RECORD_FILE).takeIf { it.isFile }?.let { file ->
            runCatching {
                AppJson.decodeFromString(DraftRecord.serializer(), file.readText())
            }.getOrNull()
        } ?: return null
        val entries = record.items.mapNotNull { meta ->
            val file = dir.listFiles()?.firstOrNull { it.name.startsWith(meta.token + "_") } ?: return@mapNotNull null
            StickerDraftEntry(meta.token, meta.fileName, meta.caption, file)
        }
        return StickerDraft(record.id, record.category, entries)
    }

    /** 全部未过期草稿（启动时用来发现"上次没导完"的东西）。 */
    fun list(): List<StickerDraft> =
        draftsRoot.listFiles()?.filter { it.isDirectory }?.mapNotNull { load(it.name) } ?: emptyList()

    /** 提交或取消之后都要调：整个草稿目录删掉。 */
    fun delete(id: String) {
        runCatching { File(draftsRoot, id).deleteRecursively() }
            .onFailure { Logger.w(TAG, "删除草稿失败: ${it.message}") }
    }

    /** 清掉遗弃的草稿，返回删除个数。启动时跑一次。 */
    fun purgeStale(now: Long = System.currentTimeMillis()): Int {
        var removed = 0
        draftsRoot.listFiles()?.filter { it.isDirectory }?.forEach { dir ->
            val record = File(dir, RECORD_FILE).takeIf { it.isFile }?.let { file ->
                runCatching {
                    AppJson.decodeFromString(DraftRecord.serializer(), file.readText())
                }.getOrNull()
            }
            val abandoned = record == null || now - record.createdAtMillis > STALE_AFTER_MS
            if (abandoned) {
                runCatching { dir.deleteRecursively() }.onSuccess { removed++ }
            }
        }
        return removed
    }
}
