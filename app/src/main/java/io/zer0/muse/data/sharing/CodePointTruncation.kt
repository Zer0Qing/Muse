package io.zer0.muse.data.sharing

/**
 * 按「看得见的字」截断，而不是按 UTF-16 单元。
 *
 * `String.take(n)` 数的是 UTF-16 单元：一个 emoji（如 😀）占两个单元，
 * 恰好切在中间就会留下半个代理对，画到画布上是乱码方框，存进文件是非法 UTF-8。
 * 名字里带 emoji 很常见，所以凡是「给人看/要落盘」的截断都该走这里。
 *
 * @param maxChars 最多保留多少个码点（一个字/一个 emoji 都算一个）
 */
internal fun String.takeCodePoints(maxChars: Int): String {
    if (maxChars <= 0) return ""
    if (isEmpty()) return this
    // 先数够不够，够就原样返回 —— 绝大多数名字都短，省一次遍历分配。
    var count = 0
    var index = 0
    while (index < length && count < maxChars) {
        index += Character.charCount(codePointAt(index))
        count++
    }
    return if (index >= length) this else substring(0, index)
}
