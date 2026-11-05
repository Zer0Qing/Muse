package io.zer0.muse.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight

/**
 * 强调变体角色：把全站反复出现的「同一角色 + 更重一档字重」收成一个名字。
 *
 * 为什么要收：量测显示 `typography.X.copy(fontWeight = Y)` 在删掉空转后仍剩 41 处 / 9 种配对，
 * 其中 5 种各 ≥5 次（合计 33 处）。留成内联覆盖，等于让 33 个页面各自决定
 * 「这个角色的强调长什么样」，字重规范永远收不拢。
 *
 * 为什么必须是 **扩展属性**而不是 `val strong = MuseTypography.labelLarge.copy(...)`：
 * 主题会按字号档位跑 `Typography.scaled()`（0.85x … 1.3x）；静态常量会绕开它，
 * 用户在设置里放大字号时这些文字**悄悄不跟着变大** —— 那是实打实的回归，不是风格问题。
 * 取 `this` 就永远跟当前主题实例同源。
 *
 * 视觉等价性：只改 fontWeight；fontSize / lineHeight / letterSpacing 一律继承基础角色。
 */

/** `labelLarge` 的强调档：列表项标题、卡片动作标签常用。 */
val Typography.labelLargeStrong: TextStyle
    get() = labelLarge.copy(fontWeight = FontWeight.SemiBold)

/** `bodyMedium` 的强调档：正文里的键名、计数前缀。 */
val Typography.bodyMediumStrong: TextStyle
    get() = bodyMedium.copy(fontWeight = FontWeight.Medium)

/** `titleSmall` 的强调档：分组小标题。 */
val Typography.titleSmallStrong: TextStyle
    get() = titleSmall.copy(fontWeight = FontWeight.SemiBold)

/** `bodyLarge` 的强调档：会话正文里的引导句。 */
val Typography.bodyLargeStrong: TextStyle
    get() = bodyLarge.copy(fontWeight = FontWeight.Medium)

/** `bodySmall` 的强调档：说明行里需要被看见的那一句。 */
val Typography.bodySmallStrong: TextStyle
    get() = bodySmall.copy(fontWeight = FontWeight.SemiBold)
