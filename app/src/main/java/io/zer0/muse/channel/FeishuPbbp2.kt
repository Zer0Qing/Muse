package io.zer0.muse.channel

import java.io.ByteArrayOutputStream

/**
 * v2.0.1: 飞书长连接 pbbp2 帧编解码 — 手写 protobuf 线格式(仅覆盖所用字段)。
 *
 * 帧结构(protobuf 定义):
 * ```
 * message Frame {
 *   uint64 SeqID = 1;      uint64 LogID = 2;
 *   int32 service = 3;     int32 method = 4;     // 0=control 1=data
 *   repeated Header headers = 5;                 // Header{string key=1; string value=2}
 *   string payloadEncoding = 6;  string payloadType = 7;
 *   bytes payload = 8;
 * }
 * ```
 * 参考实现:@larksuiteoapi/node-sdk(WSClient)的协议行为。
 */
internal object FeishuPbbp2 {

    /** 控制帧(ping/pong)。 */
    const val METHOD_CONTROL = 0

    /** 数据帧(事件)。 */
    const val METHOD_DATA = 1

    data class Header(val key: String, val value: String)

    data class Frame(
        val seqId: Long = 0L,
        val logId: Long = 0L,
        val service: Int = 0,
        val method: Int = 0,
        val headers: List<Header> = emptyList(),
        val payloadEncoding: String = "",
        val payloadType: String = "",
        val payload: ByteArray = ByteArray(0),
    ) {
        fun header(key: String): String? = headers.firstOrNull { it.key == key }?.value
    }

    /** 解码一帧(容错:未知字段按 wire type 跳过)。 */
    fun decode(bytes: ByteArray): Frame {
        var pos = 0
        var seqId = 0L
        var logId = 0L
        var service = 0
        var method = 0
        val headers = mutableListOf<Header>()
        var payloadEncoding = ""
        var payloadType = ""
        var payload = ByteArray(0)
        while (pos < bytes.size) {
            val (tag, afterTag) = readVarint(bytes, pos)
            pos = afterTag
            val field = (tag ushr 3).toInt()
            val wire = (tag and 0x7).toInt()
            when (field) {
                1 -> {
                    val (value, next) = readVarint(bytes, pos)
                    seqId = value
                    pos = next
                }
                2 -> {
                    val (value, next) = readVarint(bytes, pos)
                    logId = value
                    pos = next
                }
                3 -> {
                    val (value, next) = readVarint(bytes, pos)
                    service = value.toInt()
                    pos = next
                }
                4 -> {
                    val (value, next) = readVarint(bytes, pos)
                    method = value.toInt()
                    pos = next
                }
                5 -> {
                    val (length, next) = readVarint(bytes, pos)
                    pos = next
                    headers += decodeHeader(bytes, pos, length.toInt())
                    pos += length.toInt()
                }
                6 -> {
                    val (length, next) = readVarint(bytes, pos)
                    pos = next
                    payloadEncoding = String(bytes, pos, length.toInt(), Charsets.UTF_8)
                    pos += length.toInt()
                }
                7 -> {
                    val (length, next) = readVarint(bytes, pos)
                    pos = next
                    payloadType = String(bytes, pos, length.toInt(), Charsets.UTF_8)
                    pos += length.toInt()
                }
                8 -> {
                    val (length, next) = readVarint(bytes, pos)
                    pos = next
                    payload = bytes.copyOfRange(pos, pos + length.toInt())
                    pos += length.toInt()
                }
                else -> pos = skipField(bytes, pos, wire)
            }
        }
        return Frame(
            seqId = seqId,
            logId = logId,
            service = service,
            method = method,
            headers = headers,
            payloadEncoding = payloadEncoding,
            payloadType = payloadType,
            payload = payload,
        )
    }

    /** 编码一帧(用于 ack 回包:保留原帧字段 + 替换 payload)。 */
    fun encode(frame: Frame): ByteArray {
        val out = ByteArrayOutputStream()
        writeVarintField(out, 1, frame.seqId)
        writeVarintField(out, 2, frame.logId)
        writeVarintField(out, 3, frame.service.toLong())
        writeVarintField(out, 4, frame.method.toLong())
        for (header in frame.headers) {
            val body = ByteArrayOutputStream().also { buffer ->
                writeStringField(buffer, 1, header.key)
                writeStringField(buffer, 2, header.value)
            }.toByteArray()
            writeTag(out, 5, 2)
            writeVarint(out, body.size.toLong())
            out.write(body)
        }
        if (frame.payloadEncoding.isNotEmpty()) writeStringField(out, 6, frame.payloadEncoding)
        if (frame.payloadType.isNotEmpty()) writeStringField(out, 7, frame.payloadType)
        if (frame.payload.isNotEmpty()) {
            writeTag(out, 8, 2)
            writeVarint(out, frame.payload.size.toLong())
            out.write(frame.payload)
        }
        return out.toByteArray()
    }

    private fun decodeHeader(bytes: ByteArray, offset: Int, length: Int): Header {
        var pos = offset
        val end = offset + length
        var key = ""
        var value = ""
        while (pos < end) {
            val (tag, afterTag) = readVarint(bytes, pos)
            pos = afterTag
            val field = (tag ushr 3).toInt()
            val wire = (tag and 0x7).toInt()
            when (field) {
                1 -> {
                    val (len, next) = readVarint(bytes, pos)
                    pos = next
                    key = String(bytes, pos, len.toInt(), Charsets.UTF_8)
                    pos += len.toInt()
                }
                2 -> {
                    val (len, next) = readVarint(bytes, pos)
                    pos = next
                    value = String(bytes, pos, len.toInt(), Charsets.UTF_8)
                    pos += len.toInt()
                }
                else -> pos = skipField(bytes, pos, wire)
            }
        }
        return Header(key, value)
    }

    // ---- protobuf 低层读写 ----

    private fun readVarint(bytes: ByteArray, start: Int): Pair<Long, Int> {
        var result = 0L
        var shift = 0
        var pos = start
        while (pos < bytes.size) {
            val b = bytes[pos].toInt() and 0xFF
            result = result or ((b and 0x7F).toLong() shl shift)
            pos++
            if (b and 0x80 == 0) break
            shift += 7
            if (shift > 63) break
        }
        return result to pos
    }

    private fun skipField(bytes: ByteArray, pos: Int, wire: Int): Int = when (wire) {
        0 -> readVarint(bytes, pos).second
        1 -> pos + 8
        2 -> {
            val (length, next) = readVarint(bytes, pos)
            next + length.toInt()
        }
        5 -> pos + 4
        else -> bytes.size
    }

    private fun writeTag(out: ByteArrayOutputStream, field: Int, wire: Int) {
        writeVarint(out, ((field shl 3) or wire).toLong())
    }

    private fun writeVarint(out: ByteArrayOutputStream, value: Long) {
        var v = value
        while (true) {
            if (v and 0x7FL.inv() == 0L) {
                out.write(v.toInt())
                return
            }
            out.write(((v and 0x7F) or 0x80).toInt())
            v = v ushr 7
        }
    }

    private fun writeVarintField(out: ByteArrayOutputStream, field: Int, value: Long) {
        writeTag(out, field, 0)
        writeVarint(out, value)
    }

    private fun writeStringField(out: ByteArrayOutputStream, field: Int, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        writeTag(out, field, 2)
        writeVarint(out, bytes.size.toLong())
        out.write(bytes)
    }
}
