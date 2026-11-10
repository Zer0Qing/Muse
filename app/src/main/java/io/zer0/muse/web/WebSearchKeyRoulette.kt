package io.zer0.muse.web

import io.zer0.ai.util.KeyRoulette

/**
 * 联网搜索侧的 Key 池工具：把「一个供应商多把 Key」接到已有的 [KeyRoulette]。
 *
 * 直接复用 ai 模块的轮换器（public，`app` 已依赖 `:ai`），它已实现
 * 按最近使用分摊、429 主动切下一把、401 临时黑名单，无需在 web 侧另写一套。
 * [KeyRoulette] 的入参是逗号或换行分隔的字符串，所以这里负责
 * `List<String>` ↔ 分隔串的转换，并统一过滤空项。
 */
object WebSearchKeyRoulette {

    private val roulette = KeyRoulette()

    /** 批量粘贴解析：逗号、换行、中文逗号/分号都当分隔符，去重且保序。 */
    fun parse(raw: String): List<String> = raw
        .split(Regex("[,，;；\\r\\n]+"))
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinct()

    /** 把 Key 池拼成轮换器认识的分隔串。 */
    fun join(keys: List<String>): String = parse(keys.joinToString("\n")).joinToString("\n")

    /** 选一把当前应使用的 Key；空池返回空串（调用方据此判「未配置」）。 */
    fun pick(providerId: String, keys: List<String>): String {
        val joined = join(keys)
        if (joined.isBlank()) return ""
        return roulette.pick(providerId, joined)
    }

    /**
     * 429/限流时切下一把。只有一把 Key 时 [KeyRoulette.pickNext] 会原样返回 currentKey，
     * 表示「没有别的可换」而不是「没配置」，这里照原样透传，不误判成未配置。
     */
    fun pickNext(providerId: String, keys: List<String>, currentKey: String): String {
        val joined = join(keys)
        if (joined.isBlank()) return ""
        return roulette.pickNext(providerId, joined, currentKey)
    }

    /** 401/鉴权失败时把该把 Key 临时排除。 */
    fun markFailed(providerId: String, key: String) {
        if (key.isBlank()) return
        roulette.markFailed(providerId, key)
    }
}
