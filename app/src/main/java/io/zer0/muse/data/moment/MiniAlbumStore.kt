package io.zer0.muse.data.moment

import android.content.Context
import android.net.Uri
import io.zer0.common.Logger
import io.zer0.muse.data.`import`.MiniAlbumImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * v2.6.9: 小手机相册的本地导入存储。
 *
 * 为什么单独存文件而不是进数据库：
 * 相册原本是「朋友圈配图 / 消息图片」的派生视图（MiniDataLoader 从 messages 表读）。
 * 用户要的是「我自己也能往小手机相册里放图」，这类图与任何消息无关，
 * 若新建一张表就又要加一版 DB 迁移（当前已到 115，不想再堆）。
 *
 * 这里存到 `filesDir/mini_album_import/`，用 `file://` 路径登记；
 * 相册页把导入结果与派生图片合并展示。
 *
 * 与派生图片的区别（UI 上也会体现）：
 *  - 派生图片的 id 是 uri 本身，删除只能「隐藏」
 *  - 导入图片是真实文件，可以真删除
 */
class MiniAlbumStore(
    private val context: Context,
) {
    private val dir: File
        get() = File(context.filesDir, DIR_NAME).apply { mkdirs() }

    /** 列出所有导入的图片。 */
    suspend fun listImported(): List<MiniAlbumImage> = withContext(Dispatchers.IO) {
        runCatching {
            dir.listFiles()
                ?.filter { it.isFile && it.length() > 0 }
                ?.sortedByDescending { it.lastModified() }
                ?.map { file ->
                    MiniAlbumImage(
                        uri = Uri.fromFile(file).toString(),
                        createdAt = file.lastModified(),
                    )
                }
                ?: emptyList()
        }.onFailure { t ->
            Logger.w(TAG, "列出导入图片失败: ${t.message}")
        }.getOrDefault(emptyList())
    }

    /**
     * 导入一批图片。
     *
     * 复制而不是引用原 URI：系统相册的 URI 可能需要长期读取授权，
     * 用户清理相册后原图会失效。复制一份到应用私有目录最稳。
     */
    suspend fun import(uris: List<Uri>): Int = withContext(Dispatchers.IO) {
        var ok = 0
        uris.forEach { uri ->
            val copied = runCatching {
                val target = File(dir, "img_${UUID.randomUUID()}.jpg")
                val input = context.contentResolver.openInputStream(uri)
                if (input == null) {
                    false
                } else {
                    input.use { stream ->
                        target.outputStream().use { output -> stream.copyTo(output) }
                    }
                    target.length() > 0
                }
            }.onFailure { t ->
                Logger.w(TAG, "导入图片失败: ${t.message}")
            }.getOrDefault(false)
            if (copied) ok++
        }
        Logger.i(TAG, "相册导入完成: 成功 $ok / ${uris.size}")
        ok
    }

    /** 删除一张导入的图片（真删，不是隐藏）。 */
    suspend fun delete(uriString: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val uri = Uri.parse(uriString)
            val path = uri.path ?: return@runCatching false
            val file = File(path)
            // 安全边界：只允许删自己目录下的文件
            if (file.parentFile?.absolutePath != dir.absolutePath) return@runCatching false
            file.delete()
        }.onFailure { t ->
            Logger.w(TAG, "删除导入图片失败: ${t.message}")
        }.getOrDefault(false)
    }

    /** 判断某个 uri 是否属于「导入的图片」（可删）而不是派生图片（只能隐藏）。 */
    fun isImported(uriString: String): Boolean = uriString.contains("/$DIR_NAME/")

    companion object {
        private const val TAG = "MiniAlbumStore"
        private const val DIR_NAME = "mini_album_import"
    }
}
