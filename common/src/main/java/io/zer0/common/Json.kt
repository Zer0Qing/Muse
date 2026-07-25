package io.zer0.common

import kotlinx.serialization.json.Json

/**
 * 全局共享的 [Json] 实例。
 *
 * - 忽略未知字段(兼容服务端字段演进)
 * - 宽松解析(允许非标准 JSON)
 * - 编码默认值(保证序列化结果稳定)
 * - 不显式输出 null 字段
 * - 强制把非法默认值转成合法值
 */
val AppJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    encodeDefaults = true
    explicitNulls = false
    coerceInputValues = true
}
