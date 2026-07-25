package io.zer0.muse.data.assistant

import io.zer0.common.AppJson
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import java.util.zip.CRC32

/**
 * SillyTavern 角色卡 V2 规范 (chara_card_v2, spec 2.10)。
 *
 * SillyTavern 角色卡是业内通用格式:PNG 图片元数据 (tEXt chunk, key="chara")
 * 或 JSON 文件存储完整角色定义。支持把其他 App 创建的角色卡导入 Muse,
 * 也能把 Muse 助手导出为角色卡分享给其他 App。
 *
 * V2 卡片结构:
 *   {
 *     "spec": "chara_card_v2",
 *     "spec_version": "2.10",
 *     "data": { ... }
 *   }
 *
 * V1 卡片为扁平结构 (无 spec/data 包裹),仅含 name/description/personality/
 * scenario/first_mes/mes_example 等字段。导入时由 [CharacterCardImporter] 自动识别 V1/V2。
 *
 * 参考实现:
 *   - rikkahub AssistantImporter.kt
 *   - openhanako character-cards/service.ts
 */
@Serializable
data class SillyTavernCardV2(
    val spec: String = "chara_card_v2",
    @SerialName("spec_version")
    val specVersion: String = "2.10",
    val data: SillyTavernCardData,
) {

    /**
     * 把角色卡转换为 Muse 助手实体 (id/时间戳留空,由调用方补全)。
     *
     * 字段映射策略:
     *  - name → name
     *  - system_prompt 优先;为空时用 description + personality + scenario 拼接 → systemPrompt
     *  - first_mes → messageTemplate (用作开场白模板)
     *  - post_history_instructions → 拼到 systemPrompt 末尾
     *  - tags → tagsJson
     *  - mes_example / alternate_greetings / creator_notes / extensions → 丢弃 (Muse 无对应字段)
     */
    fun toAssistantEntity(): AssistantEntity {
        val parts = mutableListOf<String>()
        val sp = data.systemPrompt.trim()
        if (sp.isNotEmpty()) {
            parts += sp
        } else {
            if (data.description.isNotBlank()) parts += data.description
            if (data.personality.isNotBlank()) parts += "性格特点: ${data.personality}"
            if (data.scenario.isNotBlank()) parts += "场景设定: ${data.scenario}"
        }
        if (data.postHistoryInstructions.isNotBlank()) {
            parts += "后置指令: ${data.postHistoryInstructions}"
        }
        val combined = parts.joinToString("\n\n")
        return AssistantEntity(
            id = "",
            name = data.name.ifBlank { "导入角色" },
            systemPrompt = combined,
            messageTemplate = data.firstMes,
            tagsJson = serializeStringListSafe(data.tags),
        )
    }

    companion object {
        /**
         * 从 Muse 助手实体构造 SillyTavern V2 角色卡。
         *
         * 字段映射:
         *  - name → name
         *  - systemPrompt → description + system_prompt (双写, 兼容性最好)
         *  - messageTemplate → first_mes
         *  - tagsJson → tags
         *  - id → extensions.muse_id (往返用)
         *  - avatarEmoji → extensions.muse_avatar_emoji (往返用)
         */
        fun fromAssistantEntity(a: AssistantEntity): SillyTavernCardV2 {
            val tags = parseStringListSafe(a.tagsJson)
            return SillyTavernCardV2(
                data = SillyTavernCardData(
                    name = a.name,
                    description = a.systemPrompt,
                    systemPrompt = a.systemPrompt,
                    firstMes = a.messageTemplate,
                    tags = tags,
                    creator = "Muse",
                    characterVersion = "1.0",
                    extensions = buildMap {
                        if (a.id.isNotBlank()) put("muse_id", JsonPrimitive(a.id))
                        if (a.avatarEmoji.isNotBlank()) put("muse_avatar_emoji", JsonPrimitive(a.avatarEmoji))
                    },
                ),
            )
        }
    }
}

/**
 * SillyTavern 角色卡 data 字段 (V2 spec)。
 *
 * V1 兼容字段:name / description / personality / scenario / first_mes / mes_example。
 * V2 新增字段:creator_notes / system_prompt / post_history_instructions / tags /
 * creator / character_version / alternate_greetings / extensions。
 */
@Serializable
data class SillyTavernCardData(
    val name: String = "",
    val description: String = "",
    val personality: String = "",
    val scenario: String = "",
    @SerialName("first_mes") val firstMes: String = "",
    @SerialName("mes_example") val mesExample: String = "",
    @SerialName("creator_notes") val creatorNotes: String = "",
    @SerialName("system_prompt") val systemPrompt: String = "",
    @SerialName("post_history_instructions") val postHistoryInstructions: String = "",
    val tags: List<String> = emptyList(),
    val creator: String = "",
    @SerialName("character_version") val characterVersion: String = "",
    @SerialName("alternate_greetings") val alternateGreetings: List<String> = emptyList(),
    val extensions: Map<String, JsonElement> = emptyMap(),
)

// ── 内部工具 ──

/** 把字符串列表序列化为 JSON (与 AssistantRepository.serializeStringList 一致)。 */
internal fun serializeStringListSafe(list: List<String>): String =
    AppJson.encodeToString(ListSerializer(String.serializer()), list)

/** 安全解析 JSON 字符串列表, 失败返回空列表。 */
internal fun parseStringListSafe(json: String): List<String> {
    if (json.isBlank() || json == "[]") return emptyList()
    return runCatching {
        AppJson.decodeFromString(ListSerializer(String.serializer()), json)
    }.getOrDefault(emptyList())
}

/**
 * PNG chunk 手动解析/写入工具 (不引入第三方 PNG 库)。
 *
 * PNG 文件结构:
 *   signature(8) + chunk* (length(4) + type(4) + data(length) + crc(4))
 *
 * tEXt chunk data 格式: keyword(1-79 bytes Latin-1) + 0x00 + text(Latin-1)
 * SillyTavern 约定: keyword="chara", text=base64(JSON)
 *
 * CRC-32 覆盖 type + data, 用 [CRC32] 计算。
 */
internal object PngChunkUtil {

    private val PNG_SIGNATURE = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
    )

    /**
     * 从 PNG 字节中读取指定 [keyword] 的 tEXt chunk 文本。
     *
     * @return 命中返回 text (Latin-1 解码); 未找到或非 PNG 返回 null
     */
    fun readTextChunk(bytes: ByteArray, keyword: String): String? {
        // 校验签名
        if (bytes.size < PNG_SIGNATURE.size + 8) return null
        for (i in PNG_SIGNATURE.indices) {
            if (bytes[i] != PNG_SIGNATURE[i]) return null
        }
        var pos = PNG_SIGNATURE.size
        while (pos + 8 <= bytes.size) {
            val length = readUInt32BE(bytes, pos)
            val type = String(bytes, pos + 4, 4, Charsets.US_ASCII)
            val dataStart = pos + 8
            val dataEnd = dataStart + length.toInt()
            if (dataEnd + 4 > bytes.size) break  // 越界, 损坏文件
            if (type == "tEXt") {
                val data = bytes.copyOfRange(dataStart, dataEnd)
                val sepIndex = data.indexOf(0)
                if (sepIndex > 0) {
                    val kw = String(data, 0, sepIndex, Charsets.ISO_8859_1)
                    if (kw == keyword) {
                        return String(data, sepIndex + 1, data.size - sepIndex - 1, Charsets.ISO_8859_1)
                    }
                }
            }
            pos = dataEnd + 4  // 跳过 CRC
            if (type == "IEND") break
        }
        return null
    }

    /**
     * 在 PNG 字节中插入 tEXt chunk (插入到 IHDR 之后, 符合规范推荐位置)。
     *
     * 不检查是否已存在同 keyword 的 tEXt (调用方负责)。
     * 若 [bytes] 非 PNG 则原样返回。
     */
    fun writeTextChunk(bytes: ByteArray, keyword: String, text: String): ByteArray {
        // 校验签名
        if (bytes.size < PNG_SIGNATURE.size) return bytes
        for (i in PNG_SIGNATURE.indices) {
            if (bytes[i] != PNG_SIGNATURE[i]) return bytes
        }
        // 构造 tEXt chunk data: keyword + 0x00 + text
        val keywordBytes = keyword.toByteArray(Charsets.ISO_8859_1)
        require(keywordBytes.size in 1..79) { "tEXt keyword 长度须在 1..79" }
        val textBytes = text.toByteArray(Charsets.ISO_8859_1)
        val chunkData = ByteArray(keywordBytes.size + 1 + textBytes.size)
        System.arraycopy(keywordBytes, 0, chunkData, 0, keywordBytes.size)
        chunkData[keywordBytes.size] = 0
        System.arraycopy(textBytes, 0, chunkData, keywordBytes.size + 1, textBytes.size)
        // CRC 覆盖 type + data
        val crc = CRC32().apply {
            update("tEXt".toByteArray(Charsets.US_ASCII))
            update(chunkData)
        }.value
        // 拼接 chunk: length(4) + type(4) + data + crc(4)
        val chunk = ByteArray(8 + chunkData.size + 4)
        writeUInt32BE(chunk, 0, chunkData.size.toLong())
        "tEXt".toByteArray(Charsets.US_ASCII).copyInto(chunk, 4)
        chunkData.copyInto(chunk, 8)
        writeUInt32BE(chunk, 8 + chunkData.size, crc)
        // 第一个 chunk 必须是 IHDR; 读其长度确定结束位置
        val sigEnd = PNG_SIGNATURE.size
        if (sigEnd + 8 > bytes.size) return bytes
        val ihdrType = String(bytes, sigEnd + 4, 4, Charsets.US_ASCII)
        if (ihdrType != "IHDR") return bytes  // 异常 PNG
        val ihdrLen = readUInt32BE(bytes, sigEnd).toInt()
        val ihdrEnd = sigEnd + 8 + ihdrLen + 4  // length + type + data + crc
        if (ihdrEnd > bytes.size) return bytes
        val before = bytes.copyOfRange(0, ihdrEnd)
        val after = bytes.copyOfRange(ihdrEnd, bytes.size)
        return before + chunk + after
    }

    // ── 字节序工具 ──

    private fun readUInt32BE(bytes: ByteArray, offset: Int): Long =
        ((bytes[offset].toLong() and 0xFF) shl 24) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 8) or
            (bytes[offset + 3].toLong() and 0xFF)

    private fun writeUInt32BE(buf: ByteArray, offset: Int, value: Long) {
        buf[offset] = ((value ushr 24) and 0xFF).toByte()
        buf[offset + 1] = ((value ushr 16) and 0xFF).toByte()
        buf[offset + 2] = ((value ushr 8) and 0xFF).toByte()
        buf[offset + 3] = (value and 0xFF).toByte()
    }
}
