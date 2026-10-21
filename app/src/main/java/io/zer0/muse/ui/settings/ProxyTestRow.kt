package io.zer0.muse.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.zer0.common.Logger
import io.zer0.muse.R
import io.zer0.muse.data.ProxyConfig
import io.zer0.muse.ui.common.form.IosCapsuleButtonVariant
import io.zer0.muse.ui.common.form.MuseCapsuleButton
import io.zer0.muse.ui.theme.statusColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * v2.6.9: 代理连接测试。
 *
 * 为什么需要它：
 * 以前配完代理完全没有反馈。用户只能回到对话里「发一条试试」，而失败时
 * 到底是代理没通、API Key 错、还是上游拦截，完全分不清。
 * 更糟的是代理配置有「需重启生效」的约束，用户很容易在旧 client 上测出
 * 假阴性，然后去改一堆无关配置。
 *
 * 设计：
 *  - 用**当前表单里的值**现建一个临时 client 去测，不受已保存配置与重启约束影响
 *  - 探针地址选对代理敏感、响应极小的端点（gstatic 204），不走任何需要鉴权的服务
 *  - 明确区分「不走代理直连成功但走代理失败」「连接超时」「代理认证失败」
 */
@Composable
internal fun ProxyTestRow(proxyConfig: ProxyConfig) {
    var running by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<TestOutcome?>(null) }
    val scope = rememberCoroutineScope()
    val ctx = androidx.compose.ui.platform.LocalContext.current

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MuseCapsuleButton(
                text = if (running) {
                    stringResource(R.string.proxy_test_running)
                } else {
                    stringResource(R.string.proxy_test_action)
                },
                onClick = {
                    if (!running) {
                        running = true
                        result = null
                        scope.launch {
                            result = withContext(Dispatchers.IO) {
                                runProbe(
                                    proxyConfig,
                                    ProbeTexts(
                                        invalidConfig = ctx.getString(R.string.proxy_test_invalid_config),
                                        connected = ctx.getString(R.string.proxy_test_connected),
                                        needAuth = ctx.getString(R.string.proxy_test_need_auth),
                                        reachableFailed = ctx.getString(R.string.proxy_test_reachable_failed),
                                        timeout = ctx.getString(R.string.proxy_test_timeout),
                                        dnsFailed = ctx.getString(R.string.proxy_test_dns_failed),
                                        connectFailed = ctx.getString(R.string.proxy_test_connect_failed),
                                        unexpected = ctx.getString(R.string.proxy_test_unexpected),
                                    ),
                                )
                            }
                            running = false
                        }
                    }
                },
                variant = IosCapsuleButtonVariant.Secondary,
                fillWidth = false,
            )
        }
        result?.let { outcome ->
            Text(
                text = outcome.message,
                style = MaterialTheme.typography.bodySmall,
                color = if (outcome.ok) {
                    MaterialTheme.statusColors.success
                } else {
                    MaterialTheme.colorScheme.error
                },
                modifier = Modifier.padding(top = 8.dp, start = 4.dp, end = 4.dp),
            )
        }
    }
}

private data class TestOutcome(val ok: Boolean, val message: String)

/** v2.6.9: 探测用的文案集合。用数据类承载，避免 runProbe 参数膨胀。 */
private data class ProbeTexts(
    val invalidConfig: String,
    val connected: String,
    val needAuth: String,
    val reachableFailed: String,
    val timeout: String,
    val dnsFailed: String,
    val connectFailed: String,
    val unexpected: String,
)

/**
 * 探针端点：gstatic 的 generate_204。
 * 选它的理由：任何网络都能访问、响应体为空、不需要鉴权、不会被当成滥用流量。
 */
private const val TAG = "ProxyTestRow"

private const val PROBE_URL = "https://www.gstatic.com/generate_204"

private fun runProbe(config: ProxyConfig, texts: ProbeTexts): TestOutcome {
    if (!config.enabled || config.host.isBlank() || config.port !in 1..65535) {
        return TestOutcome(false, texts.invalidConfig)
    }
    return try {
        val builder = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .callTimeout(12, TimeUnit.SECONDS)
        // v2.6.9: 直接用表单值装配，不走全局 ProxyProvider。
        // 旧写法是临时 set 全局状态再还原，并发时会把正在发请求的 client 带偏——
        // 测试按钮只该影响自己这一条探针。
        val address = java.net.InetSocketAddress.createUnresolved(config.host.trim(), config.port)
        val proxy = when (config.type.uppercase()) {
            "SOCKS", "SOCKS5" -> java.net.Proxy(java.net.Proxy.Type.SOCKS, address)
            else -> java.net.Proxy(java.net.Proxy.Type.HTTP, address)
        }
        builder.proxy(proxy)
        if (config.username.isNotBlank() && config.password.isNotBlank()) {
            val credential = okhttp3.Credentials.basic(config.username, config.password)
            builder.proxyAuthenticator { _, response ->
                if (response.request.header("Proxy-Authorization") != null) {
                    null
                } else {
                    response.request.newBuilder()
                        .header("Proxy-Authorization", credential)
                        .build()
                }
            }
        }
        val client = builder.build()
        val started = System.currentTimeMillis()
        client.newCall(Request.Builder().url(PROBE_URL).get().build()).execute().use { resp ->
            val ms = System.currentTimeMillis() - started
            TestOutcome(
                ok = resp.code in 200..399,
                message = when {
                    resp.code in 200..399 -> "$texts.connected${ms}ms (HTTP ${resp.code})"
                    resp.code == 407 -> texts.needAuth
                    else -> "$texts.reachableFailed${resp.code}"
                },
            )
        }
    } catch (e: java.net.SocketTimeoutException) {
        Logger.w(TAG, "代理探测超时: ${e.message}")
        TestOutcome(false, "${texts.timeout} (${e.message ?: e::class.simpleName})")
    } catch (e: java.net.UnknownHostException) {
        Logger.w(TAG, "代理探测 DNS 失败: ${e.message}")
        TestOutcome(false, "${texts.dnsFailed} (${e.message ?: e::class.simpleName})")
    } catch (e: java.io.IOException) {
        Logger.w(TAG, "代理探测连接失败: ${e.message}")
        TestOutcome(false, "${texts.connectFailed}${e.message ?: e::class.simpleName}")
    } catch (e: kotlinx.coroutines.CancellationException) {
        // v2.6.9（真 bug 修复）: CancellationException 的继承链是
        //   IllegalStateException -> RuntimeException -> Exception
        // 原来那句 catch(RuntimeException) 会把协程取消当错误咽掉，导致
        // 界面已关闭但后台仍在探测、取消语义丢失。项目里 M-OAI3 已因同类
        // 问题把 runCatching 换成 resultOf，这里必须显式重抛。
        // 注：JVM 下 kotlinx 与 java.util.concurrent 的 CancellationException 是同一个
        // 类型（typealias），因此只需一个分支。
        throw e
    } catch (e: IllegalArgumentException) {
        // v2.6.9: 只捕具体类型。探针里可能抛 IllegalArgumentException 的点是
        // URI 构造与 JSON 解析（SerializationException 即其子类）。
        Logger.w(TAG, "代理探测参数异常: ${e.message}", e)
        TestOutcome(false, "${texts.unexpected}${e.message ?: e::class.simpleName}")
    }
}
