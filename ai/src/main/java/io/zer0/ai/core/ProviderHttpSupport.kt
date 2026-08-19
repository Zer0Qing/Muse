package io.zer0.ai.core

import io.zer0.ai.util.KeyRoulette
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import java.util.concurrent.TimeUnit

/**
 * v1.80 (M3+M5): AI Provider HTTP 公共支持。
 *
 * 集中三家 Provider(OpenAI / Gemini / Anthropic)的共用 HTTP 逻辑:
 *  - OkHttpClient 构建(统一超时配置,消除三处硬编码)
 *  - 错误体安全读取(消除 `runCatching { resp.body.string() }.getOrDefault("")` 重复)
 *  - HTTP 状态码分类(支撑差异化重试/提示)
 *
 * v1.73 已记录 M3(三 Provider 异常处理同构可抽 ProviderHttpSupport)和
 * M5(错误体 runCatching 重复),此处统一落地。
 *
 * v1.0.1: 新增 [keyRoulette] 共享单例 + [effectiveApiKey] 辅助方法,
 *  支持多 key 轮换与限流降级。各 Provider 在构造请求前调 [effectiveApiKey]
 *  取当前应使用的 key,429 错误时调 [keyRoulette].pickNext 切换 key 重试。
 */
abstract class ProviderHttpSupport(
    protected val config: ProviderConfig,
) : Provider {

    /**
     * 共享 OkHttpClient — 所有 Provider 统一超时配置。
     *
     * v1.80 (M-HTTP1): 改为引用 companion object 中的全局单例 [sharedHttpClient],
     * 避免每个 ProviderHttpSupport 子类实例化时各建一个 OkHttpClient(浪费连接池资源)。
     *
     * 子类如需额外自定义(如 Gemini VertexAi 的短超时 client),
     * 可用 `httpClient.newBuilder().xxx().build()` 派生,共享连接池。
     */
    protected val httpClient: OkHttpClient get() = sharedHttpClient

    /**
     * v1.0.1: 当前 Provider 实例使用的 API key。
     *
     * 通过 [keyRoulette] 从 [ProviderConfig.apiKey](可能含多个逗号分隔的 key)中
     * 按 LRU 策略选取。每次请求前调用,确保多 key 时负载分摊。
     *
     * 单 key 场景下直接返回原 key,无性能开销。
     *
     * 429 限流时,Provider 应调 [switchToNextKey] 切换到下一个 key 重试,
     * 而非用同一 key 反复重试。
     */
    protected var currentApiKey: String = config.apiKey
        private set

    /**
     * v1.0.1: 取当前应使用的 API key。
     *
     * 首次调用时通过 [keyRoulette] 选取,后续返回缓存的 [currentApiKey]。
     * Provider 在构造请求 header / query param 时调用此方法。
     *
     * 单 key 场景(config.apiKey 不含逗号/换行)直接返回原 key,跳过 LRU 逻辑。
     */
    protected fun effectiveApiKey(): String {
        if (!hasMultipleKeys()) {
            return config.apiKey.trim()
        }
        if (currentApiKey.isBlank() || currentApiKey == config.apiKey) {
            currentApiKey = keyRoulette.pick(config.id, config.apiKey)
        }
        return currentApiKey
    }

    /**
     * v1.0.18: 取当前应使用的 API key,带免费模型 fallback 兜底。
     *
     * 在 [effectiveApiKey] 基础上追加一层 fallback:
     *  - 用户已填 apiKey(单/多 key)→ 返回用户 key,fallback 不生效
     *  - 用户未填 apiKey + baseUrl 命中 siliconflow + modelId 在白名单
     *    → 返回 [FreeModelConfig.FALLBACK_API_KEY](CI/CD 注入)
     *  - 其余场景 → 返回空串(让调用方按 [ProviderConfig.allowMissingApiKey] 处理)
     *
     * @param modelId 当前请求的模型 id(用于白名单校验,空串表示未知)
     */
    protected fun resolveEffectiveApiKey(modelId: String = ""): String {
        val userKey = effectiveApiKey()
        if (userKey.isNotBlank()) return userKey
        // v1.0.18: 免费模型 fallback(host + modelId 双重白名单校验)
        return FreeModelConfig.resolveApiKey(
            providerId = config.id,
            baseUrl = config.resolvedBaseUrl(),
            modelId = modelId,
            userApiKey = userKey,
            hiddenFromSettings = config.hiddenFromSettings,
        ) ?: ""
    }

    /**
     * v1.0.1: 429 限流时切换到下一个 key。
     *
     * 把当前 key 加入软黑名单(60s),然后选取下一个 key。
     * 如果只有一个 key,返回原 key(由 Provider 走指数退避重试)。
     *
     * @return true 表示成功切换到新 key(Provider 应立即重试);
     *         false 表示只有一个 key 或切换后仍是同一 key(Provider 应走指数退避)
     */
    protected fun switchToNextKey(): Boolean {
        if (!hasMultipleKeys()) {
            return false
        }
        val previous = currentApiKey
        currentApiKey = keyRoulette.pickNext(config.id, config.apiKey, previous)
        return currentApiKey != previous
    }

    /**
     * v1.0.1: 标记当前 key 为失败(如 401 鉴权失败)。
     *
     * 与 [switchToNextKey] 的区别:
     *  - [markKeyFailed] 用于不可恢复的错误(401/403/key 失效),hardBlock=true,
     *    5 分钟内完全排除该 key
     *  - [switchToNextKey] 用于可恢复的限流(429),软黑名单 60s
     *
     * @return true 表示还有其他 key 可用(Provider 应切换后重试);
     *         false 表示只有一个 key 或所有 key 都已失败
     */
    protected fun markKeyFailed(hardBlock: Boolean = true): Boolean {
        if (!hasMultipleKeys()) {
            return false
        }
        val failedKey = currentApiKey
        keyRoulette.markFailed(config.id, failedKey, hardBlock = hardBlock)
        currentApiKey = ""  // 重置,下次 effectiveApiKey 会重新选取
        val newKey = effectiveApiKey()
        return newKey != failedKey
    }

    private fun hasMultipleKeys(): Boolean =
        config.apiKey.contains(',') || config.apiKey.contains('\n')

    companion object {
        /** 错误体最大截取长度(防止超大 HTML 错误页撑爆日志/UI)。 */
        const val ERROR_BODY_MAX_LEN = 500

        /**
         * v1.0.1: 全局共享 KeyRoulette 单例。
         *
         * 所有 ProviderHttpSupport 子类共用同一实例,确保多 key 轮换状态全局一致。
         * 通过 [keyRoulette] 属性访问。
         */
        private val sharedKeyRoulette: KeyRoulette by lazy { KeyRoulette() }

        /** v1.0.1: 暴露给子类使用的 KeyRoulette 访问器。 */
        protected val keyRoulette: KeyRoulette get() = sharedKeyRoulette

        /**
         * 全局共享 OkHttpClient 单例(M-HTTP1)。
         * 所有 ProviderHttpSupport 子类共用同一实例,共享连接池与超时配置。
         * 子类可用 `sharedHttpClient.newBuilder().xxx().build()` 派生差异化配置。
         *
         * v1.135 性能优化:
         *  - 扩大连接池到 10 个空闲连接、keep-alive 5 分钟,减少多 Provider / 并发请求时的
         *    连接重建与 TLS 握手开销。
         *  - 显式启用 HTTP/2 + HTTP/1.1 协商,对支持 HTTP/2 的端点降低延迟。
         *  - 启用 gzip(OkHttp 默认已开启),这里显式保留意图。
         */
        private val sharedHttpClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(ProviderHttpDefaults.CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
                .readTimeout(ProviderHttpDefaults.READ_TIMEOUT_SEC, TimeUnit.SECONDS)
                .writeTimeout(ProviderHttpDefaults.WRITE_TIMEOUT_SEC, TimeUnit.SECONDS)
                .callTimeout(ProviderHttpDefaults.CALL_TIMEOUT_SEC, TimeUnit.SECONDS)
                .connectionPool(
                    ConnectionPool(
                        maxIdleConnections = 10,
                        keepAliveDuration = 5,
                        timeUnit = TimeUnit.MINUTES,
                    ),
                )
                .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
                .build()
        }

        /**
         * 安全读取 Response body,失败返回空串。
         * 替代各家重复的 `runCatching { resp.body.string() }.getOrDefault("")`。
         * 只捕获 IOException,不吞 CancellationException / OOM。
         * v1.97: 追加捕获 IllegalStateException — EventSourceListener/WebSocketListener
         * 回调中的 Response body 已被框架 stripped,读取会抛 IllegalStateException。
         */
        fun readBodySafely(response: Response): String = try {
            response.body?.string() ?: ""
        } catch (e: java.io.IOException) {
            ""
        } catch (e: IllegalStateException) {
            ""
        }

        /**
         * 安全读取 Response body 并截断到 [maxLen],防止超大错误体。
         */
        fun readBodyCapped(response: Response, maxLen: Int = ERROR_BODY_MAX_LEN): String {
            val body = readBodySafely(response)
            return if (body.length > maxLen) body.take(maxLen) else body
        }

        /**
         * B-03: 带大小上限的流式响应体读取 — contentLength 未知(chunked)或未声明时同样生效。
         *
         * 逐块累计,超过 [maxBytes] 即中断(丢弃剩余),避免 chunked 响应绕过
         * contentLength 检查把超大响应整读进内存。
         *
         * @return Pair<已读文本(最多 maxBytes), 是否超限>
         */
        fun readBodyCappedStreaming(response: Response, maxBytes: Int): Pair<String, Boolean> {
            val body = response.body ?: return "" to false
            val sb = StringBuilder()
            var kept = 0
            var over = false
            try {
                body.byteStream().use { input ->
                    val buf = ByteArray(8192)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        if (kept + n > maxBytes) {
                            val take = maxBytes - kept
                            if (take > 0) sb.append(String(buf, 0, take, Charsets.UTF_8))
                            over = true
                            break
                        }
                        sb.append(String(buf, 0, n, Charsets.UTF_8))
                        kept += n
                    }
                }
            } catch (_: java.io.IOException) {
                // 读取中断按已读内容返回,由调用方按超限/短响应处理
            }
            return sb.toString() to over
        }

        /**
         * 按 HTTP 状态码返回人类可读的错误分类,用于差异化提示/重试决策。
         * 返回 null 表示无需特殊分类(如 400/404 等业务错误)。
         */
        fun classifyHttpCode(code: Int): String? = when (code) {
            401 -> "authentication failed"
            403 -> "permission denied"
            408 -> "request timeout"
            429 -> "rate limited"
            in 500..599 -> "server error"
            else -> null
        }

        /**
         * 构建标准化的 HTTP 错误消息(含状态码分类 + 截断的 body 摘要)。
         * 各 Provider 的 parseErrorMessage 可在此基础上追加自家错误结构解析。
         */
        fun buildHttpErrorMessage(prefix: String, code: Int, body: String): String {
            val category = classifyHttpCode(code)
            return buildString {
                append(prefix).append("HTTP ").append(code)
                category?.let { append(" [").append(it).append("]") }
                if (body.isNotBlank()) {
                    val capped = if (body.length > 200) body.take(200) else body
                    append(": ").append(capped)
                }
            }
        }

        /**
         * v1.0.16: 清理共享连接池中所有空闲连接。
         *
         * 应用从后台回到前台时调用,丢弃可能已被系统/路由器关闭的 idle socket,
         * 避免下一次请求复用失效连接导致首次 HTTP 即失败(表现为切后台回来报错)。
         * 正在使用的活跃连接不受影响。
         */
        fun evictIdleConnections() {
            runCatching {
                sharedHttpClient.connectionPool.evictAll()
            }.onFailure {
                io.zer0.common.Logger.w("ProviderHttpSupport", "evictIdleConnections 失败", it)
            }
        }
    }
}
