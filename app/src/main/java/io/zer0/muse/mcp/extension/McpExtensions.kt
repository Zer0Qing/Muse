package io.zer0.muse.mcp.extension

import io.zer0.common.Logger
import kotlinx.serialization.Serializable

/**
 * Phase 5 5E: MCP 扩展 — 额外的 MCP 服务器集成。
 *
 * P0:Spotify/Apple Music("播放一首歌")
 * P0:系统相册("查找旅行照片")
 * P1:Notion/Obsidian(AI 读写笔记)
 *
 * 当前为接口定义 + 存根实现,具体集成需各平台 API 就绪后补充。
 */
interface McpExtension {
    val name: String
    val description: String
    val isAvailable: Boolean

    /** 列出此扩展提供的工具。 */
    fun listTools(): List<ExtensionTool>

    /** 执行工具调用。 */
    suspend fun execute(toolName: String, arguments: Map<String, String>): ExtensionResult
}

/** 扩展工具描述。 */
@Serializable
data class ExtensionTool(
    val name: String,
    val description: String,
    val parameters: List<ToolParameter> = emptyList(),
)

/** 工具参数。 */
@Serializable
data class ToolParameter(
    val name: String,
    val type: String = "string",
    val description: String = "",
    val required: Boolean = true,
)

/** 工具执行结果。 */
sealed class ExtensionResult {
    data class Success(val content: String) : ExtensionResult()
    data class Error(val message: String) : ExtensionResult()
}

/**
 * P0: Spotify/Apple Music MCP 扩展 — 播放/搜索音乐。
 */
class MusicExtension : McpExtension {
    override val name = "music"
    override val description = "Play and search music via Spotify/Apple Music"
    override val isAvailable: Boolean = false // TODO: 检测已安装的播放器

    override fun listTools(): List<ExtensionTool> = listOf(
        ExtensionTool(
            name = "play_music",
            description = "Play a song by name or artist",
            parameters = listOf(
                ToolParameter("query", "string", "Song name or artist", required = true),
                ToolParameter("platform", "string", "spotify or apple_music", required = false),
            ),
        ),
        ExtensionTool(
            name = "search_music",
            description = "Search for songs, albums, or playlists",
            parameters = listOf(
                ToolParameter("query", "string", "Search query", required = true),
            ),
        ),
        ExtensionTool(
            name = "get_current_playing",
            description = "Get the currently playing song info",
        ),
    )

    override suspend fun execute(toolName: String, arguments: Map<String, String>): ExtensionResult {
        Logger.i("MusicExtension", "Execute: $toolName with $arguments")
        // TODO: 通过 Intent 调用 Spotify/Apple Music
        return ExtensionResult.Error("Music extension not yet implemented - requires Spotify/Apple Music SDK")
    }
}

/**
 * P0: 系统相册 MCP 扩展 — 搜索/浏览本地照片。
 */
class PhotoGalleryExtension : McpExtension {
    override val name = "photo_gallery"
    override val description = "Search and browse local photo gallery"
    override val isAvailable: Boolean = true // Android 相册始终可用

    override fun listTools(): List<ExtensionTool> = listOf(
        ExtensionTool(
            name = "find_photos",
            description = "Find photos by description (e.g., 'travel photos', 'last week')",
            parameters = listOf(
                ToolParameter("description", "string", "Photo description or time range", required = true),
                ToolParameter("limit", "integer", "Max number of results", required = false),
            ),
        ),
        ExtensionTool(
            name = "get_recent_photos",
            description = "Get the most recent photos",
            parameters = listOf(
                ToolParameter("count", "integer", "Number of photos", required = false),
            ),
        ),
    )

    override suspend fun execute(toolName: String, arguments: Map<String, String>): ExtensionResult {
        Logger.i("PhotoGalleryExtension", "Execute: $toolName with $arguments")
        // TODO: 通过 ContentResolver + MediaStore 查询照片
        return ExtensionResult.Error("Photo gallery extension not yet fully implemented")
    }
}

/**
 * P1: Notion/Obsidian MCP 扩展 — 读写笔记。
 */
class NotesExtension : McpExtension {
    override val name = "notes"
    override val description = "Read and write notes in Notion/Obsidian"
    override val isAvailable: Boolean = false // TODO: 检测配置

    override fun listTools(): List<ExtensionTool> = listOf(
        ExtensionTool(
            name = "search_notes",
            description = "Search notes by keyword",
            parameters = listOf(
                ToolParameter("query", "string", "Search query", required = true),
                ToolParameter("platform", "string", "notion or obsidian", required = false),
            ),
        ),
        ExtensionTool(
            name = "create_note",
            description = "Create a new note",
            parameters = listOf(
                ToolParameter("title", "string", "Note title", required = true),
                ToolParameter("content", "string", "Note content", required = true),
                ToolParameter("platform", "string", "notion or obsidian", required = false),
            ),
        ),
        ExtensionTool(
            name = "read_note",
            description = "Read a note by title or ID",
            parameters = listOf(
                ToolParameter("identifier", "string", "Note title or ID", required = true),
            ),
        ),
    )

    override suspend fun execute(toolName: String, arguments: Map<String, String>): ExtensionResult {
        Logger.i("NotesExtension", "Execute: $toolName with $arguments")
        // TODO: 通过 Notion API / Obsidian local vault 读写
        return ExtensionResult.Error("Notes extension not yet implemented - requires Notion API token or Obsidian vault path")
    }
}

/**
 * Phase 5 5E: MCP 扩展注册表 — 管理所有可用的 MCP 扩展。
 */
class McpExtensionRegistry {
    private val extensions = mutableListOf<McpExtension>()

    init {
        // 注册内置扩展
        extensions.add(MusicExtension())
        extensions.add(PhotoGalleryExtension())
        extensions.add(NotesExtension())
    }

    /** 获取所有已注册扩展。 */
    fun getAll(): List<McpExtension> = extensions.toList()

    /** 获取可用的扩展。 */
    fun getAvailable(): List<McpExtension> = extensions.filter { it.isAvailable }

    /** 按名称查找扩展。 */
    fun findByName(name: String): McpExtension? = extensions.firstOrNull { it.name == name }

    /** 列出所有可用工具(跨扩展)。 */
    fun listAllTools(): List<Pair<McpExtension, ExtensionTool>> {
        return extensions.flatMap { ext -> ext.listTools().map { tool -> ext to tool } }
    }
}
