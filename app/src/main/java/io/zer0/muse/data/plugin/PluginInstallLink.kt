package io.zer0.muse.data.plugin

/**
 * 插件安装深链：`muse://plugin?url=<编码后的仓库地址>`（插件计划 P5）。
 *
 * 用途是把"从链接安装"变成可以扫码/点链接的动作：二维码里放这条 URI，
 * 用户扫一下就能装 —— 不必先找到插件页、再手打仓库地址。
 *
 * 校验分两层，且都是**先拒绝再解释**：
 *  1. 形状：必须是 `muse://plugin`，且带 `url` 参数；
 *  2. 目标：`url` 必须通过 [io.zer0.muse.data.skill.SkillSourceUrl] 的校验（只认 github.com）。
 *     深链是外部输入，能塞进来什么都不能假设。
 */
object PluginInstallLink {

    const val SCHEME = "muse"
    const val HOST = "plugin"
    const val PARAM_URL = "url"

    sealed interface Parsed {
        /** 通过校验的目标地址（可直接交给 importUrl）。 */
        data class Target(val url: String) : Parsed

        /** 被拒绝的原因，可直接展示给用户。 */
        data class Invalid(val reason: String) : Parsed
    }

    /**
     * @param uri 形如 `muse://plugin?url=https%3A%2F%2Fgithub.com%2Fowner%2Frepo`
     */
    fun parse(uri: String): Parsed {
        val trimmed = uri.trim()
        val scheme = trimmed.substringBefore("://", missingDelimiterValue = "")
        val rest = trimmed.substringAfter("://", missingDelimiterValue = "")
        val host = rest.substringBefore('/').substringBefore('?')
        val query = rest.substringAfter('?', missingDelimiterValue = "")
        val target = query.split('&')
            .map { it.substringBefore('=') to it.substringAfter('=', "") }
            .firstOrNull { it.first == PARAM_URL }
            ?.second
            ?.let { decode(it) }
            ?.trim()
            .orEmpty()
        return when {
            trimmed.isEmpty() -> Parsed.Invalid("链接为空")
            !scheme.equals(SCHEME, ignoreCase = true) ->
                Parsed.Invalid("这不是 Muse 的安装链接（应以 muse:// 开头）")
            !host.equals(HOST, ignoreCase = true) -> Parsed.Invalid("安装链接的路径应为 muse://$HOST")
            target.isEmpty() -> Parsed.Invalid("链接里缺少 url 参数")
            else -> when (val checked = io.zer0.muse.data.skill.SkillSourceUrl.parse(target)) {
                is io.zer0.muse.data.skill.SkillSourceUrl.Parsed.GitHub -> Parsed.Target(target)
                is io.zer0.muse.data.skill.SkillSourceUrl.Parsed.Invalid -> Parsed.Invalid(checked.reason)
            }
        }
    }

    /** 百分号解码；坏编码原样返回（交给下一层校验，不在这一层抛异常）。 */
    private fun decode(value: String): String = runCatching {
        java.net.URLDecoder.decode(value, "UTF-8")
    }.getOrDefault(value)
}
