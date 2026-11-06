package io.zer0.muse.web

/**
 * v2.7.0(S6): 把「本机 App 版本 + 协议版本」注入到打包好的网页资源里。
 *
 * 要解决的问题是真实存在的一类故障:网页端(assets/muse-web)与 App 不是同一次构建产物时,
 * 界面会停在启动引导上转圈 —— 用户看到的是白屏,而真相是「这份网页资源说的是协议 1,
 * 这台 App 说的是协议 2」。没有任何一处会告诉他。
 *
 * 做法只在**HTML 入口**做一次注入,不动 JS/CSS:
 *  - 资源里留了占位符就替换占位符;
 *  - 没有占位符(更旧的资源)就在 `<head>` 之后插一段;
 *  - 连 `<head>` 都找不到就原样返回 —— 绝不允许为了加一个提示而把页面结构改坏。
 *
 * 判据口径(写在客户端的 hostBuildGuard.ts 里,两边共用这段说明):
 *  - **协议主版本不一致**才是「不匹配」,必须提示;
 *  - 只是 App 版本号数字不同不构成故障,协议相同就该照常工作 ——
 *    把每一次小版本升级都变成一条黄色横幅,是在训练用户忽略真正要紧的警告。
 */
internal object HostVersionTag {

    /** 资源侧的占位符;打包模板里放这一串。 */
    const val TOKEN = "<!--MUSE_HOST_BUILD-->"

    /** `<head>` 找不到时的兜底插入点。 */
    private const val HEAD_OPEN = "<head"
    private const val SCRIPT_ID = "muse-host-build"

    /** 注入的脚本内容。字段少而稳定:客户端只读它需要的,多一个字段就多一处会漂移的地方。 */
    fun script(appVersion: String, protocolVersion: Int): String {
        val escaped = appVersion.replace("\\", "\\\\").replace("\"", "\\\"")
        return "<script id=\"$SCRIPT_ID\">window.__MUSE_HOST_BUILD__=" +
            "{\"appVersion\":\"$escaped\",\"protocolVersion\":$protocolVersion,\"injectedAt\":Date.now()}" +
            "</script>"
    }

    /**
     * 在 HTML 里放好构建信息。
     *
     * 三种情形分别断言(见 HostVersionTagTest):有占位符 → 恰好替换一次;
     * 无占位符 → 插进 `<head>` 内;结构都不认 → 原文返回,不冒险。
     */
    fun inject(html: String, appVersion: String, protocolVersion: Int): String {
        val tag = script(appVersion, protocolVersion)
        if (html.contains(TOKEN)) return html.replaceFirst(TOKEN, tag)
        val head = html.indexOf(HEAD_OPEN)
        if (head < 0) return html
        val close = html.indexOf('>', head)
        if (close < 0) return html
        return html.take(close + 1) + "\n" + tag + html.drop(close + 1)
    }

    /** 已经注入过就不再注入(sw.js 缓存回来的同一份 HTML 可能被反复服务)。 */
    fun isAlreadyInjected(html: String): Boolean = html.contains("id=\"$SCRIPT_ID\"")
}
