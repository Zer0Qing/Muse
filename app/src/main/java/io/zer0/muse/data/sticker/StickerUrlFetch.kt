package io.zer0.muse.data.sticker

import io.zer0.common.Logger
import java.io.File
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL

/**
 * 按图床直链取原图（表情包重做 P5）。
 *
 * **只在用户明确点"按原图链接下载"时才走这里**：导入本身不联网。
 * 一条素材文档里可能有几十条链接，自动抓意味着用户没同意就往外发请求，
 * 也意味着"我选了个文件"变成"应用替我下了一整包东西"。
 *
 * 守卫放在纯函数里（可 JVM 测），联网那一段只是照守卫的结论执行。
 */
object StickerUrlFetch {

    private const val TAG = "StickerUrlFetch"

    /** 单张上限（10MB）：表情图超过这个数就是拿错文件了。 */
    const val MAX_ITEM_BYTES = 10L * 1024 * 1024

    /** 一次批量上限（64MB）。 */
    const val MAX_BATCH_BYTES = 64L * 1024 * 1024

    /** 一次最多取多少张。 */
    const val MAX_BATCH_ITEMS = 200

    /**
     * 这个链接能不能取。
     *
     * 三条理由各不一样：
     *  - 只放 https：图床链接在文档正文里，属于**别人写进去的内容**，
     *    明文下载等于把素材来源的流量裸奔；
     *  - 拒本机与内网地址：表情包里塞 `http://127.0.0.1:8080/admin`
     *    或云元数据地址，就是拿应用当内网探针使；
     *  - 拒非 http(s) 协议：`file://`、`gopher://` 这种一律不试。
     */
    fun isAllowed(raw: String): Boolean {
        val url = runCatching { URL(raw) }.getOrNull() ?: return false
        if (url.protocol.lowercase() != "https") return false
        val host = url.host
        if (host.isNullOrBlank()) return false
        return runCatching {
            val addresses = InetAddress.getAllByName(host)
            addresses.isNotEmpty() && addresses.all { it.isPubliclyRoutable() }
        }.getOrDefault(false)
    }

    /** 本机、私有网段、链路本地（含云元数据 169.254.0.0/16）都算不可信。 */
    private fun InetAddress.isPubliclyRoutable(): Boolean =
        !isLoopbackAddress && !isAnyLocalAddress && !isSiteLocalAddress && !isLinkLocalAddress

    /**
     * 取一张写到目标文件。
     *
     * @return null 表示成功；否则是失败原因（调用方按条累计，不让一张坏链接毁掉整批）
     */
    @Suppress("TooGenericExceptionCaught") // 网络层任何异常都只转成"这一条失败"，不能掀掉整批
    fun fetch(url: String, target: File): String? {
        if (!isAllowed(url)) return "链接不被允许（只接受公网 https）"
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 15_000
                readTimeout = 30_000
                requestMethod = "GET"
            }
            if (connection.url.protocol.lowercase() != "https") {
                "重定向后离开了 https"
            } else if (connection.responseCode !in 200..299) {
                "下载失败：HTTP ${connection.responseCode}"
            } else if (connection.contentLengthLong > MAX_ITEM_BYTES) {
                "文件过大（${connection.contentLengthLong / 1024 / 1024}MB，上限 ${MAX_ITEM_BYTES / 1024 / 1024}MB）"
            } else {
                connection.inputStream.use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
                if (target.length() > MAX_ITEM_BYTES) {
                    target.delete()
                    "文件超过上限，已丢弃"
                } else if (target.length() <= 0L) {
                    target.delete()
                    "下载内容为空"
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            Logger.w(TAG, "下载失败: ${e.message}")
            runCatching { target.delete() }
            "下载失败：${e.message ?: e.javaClass.simpleName}"
        } finally {
            runCatching { connection?.disconnect() }
        }
    }
}
