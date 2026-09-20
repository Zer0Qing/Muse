package io.zer0.muse.data.plugin.market

import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.muse.tools.script.SkillBridgeHttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** 受控出口返回的一次有界响应；[body] 最多为请求时给定的上限字节数。 */
internal data class CatalogHttpResponse(
    val status: Int,
    val body: ByteArray,
)

/**
 * 目录拉取传输层。
 *
 * 生产实现委托 [SkillBridgeHttpClient]（DNS 固定 + 逐跳重定向校验 + 直连路由复核）；
 * 单元测试注入 fake 传输即可覆盖大小/超时/状态码等客户端策略，不依赖网络。
 */
internal fun interface CatalogHttpTransport {
    /** @throws Exception 传输失败（DNS/路由/超时/IO）时抛出。 */
    fun getBytes(url: String, maxBytes: Int): CatalogHttpResponse
}

/** 拉取结果；[signedCatalog] 只是已解析的载荷，仍需交给目录校验器验签。 */
internal data class CatalogFetchResult(
    val signedCatalog: SignedPluginCatalog? = null,
    val error: String? = null,
) {
    val success: Boolean get() = signedCatalog != null
}

/**
 * 插件目录客户端：从可配置的目录 URL 拉取 `SignedPluginCatalog` JSON。
 *
 * 策略与 [PluginDownloadClient] 对齐：
 *  - 只接受 `https://` 目录地址（目录内容本身仍必须通过签名校验，https 只是额外的传输保护）；
 *  - 通过项目受控 HTTP 出口发起请求，出口负责 DNS 固定、逐跳校验与超时；
 *  - 读取上限 [maxCatalogBytes]，超限直接拒绝而不是静默截断后解析；
 *  - 未配置目录（空 URL）返回明确错误，绝不返回空目录冒充可用。
 */
internal class PluginCatalogClient(
    private val transport: CatalogHttpTransport = SkillBridgeTransport(),
    private val maxCatalogBytes: Int = MAX_CATALOG_BYTES,
) {

    fun fetch(catalogUrl: String): CatalogFetchResult {
        val url = catalogUrl.trim()
        if (url.isEmpty()) return CatalogFetchResult(error = "插件目录未配置")
        if (!url.startsWith(HTTPS_PREFIX)) {
            return CatalogFetchResult(error = "目录地址必须使用 https://")
        }
        return try {
            // 多读 1 字节：传输层按上限截断，若返回超过上限说明真实响应超限，必须拒绝。
            val response = transport.getBytes(url, maxCatalogBytes + 1)
            if (response.status !in 200..299) {
                return CatalogFetchResult(error = "目录请求失败: HTTP ${response.status}")
            }
            val bytes = response.body
            if (bytes.size > maxCatalogBytes) {
                return CatalogFetchResult(error = "目录体积超过限制")
            }
            val signed = AppJson.decodeFromString(
                SignedPluginCatalog.serializer(),
                bytes.toString(Charsets.UTF_8),
            )
            CatalogFetchResult(signedCatalog = signed)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: SerializationException) {
            Logger.w(TAG, "目录 JSON 解析失败: ${error.message}")
            CatalogFetchResult(error = "目录内容不是合法的签名目录 JSON")
        } catch (error: Exception) {
            Logger.w(TAG, "目录拉取失败: ${error.message}", error)
            CatalogFetchResult(error = error.message ?: "目录拉取失败")
        }
    }

    /**
     * 生产传输：受控 HTTP 出口 + 目录专用超时。
     *
     * 出口本身是唯一允许发起请求的实现；这里只收紧超时（连接/读取/整体调用），
     * DNS 固定、重定向逐跳校验与 SSRF 路由校验全部保留。
     */
    private class SkillBridgeTransport : CatalogHttpTransport {
        private val client = SkillBridgeHttpClient(
            baseClient = OkHttpClient.Builder()
                .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .callTimeout(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .build(),
        )

        override fun getBytes(url: String, maxBytes: Int): CatalogHttpResponse {
            val result = client.getBytes(url, maxBytes)
            return CatalogHttpResponse(status = result.status, body = result.body)
        }
    }

    companion object {
        private const val TAG = "PluginCatalogClient"
        private const val HTTPS_PREFIX = "https://"

        /**
         * 目录 JSON 上限；与 `PluginCatalogVerifier` 的 MAX_CATALOG_BYTES 保持一致，
         * 避免客户端放行校验器必然拒绝的载荷。
         */
        internal const val MAX_CATALOG_BYTES = 512 * 1024

        internal const val CONNECT_TIMEOUT_MS = 10_000L
        internal const val READ_TIMEOUT_MS = 15_000L
        internal const val CALL_TIMEOUT_MS = 20_000L
    }
}
