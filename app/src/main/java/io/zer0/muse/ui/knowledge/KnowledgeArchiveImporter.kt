package io.zer0.muse.ui.knowledge

import android.content.Context
import android.net.Uri
import com.github.junrar.Archive
import com.github.junrar.ArchiveOptions
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.R
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.knowledge.KnowledgeBaseDao
import io.zer0.muse.data.knowledge.KnowledgeBaseEntity
import io.zer0.muse.data.knowledge.KnowledgeDocDao
import io.zer0.muse.data.knowledge.KnowledgeDocEntity
import io.zer0.muse.doc.DocumentParser
import io.zer0.muse.rag.RagConfig
import io.zer0.muse.rag.RagService
import io.zer0.muse.ui.common.feedback.MuseToast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import java.io.Closeable
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipInputStream

@Suppress("LongParameterList")
internal class ArchiveImportDeps(
    val context: Context,
    val kbDao: KnowledgeBaseDao,
    val docDao: KnowledgeDocDao,
    val ragService: RagService,
    val settings: SettingsRepository,
    val documentParser: DocumentParser,
    val onProgress: (String) -> Unit,
)

private enum class ArchiveEntryOutcome { IGNORED, IMPORTED, SKIPPED, FAILED }

private data class ArchiveEntryResult(val outcome: ArchiveEntryOutcome, val units: Long)

private data class ArchiveScanResult(val imported: Int, val skipped: Int, val failed: Int)

internal data class ArchiveEntry(
    val name: String,
    val isDirectory: Boolean,
    val isEncrypted: Boolean = false,
    val size: Long? = null,
    val openStream: () -> InputStream,
    val extractToFile: ((OutputStream) -> Unit)? = null,
)

internal interface ArchiveEntryReader : Closeable {
    fun next(): ArchiveEntry?
}

/**
 * Imports supported archive entries directly into one knowledge base, preserving their paths
 * as virtual folders. Only non-ZIP source archives are staged; their contents are never unpacked
 * as a whole to disk.
 */
internal suspend fun importArchiveBundle(archiveUri: Uri, archiveName: String, format: ArchiveFormat, deps: ArchiveImportDeps) {
    val kbName = archiveName.substringBeforeLast('.', archiveName).take(60).ifBlank { archiveName.take(60) }
    val createdAt = System.currentTimeMillis()
    val kbId = "kb-$createdAt"
    deps.kbDao.upsert(
        KnowledgeBaseEntity(
            id = kbId,
            name = kbName,
            description = archiveName,
            createdAt = createdAt,
            updatedAt = createdAt,
        ),
    )
    deps.onProgress(deps.context.getString(R.string.knowledge_zip_extracting))

    var stagedArchive: File? = null
    val result = try {
        withContext(Dispatchers.IO) {
            val reader = when (format) {
                ArchiveFormat.ZIP -> ZipArchiveEntryReader(
                    deps.context.contentResolver.openInputStream(archiveUri)
                        ?: error("Cannot open archive: $archiveUri"),
                )
                ArchiveFormat.SEVEN_Z,
                ArchiveFormat.RAR,
                -> {
                    val archiveFile = stageArchive(archiveUri, format, deps.context)
                    stagedArchive = archiveFile
                    when (format) {
                        ArchiveFormat.SEVEN_Z -> SevenZArchiveEntryReader(archiveFile)
                        ArchiveFormat.RAR -> RarArchiveEntryReader(archiveFile)
                        ArchiveFormat.ZIP -> error("Unexpected archive format")
                    }
                }
            }
            reader.use { scanArchiveEntries(it, format, archiveName, kbId, deps) }
        }
    } catch (e: Exception) {
        // 半途失败：删掉刚建的空知识库，再把原异常继续抛给调用方（报错由它负责）。
        rollbackKnowledgeBase(deps.kbDao, kbId, "import failed rollback")
        throw e
    } finally {
        stagedArchive?.let { resultOf { it.delete() } }
    }

    if (result.imported <= 0) {
        val cleaned = rollbackKnowledgeBase(deps.kbDao, kbId, "archive has no importable content")
        // 没清干净就要说得出下一步：列表里会留一个点开什么都没有的条目，
        // 而只说「没有可导入的文档」会让用户两句对不上，以为程序坏了。
        MuseToast.show(
            if (cleaned) {
                deps.context.getString(R.string.knowledge_zip_empty)
            } else {
                deps.context.getString(R.string.knowledge_zip_empty_leftover, kbName)
            },
        )
    } else {
        MuseToast.show(
            deps.context.getString(
                R.string.knowledge_zip_done,
                kbName,
                result.imported,
                result.skipped + result.failed,
            ),
        )
    }
}

/**
 * 删掉导入过程中临时建出来的知识库条目，返回**是否真删干净**。
 *
 * 以前这里是 `resultOf { … }` 直接把结果丢掉：回滚失败没人知道，
 * 于是列表里留下一个"刚建好、点开什么都没有"的条目，
 * 而用户看到的提示是「压缩包内没有可导入的文档」——两句对不上，只能以为程序坏了。
 *
 * 失败一定留痕（带 kbId 与原因）；成功保持安静：这是清理路径，不是业务动作。
 */
internal suspend fun rollbackKnowledgeBase(kbDao: KnowledgeBaseDao, kbId: String, reason: String): Boolean {
    val outcome = resultOf { kbDao.delete(kbId) }
    outcome.onError { message, throwable ->
        Logger.w(
            "KbArchiveImport",
            "$reason 时清理临时知识库失败 kbId=$kbId | 原因: $message",
            throwable,
        )
    }
    return outcome.isSuccess
}

private fun stageArchive(uri: Uri, format: ArchiveFormat, context: Context): File {
    val target = File.createTempFile("kb-import-", ".${format.extension}", context.cacheDir)
    try {
        val input = context.contentResolver.openInputStream(uri) ?: error("Cannot open archive: $uri")
        input.use { source ->
            target.outputStream().buffered().use { output ->
                val buffer = ByteArray(64 * 1024)
                var copied = 0L
                while (true) {
                    val count = source.read(buffer)
                    if (count < 0) break
                    if (copied > KnowledgeArchivePolicy.MAX_ARCHIVE_BYTES - count) {
                        error("Archive exceeds the ${KnowledgeArchivePolicy.MAX_ARCHIVE_BYTES / 1024 / 1024} MB limit")
                    }
                    copied += count
                    output.write(buffer, 0, count)
                }
            }
        }
        return target
    } catch (e: Exception) {
        resultOf { target.delete() }
        throw e
    }
}

internal class ZipArchiveEntryReader(input: InputStream) : ArchiveEntryReader {
    private val stream = ZipInputStream(input, java.nio.charset.Charset.forName("GBK"))
    private var currentEntryOpen = false

    override fun next(): ArchiveEntry? {
        if (currentEntryOpen) {
            stream.closeEntry()
            currentEntryOpen = false
        }
        val entry = stream.nextEntry ?: return null
        currentEntryOpen = true
        return ArchiveEntry(
            name = entry.name,
            isDirectory = entry.isDirectory,
            size = entry.size.takeIf { it >= 0 },
            openStream = {
                object : FilterInputStream(stream) {
                    private var closed = false

                    override fun close() {
                        if (closed) return
                        closed = true
                        if (currentEntryOpen) {
                            stream.closeEntry()
                            currentEntryOpen = false
                        }
                    }
                }
            },
        )
    }

    override fun close() = stream.close()
}

internal class SevenZArchiveEntryReader(file: File) : ArchiveEntryReader {
    private val archive = SevenZFile.builder()
        .setFile(file)
        .setMaxMemoryLimitKiB(KnowledgeArchivePolicy.MAX_DECODER_MEMORY_KIB)
        .get()

    override fun next(): ArchiveEntry? {
        val entry = archive.nextEntry ?: return null
        return ArchiveEntry(
            name = entry.name.orEmpty(),
            isDirectory = entry.isDirectory,
            size = entry.size.takeIf { it >= 0 },
            openStream = { archive.getInputStream(entry) },
        )
    }

    override fun close() = archive.close()
}

internal class RarArchiveEntryReader(file: File) : ArchiveEntryReader {
    private val archive = Archive(
        file,
        ArchiveOptions.builder()
            .maxDictionarySize(KnowledgeArchivePolicy.MAX_DECODER_MEMORY_BYTES)
            .build(),
    )

    override fun next(): ArchiveEntry? {
        val header = archive.nextFileHeader() ?: return null
        return ArchiveEntry(
            name = header.fileName.orEmpty(),
            isDirectory = header.isDirectory,
            isEncrypted = header.isEncrypted,
            size = header.fullUnpackSize.takeIf { it >= 0 },
            openStream = { error("RAR entries must use bounded synchronous extraction") },
            extractToFile = { output -> archive.extractFile(header, output) },
        )
    }

    override fun close() = archive.close()
}

private fun overLimit(count: Int, units: Long): Boolean =
    count >= KnowledgeArchivePolicy.MAX_ENTRIES || units >= KnowledgeArchivePolicy.MAX_TOTAL_BYTES

private suspend fun scanArchiveEntries(
    reader: ArchiveEntryReader,
    format: ArchiveFormat,
    archiveName: String,
    kbId: String,
    deps: ArchiveImportDeps,
): ArchiveScanResult {
    var imported = 0
    var skipped = 0
    var failed = 0
    var count = 0
    var totalUnits = 0L

    while (!overLimit(count, totalUnits)) {
        val entry = try {
            reader.next()
        } catch (e: java.util.zip.ZipException) {
            Logger.w("KnowledgeArchiveImporter", "Archive stream ended after a format error: ${e.message}")
            null
        } ?: break

        count++
        val result = try {
            processArchiveEntry(entry, format, archiveName, kbId, deps)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w("KnowledgeArchiveImporter", "Archive entry import failed: ${entry.name}", e)
            ArchiveEntryResult(ArchiveEntryOutcome.FAILED, 0L)
        }
        totalUnits += result.units
        when (result.outcome) {
            ArchiveEntryOutcome.IMPORTED -> {
                imported++
                deps.onProgress(deps.context.getString(R.string.knowledge_zip_progress, imported))
            }
            ArchiveEntryOutcome.SKIPPED -> skipped++
            ArchiveEntryOutcome.FAILED -> failed++
            ArchiveEntryOutcome.IGNORED -> Unit
        }
    }

    return ArchiveScanResult(imported, skipped, failed)
}

private suspend fun processArchiveEntry(
    entry: ArchiveEntry,
    format: ArchiveFormat,
    archiveName: String,
    kbId: String,
    deps: ArchiveImportDeps,
): ArchiveEntryResult {
    if (entry.isDirectory || KnowledgeArchivePolicy.isJunk(entry.name)) {
        return ArchiveEntryResult(ArchiveEntryOutcome.IGNORED, 0L)
    }
    if (entry.isEncrypted) return ArchiveEntryResult(ArchiveEntryOutcome.SKIPPED, 0L)

    val entryName = entry.name.replace('\\', '/')
    val kind = KnowledgeArchivePolicy.classify(entryName)
    if (kind == ArchiveEntryKind.SKIP) return ArchiveEntryResult(ArchiveEntryOutcome.SKIPPED, 0L)
    if (entry.extractToFile != null && (entry.size ?: 0L) > KnowledgeArchivePolicy.MAX_ENTRY_BYTES) {
        return ArchiveEntryResult(ArchiveEntryOutcome.FAILED, 0L)
    }
    if (format == ArchiveFormat.RAR && kind == ArchiveEntryKind.TEXT) {
        return importRarTextEntry(entry, entryName, archiveName, kbId, deps)
    }
    return when (kind) {
        ArchiveEntryKind.TEXT -> ArchiveEntryResult(
            ArchiveEntryOutcome.IMPORTED,
            importArchiveTextEntry(entry, entryName, format, archiveName, kbId, deps),
        )
        ArchiveEntryKind.PARSED -> ArchiveEntryResult(
            ArchiveEntryOutcome.IMPORTED,
            importArchiveParsedEntry(entry, entryName, format, archiveName, kbId, deps),
        )
        ArchiveEntryKind.SKIP -> ArchiveEntryResult(ArchiveEntryOutcome.SKIPPED, 0L)
    }
}

private suspend fun importRarTextEntry(
    entry: ArchiveEntry,
    entryName: String,
    archiveName: String,
    kbId: String,
    deps: ArchiveImportDeps,
): ArchiveEntryResult {
    val tempDir = File(deps.context.cacheDir, "archive_entry_import").apply { mkdirs() }
    val tempFile = File.createTempFile("rar-text-", ".txt", tempDir)
    try {
        copyArchiveEntryToFile(entry, tempFile, KnowledgeArchivePolicy.MAX_ENTRY_BYTES)
        val fileEntry = entry.copy(
            openStream = { tempFile.inputStream() },
            extractToFile = null,
        )
        return ArchiveEntryResult(
            ArchiveEntryOutcome.IMPORTED,
            importArchiveTextEntry(
                fileEntry,
                entryName,
                ArchiveFormat.RAR,
                archiveName,
                kbId,
                deps,
            ),
        )
    } finally {
        resultOf { tempFile.delete() }
    }
}

private suspend fun importArchiveTextEntry(
    entry: ArchiveEntry,
    entryName: String,
    format: ArchiveFormat,
    archiveName: String,
    kbId: String,
    deps: ArchiveImportDeps,
): Long {
    val title = entryName.substringAfterLast('/')
    val docId = "doc-${System.currentTimeMillis()}-${UUID.randomUUID()}"
    val now = System.currentTimeMillis()
    val ragConfig = deps.settings.getRagConfig()
    deps.docDao.upsert(
        KnowledgeDocEntity(
            id = docId,
            title = title,
            content = "",
            filePath = "${format.sourceScheme}://$archiveName/$entryName",
            fileType = KnowledgeArchivePolicy.textFileType(title),
            createdAt = now,
            updatedAt = now,
            kbId = kbId,
        ),
    )

    val preview = StringBuilder()
    val digest = MessageDigest.getInstance("SHA-256")
    var seenChars = 0L
    var truncated = false
    val textFlow = flow {
        entry.openStream().bufferedReader().use { reader ->
            val buffer = CharArray(64 * 1024)
            while (true) {
                val count = reader.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                val remaining = KnowledgeArchivePolicy.MAX_TEXT_CHARS - seenChars
                if (remaining <= 0) {
                    truncated = true
                    break
                }
                val take = minOf(count.toLong(), remaining).toInt()
                val piece = String(buffer, 0, take)
                if (preview.length < KnowledgeArchivePolicy.MAX_PREVIEW_CHARS) {
                    preview.append(
                        buffer,
                        0,
                        minOf(take, KnowledgeArchivePolicy.MAX_PREVIEW_CHARS - preview.length),
                    )
                }
                digest.update(piece.toByteArray(Charsets.UTF_8))
                seenChars += take
                emit(piece)
                if (take < count) {
                    truncated = true
                    break
                }
            }
        }
        if (truncated) {
            emit(deps.context.getString(R.string.chat_doc_truncated, KnowledgeArchivePolicy.MAX_TEXT_CHARS))
        }
    }.flowOn(Dispatchers.IO)

    val chunks = try {
        deps.ragService.indexDocumentStreamed(docId, textFlow, ragConfig)
    } catch (e: CancellationException) {
        resultOf { deps.ragService.deleteDocument(docId) }
        throw e
    } catch (e: Exception) {
        resultOf { deps.ragService.deleteDocument(docId) }
        throw e
    }
    if (chunks <= 0) {
        resultOf { deps.ragService.deleteDocument(docId) }
        error("Empty content: $title")
    }
    deps.docDao.upsert(
        (deps.docDao.getById(docId) ?: error("Document missing: $docId")).copy(
            content = preview.toString(),
            chunkCount = chunks,
            embeddingModel = RagConfig.embeddingModelKey(ragConfig),
            contentHash = digest.digest().joinToString("") { "%02x".format(it) },
            updatedAt = System.currentTimeMillis(),
        ),
    )
    return seenChars
}

private suspend fun importArchiveParsedEntry(
    entry: ArchiveEntry,
    entryName: String,
    format: ArchiveFormat,
    archiveName: String,
    kbId: String,
    deps: ArchiveImportDeps,
): Long {
    val title = entryName.substringAfterLast('/')
    val tempDir = File(deps.context.cacheDir, "archive_entry_import").apply { mkdirs() }
    val tempName = "${UUID.randomUUID()}-${KnowledgeArchivePolicy.sanitizeTempName(entryName)}"
    val tempFile = File(tempDir, tempName)
    try {
        val used = copyArchiveEntryToFile(entry, tempFile, KnowledgeArchivePolicy.MAX_ENTRY_BYTES)
        val ragConfig = deps.settings.getRagConfig()
        val content = deps.documentParser
            .parseResult(
                Uri.fromFile(tempFile),
                deps.context,
                ragConfig.documentParserType,
                ragConfig.cloudParserEndpoint,
                ragConfig.mineruEndpoint,
                ragConfig.mineruToken,
            )
            .getOrNull()
            .orEmpty()
        if (content.isBlank()) error("Empty content: $title")

        val docId = "doc-${System.currentTimeMillis()}-${UUID.randomUUID()}"
        val now = System.currentTimeMillis()
        deps.docDao.upsert(
            KnowledgeDocEntity(
                id = docId,
                title = title,
                content = content.take(KnowledgeArchivePolicy.MAX_PREVIEW_CHARS),
                filePath = "${format.sourceScheme}://$archiveName/$entryName",
                fileType = KnowledgeArchivePolicy.parsedFileType(title),
                createdAt = now,
                updatedAt = now,
                kbId = kbId,
            ),
        )
        val chunks = try {
            deps.ragService.indexDocument(docId, content, ragConfig)
        } catch (e: CancellationException) {
            resultOf { deps.ragService.deleteDocument(docId) }
            throw e
        } catch (e: Exception) {
            resultOf { deps.ragService.deleteDocument(docId) }
            throw e
        }
        if (chunks <= 0) {
            resultOf { deps.ragService.deleteDocument(docId) }
            error("Index failed: $title")
        }
        deps.docDao.upsert(
            (deps.docDao.getById(docId) ?: error("Document missing: $docId")).copy(
                chunkCount = chunks,
                embeddingModel = RagConfig.embeddingModelKey(ragConfig),
                contentHash = RagService.computeContentHash(content),
                updatedAt = System.currentTimeMillis(),
            ),
        )
        return used
    } finally {
        resultOf { tempFile.delete() }
    }
}

private fun copyArchiveEntryToFile(entry: ArchiveEntry, target: File, capBytes: Long): Long {
    var used = 0L
    target.outputStream().buffered().use { output ->
        if (entry.extractToFile != null) {
            val boundedOutput = object : OutputStream() {
                private fun requireSpace(count: Int) {
                    if (used > capBytes - count) error("Archive entry exceeds the extraction limit")
                }

                override fun write(value: Int) {
                    requireSpace(1)
                    output.write(value)
                    used++
                }

                override fun write(bytes: ByteArray, offset: Int, length: Int) {
                    requireSpace(length)
                    output.write(bytes, offset, length)
                    used += length
                }

                override fun flush() = output.flush()
            }
            entry.extractToFile.invoke(boundedOutput)
            boundedOutput.flush()
        } else {
            entry.openStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (used > capBytes - count) error("Archive entry exceeds the extraction limit")
                    used += count
                    output.write(buffer, 0, count)
                }
            }
        }
    }
    return used
}
