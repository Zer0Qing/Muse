package io.zer0.muse.data.sticker

import java.security.MessageDigest

/**
 * 导入候选的取舍规则（表情包重做 P1）。
 *
 * 单独成文件的原因：这几条规则**决定用户会不会看到一堆垃圾**。
 * 素材包里的图经常混着水印图、二维码、作者头像、同一张图的两个尺寸；
 * 全导进来，用户的表情面板第一屏就是废料。
 *
 * 规则按"宁少不误"排：
 *  1. **先按内容哈希去重** —— 同一张图重复出现是最常见的（PDF/DOCX 各带一份）；
 *  2. **太小不要** —— 低于 [MIN_SIDE_PX] 的多半是角标；
 *     但 120px 左右的合法小表情要留，所以阈值压得比直觉低；
 *  3. **极端长条不要** —— 分割线、标题条；
 *  4. **超大不要** —— 单张超过 [MAX_BYTES] 的通常是原画稿，进表情面板只会拖慢加载。
 */
object StickerCandidateFilters {

    /** 最小边长（像素）。 */
    const val MIN_SIDE_PX = 64

    /** 最大长宽比。 */
    const val MAX_ASPECT_RATIO = 4f

    /** 单张字节上限（3MB）。 */
    const val MAX_BYTES = 3 * 1024 * 1024

    /** 一次导入的张数上限，与既有 zip 路径一致。 */
    const val MAX_ITEMS = 200

    /** 通过筛选的一条。 */
    data class Accepted<T>(val item: T, val sha256: String)

    /**
     * 依次套用规则，保留原始顺序。
     *
     * @param widthOf/heightOf/bytesOf 由调用方提供（Android 侧从 BitmapFactory 取，测试里直接给常量）
     */
    fun <T> filter(
        items: List<T>,
        widthOf: (T) -> Int,
        heightOf: (T) -> Int,
        bytesOf: (T) -> ByteArray,
    ): List<T> {
        val seen = HashSet<String>()
        val kept = mutableListOf<T>()
        // 上限必须在**进循环体第一件事**就判：以前写成 forEach + return@forEach，
        // 那只跳出当前这一次迭代，200 张的上限形同虚设（测试抓到实际给了 221 张）。
        // 这里也刻意不用多个 continue：去重与尺寸判定合成一个条件，读起来是一条规则而不是三道闸门。
        for (item in items) {
            if (kept.size >= MAX_ITEMS) break
            if (seen.add(sha256(bytesOf(item))) &&
                plausible(widthOf(item), heightOf(item), bytesOf(item).size)
            ) {
                kept += item
            }
        }
        return kept
    }

    /** 单条判定（尺寸/字节已知时）。 */
    fun plausible(width: Int, height: Int, bytes: Int): Boolean {
        if (width < MIN_SIDE_PX || height < MIN_SIDE_PX) return false
        if (bytes <= 0 || bytes > MAX_BYTES) return false
        val long = maxOf(width, height).toFloat()
        val shortSide = minOf(width, height).toFloat()
        return long / shortSide <= MAX_ASPECT_RATIO
    }

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }
}
