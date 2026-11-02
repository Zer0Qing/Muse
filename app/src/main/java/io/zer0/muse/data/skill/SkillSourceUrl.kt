package io.zer0.muse.data.skill

/**
 * 技能包的**来源地址**（对比清单 P1-09）。
 *
 * 只支持 GitHub 仓库：标准 `SKILL.md` 生态里最常见的就是"把技能放仓库里，别人拉下来用"。
 * 只认一个域名不是偷懒 —— 让用户（或模型）给一个任意 URL 让应用去下载，
 * 等于把应用变成内网探测器；限定来源是这类功能最基本的一道闸。
 */
object SkillSourceUrl {

    /** 默认取仓库默认分支：`archive/HEAD.zip` 在 GitHub 上是合法写法。 */
    const val DEFAULT_REF = "HEAD"

    private val OWNER_OR_REPO = Regex("^[A-Za-z0-9._-]{1,100}$")
    private val REF = Regex("^[A-Za-z0-9._/-]{1,200}$")
    private const val HOST = "github.com"

    sealed interface Parsed {
        data class GitHub(val owner: String, val repo: String, val ref: String) : Parsed {
            /** 归档下载地址。 */
            val archiveUrl: String get() = "https://$HOST/$owner/$repo/archive/$ref.zip"
        }

        data class Invalid(val reason: String) : Parsed
    }

    /**
     * 解析用户给的一串地址。失败一律给可读原因（用户是手输的，得知道哪里不对）。
     */
    // 顺序校验天然是"一条不满足就返回"，拆成嵌套 when 只会更难读
    @Suppress("ReturnCount")
    fun parse(raw: String): Parsed {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return Parsed.Invalid("地址不能为空")
        if (!trimmed.startsWith("https://")) return Parsed.Invalid("只接受 https:// 开头的地址")
        val withoutScheme = trimmed.removePrefix("https://")
        val host = withoutScheme.substringBefore('/')
        if (!host.equals(HOST, ignoreCase = true)) {
            return Parsed.Invalid("只支持 github.com 的仓库地址（当前是 $host）")
        }
        val segments = withoutScheme.substringAfter('/', "").split('/').filter { it.isNotEmpty() }
        if (segments.size < 2) return Parsed.Invalid("地址里缺少仓库名，应形如 https://github.com/作者/仓库")
        val owner = segments[0]
        val repo = segments[1].removeSuffix(".git")
        if (!OWNER_OR_REPO.matches(owner)) return Parsed.Invalid("作者名不合法：$owner")
        if (!OWNER_OR_REPO.matches(repo)) return Parsed.Invalid("仓库名不合法：$repo")
        // 只允许两种形状：/owner/repo 与 /owner/repo/tree/<ref>。
        // 其它多余段一律拒绝 —— 否则 /owner/repo/../../etc 会被当成合法地址，
        // 用户以为自己指向了别的东西，实际装的是这个仓库。
        val ref = when {
            segments.size == 2 -> DEFAULT_REF
            segments.size >= 4 && segments[2] == "tree" -> segments.drop(3).joinToString("/")
            else -> return Parsed.Invalid("地址里有多余路径：${segments.drop(2).joinToString("/")}")
        }
        if (!REF.matches(ref) || ref.contains("..")) return Parsed.Invalid("分支/标签名不合法：$ref")
        return Parsed.GitHub(owner, repo, ref)
    }
}
