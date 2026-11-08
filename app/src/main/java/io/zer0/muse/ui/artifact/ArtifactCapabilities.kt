package io.zer0.muse.ui.artifact

/**
 * 37：产物的渲染形态。把「点开是什么」从散落在查看器里的 if/else 收敛成一个可测判据，
 * 顺带修掉 image/file 被当成纯文本渲染的老问题。
 */
enum class ArtifactRenderMode {
    /** svg/html/chart/mermaid：WebView 渲染，可切源码、可全屏。 */
    RichWeb,

    /** 图片：真正的图片查看（URL/data-URI/本地路径），可全屏、可保存。 */
    Image,

    /** markdown/document/table/json/csv：GFM 渲染。 */
    Markdown,

    /** 代码：等宽 + 语法高亮。 */
    Code,

    /** 其余文本/文件：可滚动正文 + 外部打开，而不是 inert 预览框。 */
    PlainText,
}

/** type 与 language 双查富内容（与查看器/全屏入口同一判据），其余按类型分流。 */
fun artifactRenderMode(type: String, language: String?): ArtifactRenderMode {
    if (richPreviewLanguage(type, language) != null) return ArtifactRenderMode.RichWeb
    return when (type.lowercase()) {
        "image" -> ArtifactRenderMode.Image
        "markdown", "document", "table", "json", "csv" -> ArtifactRenderMode.Markdown
        "code" -> ArtifactRenderMode.Code
        else -> ArtifactRenderMode.PlainText
    }
}

/** 产物上可执行的动作。统一动作条据此渲染，杜绝「展开后什么都干不了」。 */
enum class ArtifactAction {
    Copy,
    Save,
    Share,
    OpenExternal,
    Fullscreen,
    ToggleSource,
}

/**
 * 每种形态的动作集合。
 *
 * 底线：任何形态都至少有 复制/保存/分享/外部打开 四件实事；网页形态额外给 源码切换 + 全屏，
 * 图片额外给 全屏。代码/文本不需要单独的「全屏网页」。
 */
fun artifactActions(mode: ArtifactRenderMode): Set<ArtifactAction> {
    val base = setOf(ArtifactAction.Copy, ArtifactAction.Save, ArtifactAction.Share, ArtifactAction.OpenExternal)
    return when (mode) {
        ArtifactRenderMode.RichWeb -> base + ArtifactAction.Fullscreen + ArtifactAction.ToggleSource
        ArtifactRenderMode.Image -> base + ArtifactAction.Fullscreen
        ArtifactRenderMode.Markdown, ArtifactRenderMode.Code, ArtifactRenderMode.PlainText -> base
    }
}
