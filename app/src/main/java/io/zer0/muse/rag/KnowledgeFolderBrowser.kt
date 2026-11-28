package io.zer0.muse.rag

import io.zer0.common.AppJson
import io.zer0.muse.data.knowledge.KnowledgeDocEntity
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Virtual folder paths for a file-manager style KB browser, stored in document metadata (no DB migration). */
object KnowledgeFolderBrowser {
    private const val FOLDER_PATH_KEY = "folderPath"
    private val ARCHIVE_SOURCE_SCHEMES = setOf("zip", "7z", "rar")
    private val json = AppJson

    data class Folder(
        val name: String,
        val path: String,
        val documentCount: Int,
    )

    fun normalize(path: String): String {
        val segments = mutableListOf<String>()
        path.replace('\\', '/').split('/').map { it.trim() }.forEach { segment ->
            when (segment) {
                "", "." -> Unit
                ".." -> if (segments.isNotEmpty()) segments.removeAt(segments.lastIndex)
                else -> segments.add(segment)
            }
        }
        return segments.joinToString("/")
    }

    fun parent(path: String): String {
        val normalized = normalize(path)
        val slash = normalized.lastIndexOf('/')
        return if (slash < 0) "" else normalized.substring(0, slash)
    }

    /** Import into the open folder only when it belongs to the selected KB and search is inactive. */
    fun importFolderPath(targetKbId: String, browsingKbId: String?, currentFolderPath: String, searchQuery: String): String =
        if (browsingKbId != null && targetKbId == browsingKbId && searchQuery.isBlank()) {
            normalize(currentFolderPath)
        } else {
            ""
        }

    /** Explicit move metadata wins; archive imports retain their relative entry folders in filePath. */
    fun folderPath(document: KnowledgeDocEntity): String {
        val explicitFolder = runCatching {
            json.parseToJsonElement(document.metadataJson).jsonObject[FOLDER_PATH_KEY]?.jsonPrimitive?.content
        }.getOrNull()
        val archiveEntryFolder = ARCHIVE_SOURCE_SCHEMES
            .firstOrNull { document.filePath.startsWith("$it://", ignoreCase = true) }
            ?.let { document.filePath.substringAfter("://", "") }
            ?.substringAfter('/', "")
            ?.let { entry ->
                val slash = entry.lastIndexOf('/')
                entry.takeIf { slash > 0 }?.substring(0, slash)
            }
        return explicitFolder?.let(::normalize) ?: archiveEntryFolder?.let(::normalize).orEmpty()
    }

    fun documentsInFolder(documents: List<KnowledgeDocEntity>, path: String): List<KnowledgeDocEntity> {
        val target = normalize(path)
        return documents.filter { folderPath(it) == target }
    }

    fun childFolders(documents: List<KnowledgeDocEntity>, path: String): List<Folder> {
        val parent = normalize(path)
        val prefix = if (parent.isEmpty()) "" else "$parent/"
        val childDocumentIds = linkedMapOf<String, MutableSet<String>>()
        documents.forEach { document ->
            val documentPath = folderPath(document)
            if (!documentPath.startsWith(prefix) || documentPath == parent) return@forEach
            val childName = documentPath.removePrefix(prefix).substringBefore('/')
            if (childName.isNotBlank()) {
                val childPath = if (parent.isEmpty()) childName else "$parent/$childName"
                childDocumentIds.getOrPut(childPath) { linkedSetOf() }.add(document.id)
            }
        }
        return childDocumentIds.map { (childPath, documentIds) ->
            Folder(name = childPath.substringAfterLast('/'), path = childPath, documentCount = documentIds.size)
        }.distinctBy { it.path }.sortedBy { it.name.lowercase() }
    }

    /** Update only the virtual folder property while retaining existing document metadata. */
    fun withFolderPath(document: KnowledgeDocEntity, path: String): KnowledgeDocEntity {
        val existing = runCatching { json.parseToJsonElement(document.metadataJson).jsonObject }
            .getOrDefault(JsonObject(emptyMap()))
        val normalized = normalize(path)
        // Keep an explicit empty path too: it lets a user move an archive entry to the KB root,
        // overriding the folder inferred from its immutable archive source path.
        val updated = JsonObject(existing + (FOLDER_PATH_KEY to JsonPrimitive(normalized)))
        return document.copy(metadataJson = json.encodeToString(JsonObject.serializer(), updated))
    }
}
