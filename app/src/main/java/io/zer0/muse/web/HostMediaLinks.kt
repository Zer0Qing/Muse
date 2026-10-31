package io.zer0.muse.web

import java.util.Base64

/**
 * 消息里的媒体引用 → 浏览器可取的链接。
 *
 * App 侧的图片/语音引用有三种形态：`file:///data/user/…` 绝对路径、`data:image/…;base64,…`
 * 内联数据、以及裸 base64。三种在浏览器里都取不到（前者是本机路径，后两者不是地址），
 * 下发前统一换成 [MEDIA_PATH_PREFIX] + token；[HostMediaStore] 登记不上的（文件已清理、
 * 指向允许目录之外、内容不是可识别媒体）直接从数组里剔除，不留一个必然 404 的地址 ——
 * 网页端拿到不可用地址会比拿到空数组更难处理。
 *
 * `http(s)` 地址原样下发：浏览器本来就能取，绕一圈代理反而多一层失败点。
 */
internal class HostMediaLinks(
    private val store: HostMediaStore,
) {

    /** 单条引用的转换；返回 null 表示这条媒体在网页端不可用。[ownerMessageId] 用于内联落盘文件的归属前缀。 */
    fun link(value: String, ownerMessageId: String): String? {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.startsWith(HTTP_SCHEME) || trimmed.startsWith(HTTPS_SCHEME)) return trimmed
        return tokenFor(trimmed, ownerMessageId)?.let { MEDIA_PATH_PREFIX + it }
    }

    /** 列表版转换，顺带剔除不可用项。 */
    fun links(values: List<String>, ownerMessageId: String): List<String> =
        values.mapNotNull { link(it, ownerMessageId) }

    /** 语音引用单独走一条链：非语音消息不下发 voiceUrl。 */
    fun voiceLink(value: String?, ownerMessageId: String): String? =
        value?.takeIf { it.isNotBlank() }?.let { link(it, ownerMessageId) }

    /**
     * 头像这类一次性资源：只认本地文件与 `http(s)`。
     *
     * 内联 base64 不在这里落盘 —— 头像不属于任何消息，落下来的文件没有删除时机，
     * 徒增孤儿；转不了就给 null，由前端退回首字。
     */
    fun fileOrHttpLink(value: String?): String? {
        val trimmed = value?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        if (trimmed.startsWith(HTTP_SCHEME) || trimmed.startsWith(HTTPS_SCHEME)) return trimmed
        if (!trimmed.startsWith(FILE_SCHEME) && !isLocalPath(trimmed)) return null
        return store.tokenForFile(trimmed)?.let { MEDIA_PATH_PREFIX + it }
    }

    /** 按引用形态分派；每一种都只在 [HostMediaStore] 的允许目录内才会拿到 token。 */
    private fun tokenFor(value: String, ownerMessageId: String): String? = when {
        value.startsWith(FILE_SCHEME) || isLocalPath(value) -> store.tokenForFile(value)
        value.startsWith(DATA_SCHEME) -> store.tokenForInlineData(
            HostMediaContentType.inlinePayload(value),
            HostMediaContentType.declaredMime(value),
            ownerMessageId,
        )

        looksLikeBase64(value) -> store.tokenForInlineData(value, null, ownerMessageId)
        else -> null
    }

    /** 不带 `file://` 前缀的本机路径：Unix 绝对路径与 Windows 盘符路径。 */
    private fun isLocalPath(value: String): Boolean =
        value.startsWith("/") || value.startsWith(UNC_PREFIX) || DRIVE_PATH.matches(value)

    private fun looksLikeBase64(value: String): Boolean {
        if (value.length < MIN_BASE64_LENGTH) return false
        // base64 长度 mod 4 不可能为 1（奇数个尾部字节会多出一个填充位）。
        if (value.length % 4 == 1) return false
        return runCatching { Base64.getMimeDecoder().decode(value).isNotEmpty() }.getOrDefault(false)
    }

    companion object {
        /** 媒体路由前缀，与 WebServer 里安装的 `/api/media/{token}` 路径一致。 */
        const val MEDIA_PATH_PREFIX = "/api/media/"

        private const val FILE_SCHEME = "file://"
        private const val DATA_SCHEME = "data:"
        private const val HTTP_SCHEME = "http://"
        private const val HTTPS_SCHEME = "https://"

        /** 网络共享路径前缀（`\\server\share\x.png`），也是本机可解析的路径写法。 */
        private const val UNC_PREFIX = "\\\\"

        /** 短于此长度不可能是图片数据（占位 gif 也远长于此）。 */
        private const val MIN_BASE64_LENGTH = 32

        /** Windows 盘符路径（`C:\…` / `C:/…`）。 */
        private val DRIVE_PATH = Regex("^[A-Za-z]:[\\\\/].*")
    }
}
