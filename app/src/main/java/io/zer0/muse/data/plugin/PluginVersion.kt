package io.zer0.muse.data.plugin

/**
 * 插件版本号（语义化版本，SemVer 2.0 的 precedence 规则）。
 *
 * 用于三件事：
 *  - 拒绝安装比已安装版本更旧的包（降级保护，回滚走显式入口）；
 *  - 比较 manifest 的 `minAppVersion` 与当前 App 版本；
 *  - 目录与包之间的版本一致性判断，以及回滚目标的查找。
 *
 * 构建元数据（`+build`）不参与比较，符合 SemVer 规范。
 */
data class PluginVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
    /** 预发布标识（`1.0.0-rc.1` 中的 `rc`, `1`）；空表示正式版本。 */
    val prerelease: List<String> = emptyList(),
) : Comparable<PluginVersion> {

    override fun compareTo(other: PluginVersion): Int {
        compareValues(major, other.major).takeIf { it != 0 }?.let { return it }
        compareValues(minor, other.minor).takeIf { it != 0 }?.let { return it }
        compareValues(patch, other.patch).takeIf { it != 0 }?.let { return it }
        return comparePrerelease(prerelease, other.prerelease)
    }

    /** 正式版本（无预发布标识）。 */
    val isStable: Boolean get() = prerelease.isEmpty()

    override fun toString(): String = buildString {
        append(major).append('.').append(minor).append('.').append(patch)
        if (prerelease.isNotEmpty()) append('-').append(prerelease.joinToString("."))
    }

    companion object {
        /**
         * 解析语义化版本；不合法返回 null（调用方据此拒绝而不是猜一个版本）。
         *
         * 与目录校验使用同一套形状：`主.次.修订` + 可选 `-预发布` + 可选 `+构建`。
         */
        private val PATTERN =
            Regex("^(\\d+)\\.(\\d+)\\.(\\d+)(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?$")

        fun parse(raw: String): PluginVersion? {
            val matched = PATTERN.matchEntire(raw.trim()) ?: return null
            val major = matched.groupValues[1].toIntOrNull() ?: return null
            val minor = matched.groupValues[2].toIntOrNull() ?: return null
            val patch = matched.groupValues[3].toIntOrNull() ?: return null
            val prerelease = matched.groupValues[4]
                .takeIf { it.isNotEmpty() }
                ?.split('.')
                .orEmpty()
            // 数字型预发布标识不允许前导零（SemVer 规范），非法一律判为不合法版本。
            if (prerelease.any { it.length > 1 && it.startsWith("0") && it.all(Char::isDigit) }) return null
            return PluginVersion(major, minor, patch, prerelease)
        }
    }
}

private fun comparePrerelease(first: List<String>, second: List<String>): Int {
    // 有预发布标识的版本优先级更低（1.0.0-rc < 1.0.0）。
    if (first.isEmpty() && second.isEmpty()) return 0
    if (first.isEmpty()) return 1
    if (second.isEmpty()) return -1
    for (index in 0 until maxOf(first.size, second.size)) {
        val left = first.getOrNull(index) ?: return -1
        val right = second.getOrNull(index) ?: return 1
        val leftNumber = left.toIntOrNull()
        val rightNumber = right.toIntOrNull()
        val result = when {
            leftNumber != null && rightNumber != null -> compareValues(leftNumber, rightNumber)
            leftNumber != null -> -1 // 数字标识优先级低于字母标识
            rightNumber != null -> 1
            else -> left.compareTo(right)
        }
        if (result != 0) return result
    }
    return 0
}
