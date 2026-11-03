package io.zer0.muse.data.sticker

/**
 * 从任意文本里把**图片直链**捞出来（表情包重做 P1）。
 *
 * 为什么需要它：市面上发的素材包，正文里经常只有图床链接
 * （样本里每行是 `https://pic1.imgdb.cn/i/<id>.jpg（小猫歪头…）`），
 * PDF 那几页干脆**只**有链接。认不出这些，用户就只能自己一张张存。
 *
 * 三条约束：
 *  1. 只认 http/https，其他 scheme 一律不碰；
 *  2. 只要图片直链 —— 带图片扩展名，或路径命中已知图床直链形态；
 *     普通网页链接（笔记页、店铺页）导进来不是表情，只会让用户看到一堆失败；
 *  3. 限量：一次最多 [MAX_URLS] 条，超出就停 —— 素材包不该有几百张，
 *     超出来通常是文本里混了别的链接。
 */
object StickerUrlList {

    /** 一次最多捞多少条。 */
    const val MAX_URLS = 200

    /** 已知的图床直链路径特征（这些站点路径里没有扩展名也可能返回图片）。 */
    private val HOSTED_IMAGE_PATHS = listOf("/i/", "/img/", "/pic/", "/item/", "/original/")

    /**
     * URL 字符集只取 ASCII（RFC 3986 允许的字面字符）。
     *
     * 不能写成"任何非空白字符"：样本里链接紧跟全角括号中文配文
     * （`…jpg（小猫歪头）`），非空白写法会把中文一起吃掉，
     * 捞出来的"链接"其实是半句话。
     */
    internal val URL_PATTERN = Regex(
        """https?://[A-Za-z0-9._~:/?#\[\]@!%()+,*=-]+""",
        RegexOption.IGNORE_CASE,
    )

    private val IMAGE_EXTENSIONS = listOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "apng")

    /**
     * 按出现顺序返回去重后的图片链接。
     *
     * @param requireExtension 为 true 时只认带图片扩展名的链接
     */
    fun harvest(text: String, requireExtension: Boolean = false): List<String> {
        if (text.isBlank()) return emptyList()
        val seen = LinkedHashSet<String>()
        // 用带标签的循环而不是 forEach + 提前 return：后者只跳出当前这一次迭代，
        // 上限根本挡不住（这条是被测试抓出来的）
        collect@ for (match in URL_PATTERN.findAll(text)) {
            val url = normalize(match.value)
            if (url.isNotEmpty() && isImageUrl(url, requireExtension)) seen += url
            if (seen.size >= MAX_URLS) break@collect
        }
        return seen.toList()
    }

    /** 是否是图片直链。 */
    fun isImageUrl(url: String, requireExtension: Boolean = false): Boolean {
        val lowered = url.lowercase()
        if (!lowered.startsWith("http://") && !lowered.startsWith("https://")) return false
        val path = "/" + lowered.substringAfter("://").substringAfter('/', "")
        val file = path.substringAfterLast('/')
        val extension = file.substringAfterLast('.', "")
        if (extension in IMAGE_EXTENSIONS) return true
        if (requireExtension) return false
        // 没扩展名时只认明确的图床路径形态（补了前导斜杠才能匹配 `/i/` 这类片段）
        return HOSTED_IMAGE_PATHS.any { it in path }
    }

    /** 去掉尾随标点，避免把中文标点或句子尾巴粘进链接。 */
    private fun normalize(raw: String): String = raw
        .trim()
        .trimEnd('，', ',', '。', ';', '；', '、', ')', '）', ']', '】', '.')
}
