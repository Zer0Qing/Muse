package io.zer0.muse.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.zer0.muse.ui.theme.MuseColors

/**
 * Muse UI Kit — 风险等级徽章 [RiskBadge]。
 *
 * 设计稿对齐:
 *  - 安全: 深绿文字 + 浅绿背景
 *  - 中等: 橙色文字 + 浅橙背景
 *  - 高风险: 红色文字 + 浅红背景
 *  - 小圆角(4dp) + 紧凑内边距
 *
 * 用法:
 * ```
 * RiskBadge(level = RiskLevel.SAFE)    // 显示 "安全"
 * RiskBadge(level = RiskLevel.HIGH)     // 显示 "高风险"
 * RiskBadge(level = RiskLevel.NORMAL, label = "中等")
 * ```
 *
 * @param level 风险等级
 * @param modifier 修饰符
 * @param label 自定义文字(null 则用默认: 安全/中等/高风险)
 */
@Composable
fun RiskBadge(
    level: RiskLevel,
    modifier: Modifier = Modifier,
    label: String? = null,
) {
    val (textColor, bgColor) = when (level) {
        RiskLevel.SAFE -> MuseColors.riskSafe to MuseColors.riskSafe.copy(alpha = 0.1f)
        RiskLevel.NORMAL -> MuseColors.riskNormal to MuseColors.riskNormal.copy(alpha = 0.1f)
        RiskLevel.HIGH -> MuseColors.riskHigh to MuseColors.riskHigh.copy(alpha = 0.1f)
    }

    val displayText = label ?: when (level) {
        RiskLevel.SAFE -> "安全"
        RiskLevel.NORMAL -> "中等"
        RiskLevel.HIGH -> "高风险"
    }

    Text(
        text = displayText,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Medium,
        color = textColor,
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(bgColor)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/** 风险等级枚举。 */
enum class RiskLevel {
    /** 安全: 只读操作,无副作用。 */
    SAFE,
    /** 中等: 有副作用但可逆。 */
    NORMAL,
    /** 高风险: 不可逆操作(删除/发送/拨号)。 */
    HIGH,
}
