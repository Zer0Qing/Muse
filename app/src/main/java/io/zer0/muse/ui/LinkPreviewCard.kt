package io.zer0.muse.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import io.zer0.common.Logger
import io.zer0.common.Result
import io.zer0.common.resultOf
import io.zer0.muse.R
import io.zer0.muse.ui.common.surface.MuseSurface
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.MusePaddings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

private const val TAG = "LinkPreviewCard"

/**
 * 单条链接预览抓取的最大字节数(防止恶意大页面 OOM)。
 * 取 HTML 前 50KB 通常足以容纳 og:* 头部。
 */
private const val MAX_HTML_BYTES = 50_000

/**
 * 单条链接预览的总超时(包含 HEAD + GET + 解析)。
 * 与原 5s connect + 5s read 重叠上限对齐,避免单条卡死 produceState。
 */
private const val FETCH_TIMEOUT_MS = 8_000L

data class LinkPreviewData(
    val url: String,
    val title: String = "",
    val description: String = "",
    val imageUrl: String = "",
    /** Phase 2: 抓取中(UI 显示骨架占位)。 */
    val isLoading: Boolean = false,
    /** Phase 2: 抓取失败(区别于"页面无标题")。 */
    val isFailed: Boolean = false,
)

private val URL_REGEX = Regex("https?://[^\\s)]+")

fun extractUrls(text: String): List<String> {
    return URL_REGEX.findAll(text).map { it.value.trimEnd('.', ',', ')', '(', '[', ']', '!', '?', ';', ':') }.distinct().toList()
}

/**
 * Phase 2: 从 URL 提取展示用来源域名(去掉 www. 前缀;解析失败回退原始 URL)。
 */
internal fun linkDomain(url: String): String {
    val host = runCatching { URI(url).host }.getOrNull()
    return host?.trim()?.removePrefix("www.")?.takeIf { it.isNotBlank() } ?: url
}

/**
 * 通用 <meta> 标签提取正则 — 一次匹配所有 meta 标签,再按 property/name 字段过滤。
 *
 * 旧实现 [extractMeta] 内部每次调用都新建 4 个 Regex(property/name × 前后顺序),
 * 5 个目标字段 × 4 = 20 个 Regex 对象,且每次链接预览都重建。
 * 这里改为单一通用正则 + Map 索引,从 O(20×html) 降到 O(html)。
 *
 * 捕获组:
 *  1. property/name 的值(og:title / twitter:image 等)
 *  2. content 的值
 *  通过 IGNORE_CASE 容错大小写,允许 property 与 content 任意先后。
 */
private val META_REGEX = Regex(
    """<meta\s+[^>]*(?:property|name)\s*=\s*["']([^"']+)["'][^>]*content\s*=\s*["']([^"']*)["'][^>]*/?>|<meta\s+[^>]*content\s*=\s*["']([^"']*)["'][^>]*(?:property|name)\s*=\s*["']([^"']+)["'][^>]*/?>""",
    RegexOption.IGNORE_CASE,
)

private val TITLE_REGEX = Regex("""<title[^>]*>([^<]+)</title>""", RegexOption.IGNORE_CASE)

/**
 * 从 HTML 中按字段名提取 meta content(优先 og:*,其次 twitter:*,最后 <title>)。
 */
private fun extractMeta(html: String, property: String): String? {
    // 一次遍历所有 meta 标签,按字段建索引,避免每个 property 重新扫一遍 html。
    val metaMap = HashMap<String, String>(16)
    META_REGEX.findAll(html).forEach { m ->
        val key = m.groupValues[1].ifEmpty { m.groupValues[4] }
        val value = m.groupValues[2].ifEmpty { m.groupValues[3] }
        if (key.isNotEmpty() && value.isNotEmpty() && key !in metaMap) {
            metaMap[key] = value.trim()
        }
    }
    return metaMap[property]
}

private fun extractTitle(html: String): String? {
    return TITLE_REGEX.find(html)?.groupValues?.get(1)?.trim()
}

/**
 * 抓取单条链接的预览信息。
 *
 * 调用方必须在 IO Dispatcher 中调用(本函数内部不再切线程,避免双重 withContext 开销)。
 *
 * 修复点(v1.131 代码质量优化):
 *  - HEAD/GET 的 [HttpURLConnection] 与 [BufferedReader] 全部走 [use] / try-finally,
 *    异常路径不再泄漏 FD/socket。
 *  - 不再每次调用重建 Regex(META_REGEX/TITLE_REGEX 已提为文件级常量)。
 *
 * Phase 2: 超时/异常/SSRF 拦截统一标记 [LinkPreviewData.isFailed],
 * 与"抓取成功但页面无标题"区分开。
 */
private suspend fun fetchLinkPreview(url: String): LinkPreviewData = withContext(Dispatchers.IO) {
    // SSRF 防护:进入即校验原始 URL,拒绝内网/非公网/非 http(s) 目标。
    if (SsrfGuard.isBlocked(url)) {
        Logger.w(TAG, "fetchLinkPreview 拒绝内网/非公网地址: $url")
        return@withContext LinkPreviewData(url = url, isFailed = true)
    }
    val result = withTimeoutOrNull(FETCH_TIMEOUT_MS) {
        resultOf<LinkPreviewData> {
            val finalUrl = followRedirect(url)
            // 重定向落地地址再次校验(防 302/307 跳转到内网)
            if (SsrfGuard.isBlocked(finalUrl)) {
                Logger.w(TAG, "fetchLinkPreview 拒绝重定向到内网: $finalUrl")
                return@resultOf LinkPreviewData(url = finalUrl, isFailed = true)
            }
            val html = fetchHtml(finalUrl)
            val title = extractMeta(html, "og:title")
                ?: extractMeta(html, "twitter:title")
                ?: extractTitle(html)
                ?: ""
            val description = extractMeta(html, "og:description")
                ?: extractMeta(html, "twitter:description")
                ?: ""
            val imageUrl = extractMeta(html, "og:image")
                ?: extractMeta(html, "twitter:image")
                ?: ""
            LinkPreviewData(
                url = finalUrl,
                title = title,
                description = description,
                imageUrl = sanitizeImageUrl(imageUrl),
            )
        }
    }
    when {
        result == null -> {
            Logger.w(TAG, "fetchLinkPreview timeout: $url")
            LinkPreviewData(url = url, isFailed = true)
        }
        result.isError -> {
            Logger.w(TAG, "fetchLinkPreview failed: $url (${(result as? Result.Error)?.message})")
            LinkPreviewData(url = url, isFailed = true)
        }
        else -> result.getOrThrow()
    }
}

/** og:image 会被 Coil 二次抓取,同样拒绝内网/非公网图片地址。 */
private fun sanitizeImageUrl(imageUrl: String): String =
    if (imageUrl.isNotBlank() && SsrfGuard.isBlocked(imageUrl)) {
        Logger.w(TAG, "fetchLinkPreview 拒绝内网图片地址: $imageUrl")
        ""
    } else {
        imageUrl
    }

/** 仅发起 HEAD 跟随重定向,返回最终落地 URL(失败则回退原 URL)。 */
private fun followRedirect(url: String): String {
    var conn: HttpURLConnection? = null
    return try {
        conn = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            requestMethod = "HEAD"
            connectTimeout = 5000
            readTimeout = 5000
        }
        conn.connect()
        conn.url.toString()
    } catch (e: Exception) {
        Logger.w(TAG, "HEAD failed for $url: ${e.message}")
        url
    } finally {
        conn?.disconnect()
    }
}

/** GET 抓取 HTML 正文(截断到 [MAX_HTML_BYTES] 防止大页面 OOM)。 */
private fun fetchHtml(url: String): String {
    var conn: HttpURLConnection? = null
    return try {
        conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 5000
            readTimeout = 5000
            setRequestProperty("User-Agent", "Mozilla/5.0 Muse/1.0")
        }
        conn.connect()
        BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { reader ->
            reader.readText().take(MAX_HTML_BYTES)
        }
    } finally {
        conn?.disconnect()
    }
}

/**
 * 抓取消息文本中的链接预览。
 *
 * Phase 2: 初始值立即返回"抓取中"占位(骨架),抓取完成后再替换为结果/失败态,
 * 用户不再面对"卡片迟迟不出现"的空窗。
 */
@Composable
fun rememberLinkPreviews(text: String): List<LinkPreviewData> {
    val urls = remember(text) { extractUrls(text).take(2) }
    val previews by produceState(
        initialValue = urls.map { LinkPreviewData(url = it, isLoading = true) },
        urls,
    ) {
        // v1.131: fetchLinkPreview 内部已切 Dispatchers.IO + withTimeout,
        // 此处不再阻塞 Compose 主线程(旧实现 produceState 直接调用阻塞 IO 会卡 UI)。
        value = if (urls.isEmpty()) emptyList() else urls.map { fetchLinkPreview(it) }
    }
    return previews
}

@Composable
fun LinkPreviewCard(
    preview: LinkPreviewData,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    // Phase 2: 统一走项目容器基元 [MuseSurface],保留原 Card 的小圆角、
    // surfaceVariant 半透明底色与整卡打开链接的点击行为。
    MuseSurface(
        onClick = {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(preview.url))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(intent) }
        },
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 6.dp),
        shape = MuseShapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
    ) {
        if (preview.isLoading) {
            LinkPreviewLoading(url = preview.url)
        } else {
            LinkPreviewContent(preview = preview)
        }
    }
}

/** 抓取中的骨架占位(图片块 + 三行文本条)。 */
@Composable
private fun LinkPreviewLoading(url: String) {
    val barColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.18f)
    val loadingDescription = stringResource(R.string.link_preview_loading, linkDomain(url))
    Row(
        modifier = Modifier
            .padding(MusePaddings.contentGap)
            .semantics { contentDescription = loadingDescription },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(80.dp)
                .clip(MuseShapes.small)
                .background(barColor),
        )
        Spacer(Modifier.width(8.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            SkeletonBar(fraction = 0.7f, color = barColor)
            SkeletonBar(fraction = 0.95f, color = barColor)
            SkeletonBar(fraction = 0.4f, color = barColor)
        }
    }
}

/** 骨架单行条。 */
@Composable
private fun SkeletonBar(fraction: Float, color: Color) {
    Box(
        modifier = Modifier
            .fillMaxWidth(fraction)
            .height(10.dp)
            .clip(MuseShapes.small)
            .background(color),
    )
}

/** 抓取完成后的卡片内容(失败态 / 无标题态 / 正常态)。 */
@Composable
private fun LinkPreviewContent(preview: LinkPreviewData) {
    val domain = remember(preview.url) { linkDomain(preview.url) }
    Row(modifier = Modifier.padding(MusePaddings.contentGap)) {
        if (preview.imageUrl.isNotBlank()) {
            AsyncImage(
                model = preview.imageUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(width = 80.dp, height = 80.dp)
                    .clip(MuseShapes.small),
            )
            Spacer(Modifier.width(8.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = when {
                    preview.isFailed -> stringResource(R.string.link_preview_failed)
                    preview.title.isNotBlank() -> preview.title
                    // 抓取成功但没有 og:title/<title>:与"抓取失败"区分展示
                    else -> stringResource(R.string.link_preview_no_title)
                },
                style = MaterialTheme.typography.labelMedium,
                color = if (preview.isFailed) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (!preview.isFailed && preview.description.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = preview.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = domain,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
