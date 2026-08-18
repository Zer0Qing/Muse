package io.zer0.muse.data.`import`

import android.content.Context
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.data.session.MuseDb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * v1.0.74: 小手机子页数据加载器。
 * - AI 相册: 聚合 messages 表里 assistant 消息的图片(AI 生成图)
 * - 日记本: 今天的记忆片段(FactStore)
 */
object MiniDataLoader {

    private const val TAG = "MiniDataLoader"

    /** 聚合 AI 生成图片,并保留图片所在消息的生成时间。 */
    suspend fun loadAiGeneratedImages(context: Context): List<MiniAlbumImage> = withContext(Dispatchers.IO) {
        resultOf {
            val db: MuseDb = org.koin.java.KoinJavaComponent.get(MuseDb::class.java)
            val msgs = db.messageDao().getAllAssistantWithImages(200)
            val images = mutableListOf<MiniAlbumImage>()
            msgs.forEach { msg ->
                // imageUrlsJson 反序列化
                val parsed = runCatching {
                    io.zer0.common.AppJson.parseToJsonElement(msg.imageUrlsJson)
                        .jsonArray
                        .mapNotNull { it.jsonPrimitive.contentOrNull }
                }.getOrDefault(emptyList())
                parsed.filter { it.isNotBlank() }.forEach { uri ->
                    images += MiniAlbumImage(uri = uri, createdAt = msg.createdAt)
                }
                // base64 图片转 data URI
                val b64 = runCatching {
                    io.zer0.common.AppJson.parseToJsonElement(msg.imageBase64Json)
                        .jsonArray
                        .mapNotNull { it.jsonPrimitive.contentOrNull }
                }.getOrDefault(emptyList())
                b64.filter { it.isNotBlank() }.forEach { value ->
                    images += MiniAlbumImage(
                        uri = "data:image/jpeg;base64,$value",
                        createdAt = msg.createdAt,
                    )
                }
            }
            // 去重保序
            images.distinctBy { it.uri }
        }.onError { msg, t -> Logger.w(TAG, "加载 AI 图片失败: ${t?.message ?: msg}") }
            .getOrNull() ?: emptyList()
    }

    /** 今天的记忆片段(FactStore 按天过滤)。 */
    suspend fun loadTodayMemories(context: Context): List<String> = withContext(Dispatchers.IO) {
        resultOf {
            val factStore: io.zer0.memory.fact.FactStore = org.koin.java.KoinJavaComponent.get(io.zer0.memory.fact.FactStore::class.java)
            val facts = factStore.getAll("main")
            val todayStart = java.time.LocalDate.now()
                .atStartOfDay(java.time.ZoneId.systemDefault())
                .toInstant().toEpochMilli()
            // Fact.createdAt 是 ISO 字符串,解析为毫秒后按天过滤
            facts.filter { fact ->
                val ts = runCatching {
                    java.time.Instant.parse(fact.createdAt).toEpochMilli()
                }.getOrDefault(Long.MAX_VALUE)
                ts >= todayStart && ts != Long.MAX_VALUE
            }
                .sortedByDescending { runCatching { java.time.Instant.parse(it.createdAt).toEpochMilli() }.getOrDefault(0L) }
                .take(20)
                .map { it.fact }
        }.onError { msg, t -> Logger.w(TAG, "加载今日记忆失败: ${t?.message ?: msg}") }
            .getOrNull() ?: emptyList()
    }
}

/** AI 相册中的一张图片及其来源消息时间。 */
data class MiniAlbumImage(
    val uri: String,
    val createdAt: Long,
) {
    /** 本地收藏/隐藏状态使用的稳定 id,不把超长 data URI 直接写入 DataStore。 */
    val id: String get() = uri.hashCode().toString()
}
