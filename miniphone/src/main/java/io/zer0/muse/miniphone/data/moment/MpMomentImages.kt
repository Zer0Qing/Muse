package io.zer0.muse.miniphone.data.moment

import org.json.JSONArray

/**
 * 动态图片列表与 `imagesJson` 的互转。
 *
 * 库里只存一段 JSON 文本(一行最多九张),不让 Room 多一张关联表:
 * 图片没有自己的生命周期,删动态就该跟着消失,存成文本最省事。
 * 解析失败一律当「没有图」处理 —— 一条脏数据不该让整段动态读不出来。
 */

/** 图片路径列表 → `imagesJson`;空列表存 `[]`。 */
internal fun encodeMomentImages(images: List<String>): String {
    val array = JSONArray()
    images.forEach { path -> array.put(path) }
    return array.toString()
}

/** `imagesJson` → 图片路径列表;空串、空数组、坏文本都得到空列表。 */
internal fun decodeMomentImages(json: String?): List<String> {
    if (json.isNullOrBlank()) return emptyList()
    return runCatching {
        val array = JSONArray(json)
        (0 until array.length()).mapNotNull { index ->
            array.optString(index).takeIf { path -> path.isNotBlank() }
        }
    }.getOrDefault(emptyList())
}
