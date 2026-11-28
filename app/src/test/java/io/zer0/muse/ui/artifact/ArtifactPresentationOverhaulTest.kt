package io.zer0.muse.ui.artifact

import io.zer0.muse.data.artifact.ArtifactEntity
import io.zer0.muse.data.media.MediaGenAssetEntity
import io.zer0.muse.data.media.MediaGenTaskEntity
import io.zer0.muse.ui.markdown.DataCardParser
import io.zer0.muse.ui.markdown.isMermaidCodeBlock
import io.zer0.muse.ui.markdown.isPlantUmlCodeBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 产物展示重构后的纯函数与边界守护测试：
 *  - rich_card / image 导出扩展名与 MIME
 *  - 文件名推理渲染形态
 *  - RichCardArtifacts 标题派生与 mermaid 收录
 *  - 方案 A 产物库（ArtifactEntity + MediaGenAssetEntity）归并筛选
 *  - Mermaid / PlantUML 显式语言不误劫持与 DataCard 单块 JSON 解析
 */
class ArtifactPresentationOverhaulTest {

    @Test
    fun `rich_card and image resolve accurate export extension and mime`() {
        assertEquals(".html", artifactExportExtensionWithContent("rich_card", "html", "<div/>"))
        assertEquals(".svg", artifactExportExtensionWithContent("rich_card", "svg", "<svg/>"))
        assertEquals(".json", artifactExportExtensionWithContent("rich_card", "chart", "{}"))
        assertEquals(".mmd", artifactExportExtensionWithContent("rich_card", "mermaid", "graph TD"))

        assertEquals("text/html", artifactExportMime("rich_card", "html"))
        assertEquals("image/svg+xml", artifactExportMime("rich_card", "svg"))
        assertEquals("application/json", artifactExportMime("rich_card", "chart"))
        assertEquals("text/plain", artifactExportMime("rich_card", "mermaid"))

        assertEquals(".jpg", artifactExportExtensionWithContent("image", null, "data:image/jpeg;base64,abc"))
        assertEquals(".webp", artifactExportExtensionWithContent("image", null, "https://x.test/a.webp"))
        assertEquals(".png", artifactExportExtensionWithContent("image", null, "data:image/png;base64,abc"))
        assertEquals("image/*", artifactExportMime("image", null))
    }

    @Test
    fun `inferRenderModeFromFileName maps rich web, markdown, code, and images accurately`() {
        assertEquals(ArtifactRenderMode.RichWeb to "html", inferRenderModeFromFileName("demo.html"))
        assertEquals(ArtifactRenderMode.RichWeb to "svg", inferRenderModeFromFileName("icon.SVG"))
        assertEquals(ArtifactRenderMode.Image to "png", inferRenderModeFromFileName("shot.png"))
        assertEquals(ArtifactRenderMode.Markdown to "markdown", inferRenderModeFromFileName("README.md"))
        assertEquals(ArtifactRenderMode.Code to "json", inferRenderModeFromFileName("config.json"))
        assertEquals(ArtifactRenderMode.Code to "kt", inferRenderModeFromFileName("Main.kt"))
        assertEquals(ArtifactRenderMode.PlainText to "log", inferRenderModeFromFileName("run.log"))
    }

    @Test
    fun `RichCardArtifacts extracts mermaid and derives meaningful titles`() {
        val md = """
            ```html
            <html><head><title>销售看板</title></head><body><h1>忽略</h1></body></html>
            ```
            ```chart
            {"type":"line","options":{"plugins":{"title":{"text":"七日趋势"}}}}
            ```
            ```mermaid
            sequenceDiagram
                Alice->>Bob: Hi
            ```
        """.trimIndent()

        val items = RichCardArtifacts.extract("s1", "m1", md)
        assertEquals(3, items.size)
        assertEquals("销售看板", items.first { it.language == "html" }.title)
        assertEquals("七日趋势", items.first { it.language == "chart" }.title)
        assertEquals("Sequence Diagram", items.first { it.language == "mermaid" }.title)
    }

    @Test
    fun `explicit programming languages are not hijacked as mermaid or plantuml`() {
        assertFalse(isMermaidCodeBlock("python", "graph = {'a': 1}"))
        assertFalse(isMermaidCodeBlock("kotlin", "journey.start()"))
        assertFalse(isPlantUmlCodeBlock("markdown", "example: @startuml"))

        assertTrue(isMermaidCodeBlock("mermaid", "graph TD\nA-->B"))
        assertTrue(isMermaidCodeBlock(null, "graph TD\nA-->B"))
        assertTrue(isPlantUmlCodeBlock("plantuml", "@startuml\nA -> B\n@enduml"))
        assertTrue(isPlantUmlCodeBlock("", "@startuml\nA -> B\n@enduml"))
    }

    @Test
    fun `DataCardParser parseJson parses single block JSON directly`() {
        val card = DataCardParser.parseJson("""{"type":"bar","title":"Q1","labels":["A","B"],"values":[10,20]}""")
        assertNotNull(card)
        assertEquals("Q1", card?.title)
        assertEquals(listOf("A", "B"), card?.labels)
    }

    @Test
    fun `filterAndMergeLibraryItems merges artifacts and media sorted by time and filters properly`() {
        val artifacts = listOf(
            ArtifactEntity(
                id = "a1",
                sessionId = "s1",
                messageId = "m1",
                title = "报告",
                type = "markdown",
                content = "hello",
                createdAt = 100L,
            ),
            ArtifactEntity(
                id = "a2",
                sessionId = "s2",
                messageId = "m2",
                title = "网页",
                type = "rich_card",
                content = "<div/>",
                language = "html",
                createdAt = 300L,
            ),
            ArtifactEntity(id = "a3", sessionId = "s1", messageId = "m3", title = "配置", type = "json", content = "{}", createdAt = 150L),
        )
        val media = listOf(
            MediaGenAssetEntity(
                id = "g1",
                taskId = "t1",
                kind = MediaGenTaskEntity.KIND_IMAGE,
                prompt = "sunset mountain",
                providerId = "p1",
                modelId = "img-1",
                localPath = "/tmp/1.png",
                mimeType = "image/png",
                createdAt = 250L,
            ),
        )
        val sessions = mapOf("s1" to "项目讨论", "s2" to "前端页面")

        val all = filterAndMergeLibraryItems(artifacts, media, sessions, LibraryFilter.ALL, "")
        assertEquals(listOf("art:a2", "media:g1", "art:a3", "art:a1"), all.map { it.key })

        val webOnly = filterAndMergeLibraryItems(artifacts, media, sessions, LibraryFilter.WEB, "")
        assertEquals(listOf("art:a2"), webOnly.map { it.key })

        val codeOnly = filterAndMergeLibraryItems(artifacts, media, sessions, LibraryFilter.CODE, "")
        assertEquals(listOf("art:a3"), codeOnly.map { it.key })

        val mediaOnly = filterAndMergeLibraryItems(artifacts, media, sessions, LibraryFilter.MEDIA, "")
        assertEquals(listOf("media:g1"), mediaOnly.map { it.key })

        val bySessionSearch = filterAndMergeLibraryItems(artifacts, media, sessions, LibraryFilter.ALL, "项目讨论")
        assertEquals(listOf("art:a3", "art:a1"), bySessionSearch.map { it.key })

        val byPromptSearch = filterAndMergeLibraryItems(artifacts, media, sessions, LibraryFilter.ALL, "sunset")
        assertEquals(listOf("media:g1"), byPromptSearch.map { it.key })
    }
}
