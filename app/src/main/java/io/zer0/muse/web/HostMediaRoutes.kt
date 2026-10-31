package io.zer0.muse.web

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.http.content.LocalFileContent
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.zer0.common.Logger

/**
 * 消息媒体的读取路由：`GET /api/media/{token}`。
 *
 * 只读、只认 [HostMediaStore] 里登记过的 token。装到与 `/ws` 同级的 JWT 鉴权块里：
 * 图片与语音就是聊天记录本身，未登录的局域网设备不该能翻出历史会话的截图。
 *
 * 越界与不存在统一 404，不回 403 也不回 500：前者不给探测者反馈，后者不该由
 * 「文件被清理」这种正常情况触发。
 */
internal fun Route.installHostMediaRoutes(mediaStore: HostMediaStore) {
    get("/api/media/{token}") {
        val token = call.parameters["token"].orEmpty()
        val file = token.takeIf { it.isNotBlank() }?.let(mediaStore::resolve)
        if (file == null) {
            if (token.isNotBlank()) Logger.d(TAG, "媒体不可用: token=$token")
            call.respond(HttpStatusCode.NotFound)
            return@get
        }
        val contentType = HostMediaContentType.of(file) ?: HostMediaContentType.OCTET_STREAM
        call.response.header(HttpHeaders.CacheControl, CACHE_CONTROL)
        // LocalFileContent 直接把文件交给引擎分块发送,不把整张图读进内存。
        call.respond(LocalFileContent(file, ContentType.parse(contentType)))
    }
}

private const val TAG = "HostMediaRoutes"

/**
 * 媒体内容按内容哈希命名、token 与路径一一对应，同一份文件的内容不会变，所以可以缓存；
 * `private` 是不允许中间代理共享，`max-age=300` 是不把已删会话的图片在浏览器里多留半天。
 */
private const val CACHE_CONTROL = "private, max-age=300"
