package io.zer0.muse.data.experience

import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.memory.experience.ExperienceEntry
import io.zer0.memory.experience.ExperienceRecordInput
import io.zer0.memory.experience.ExperienceRecordMetadata
import io.zer0.memory.experience.ExperienceRepository
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.time.Instant

/**
 * 旧经验表（Room）→ 经验库文件存储的一次性导入适配器。
 *
 * 为什么留在这层：统一数据源之后，生产链路只读经验库，旧表不再被任何 prompt/UI 查询；
 * 但存量用户已经手记的经验必须原样搬过去，否则升级一次就"经验全没了"。
 *
 * 幂等由两层保证：
 *  - `hasRun` 标志让正常路径每次启动只扫一遍；
 *  - 经验库按 (assistantId, category, content) 去重，标志丢失重跑也不会写出重复条目。
 * 逐行失败时不写标志，下次启动继续补导入 —— 宁可多扫一次，也不要静默丢掉半截数据。
 */
class ExperienceLegacyImporter(
    private val legacy: ExperienceDao,
    private val target: ExperienceRepository,
    private val hasRun: () -> Boolean,
    private val markRun: () -> Unit,
    /** 旧表没有助手维度，全部归到默认助手；跨助手隔离从导入之后才开始生效。 */
    private val assistantId: String = DEFAULT_ASSISTANT_ID,
) {

    /** 导入结果计数；供日志与测试断言，不驱动任何 UI。 */
    data class Summary(val imported: Int, val skipped: Int, val failed: Int)

    /** 已跑过返回 null（表示这次什么都没做）。 */
    suspend fun importOnce(): Summary? {
        if (hasRun()) return null
        val rows = legacy.getAll()
        var imported = 0
        var skipped = 0
        var failed = 0
        rows.forEach { row ->
            val content = row.content.trim()
            if (content.isEmpty()) {
                skipped += 1
                return@forEach
            }
            val outcome = resultOf {
                target.recordAt(
                    ExperienceRecordInput(
                        assistantId = assistantId,
                        category = safeCategory(row.category),
                        content = content,
                        metadata = ExperienceRecordMetadata(
                            title = row.title.trim(),
                            tags = parseTags(row.tagsJson),
                            source = ExperienceEntry.SOURCE_IMPORTED,
                            sessionId = row.sessionId,
                            createdAt = toIso(row.createdAt),
                            updatedAt = toIso(row.updatedAt),
                        ),
                    ),
                )
            }
            outcome.onSuccess { imported += 1 }
                .onError { _, error ->
                    failed += 1
                    Logger.w(TAG, "经验导入失败 id=${row.id}: ${error?.javaClass?.simpleName}")
                }
        }
        if (failed == 0) markRun()
        Logger.i(TAG, "经验库导入结束: imported=$imported skipped=$skipped failed=$failed")
        return Summary(imported = imported, skipped = skipped, failed = failed)
    }

    companion object {
        private const val TAG = "ExperienceLegacyImporter"
        const val DEFAULT_ASSISTANT_ID = "default"

        /** 旧表 category 是用户可编辑文本，含路径分隔符时必须擦掉再入库，不然文件存储会拒绝整条。 */
        internal fun safeCategory(raw: String?): String {
            val cleaned = raw.orEmpty().trim().replace('/', '_').replace('\\', '_').trim()
            return if (cleaned.isEmpty() || cleaned == "." || cleaned == "..") {
                DEFAULT_EXPERIENCE_CATEGORY
            } else {
                cleaned
            }
        }

        /** 旧表把标签存成 JSON 数组字符串；解析失败按无标签处理，不阻塞导入。 */
        internal fun parseTags(tagsJson: String?): List<String> {
            val raw = tagsJson?.trim()
            if (raw.isNullOrEmpty() || raw == "[]") return emptyList()
            return runCatching {
                (AppJson.parseToJsonElement(raw) as? JsonArray)
                    ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim() }
                    ?.filter { it.isNotEmpty() }
                    .orEmpty()
            }.getOrDefault(emptyList())
        }

        /** 毫秒时间戳转 ISO；越界值退回当前时间，绝不用 0 冒充 1970。 */
        internal fun toIso(millis: Long): String = runCatching { Instant.ofEpochMilli(millis).toString() }
            .getOrDefault(Instant.now().toString())
    }
}
