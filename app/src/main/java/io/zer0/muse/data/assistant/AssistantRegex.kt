package io.zer0.muse.data.assistant

import kotlinx.serialization.Serializable

/**
 * v1.97: 助手级正则替换规则。
 *
 * 参考 rikkahub 的 AssistantRegex,允许用户为每个助手配置正则替换规则,
 * 在消息发送/接收时自动替换文本内容。
 *
 * 应用场景:
 *  - 角色名替换:把"AI"替换为角色名
 *  - 敏感词过滤:替换不当用语
 *  - 格式标准化:统一标点、空格
 *  - 输出修饰:给特定内容加标记
 *
 * @param id 规则 id(唯一标识)
 * @param name 规则名称(显示用)
 * @param enabled 是否启用
 * @param findRegex 查找正则表达式
 * @param replaceString 替换字符串(支持 $1, $2 等反向引用)
 * @param affectingScope 影响范围:"user"(用户消息) / "assistant"(助手消息) / "both"(双向)
 * @param visualOnly 仅视觉替换(不改变实际发送内容,只改 UI 显示)
 */
@Serializable
data class AssistantRegex(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String = "",
    val enabled: Boolean = true,
    val findRegex: String = "",
    val replaceString: String = "",
    val affectingScope: String = "both",
    val visualOnly: Boolean = false,
)
