package io.zer0.muse.workspace

import android.content.Context
import android.net.Uri
import io.zer0.common.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * 工作区管理器。
 *
 * 工作区由若干条「挂载」组成，每条挂载是一块单独授权的目录，自带能力位
 * （能否列目录、能否读、能否写）。默认挂载 [WorkspaceMount.MAIN_ID] 指向应用沙盒内的
 * 固定目录，路径不写前缀时一律落到它上面——老数据、老工具调用、老模型习惯全部照常。
 *
 * 路径语法：`挂载标识:/相对路径`。前缀只认已注册的标识，`a/b.jpg` 这种普通相对路径
 * 不会被误判成挂载。
 *
 * 安全边界：每条挂载的 [WorkspaceFs] 自己负责把相对路径钉在自己的根目录里；
 * 本类额外做三道校验——能力位、`..` 段、会话可见范围。
 *
 * @param context 应用 Context（用于定位 filesDir）
 */
class WorkspaceManager(private val context: Context) {

    /** 默认挂载的根目录，沿用历史路径。 */
    val rootDir: File = File(context.filesDir, MAIN_DIR_NAME).apply {
        if (!exists()) mkdirs()
    }

    private val mountStore = WorkspaceMountStore(context)

    private val bindingStore = WorkspaceSessionBindingStore(context)

    private val fsLock = Any()

    private val fsCache = HashMap<String, WorkspaceFs>()

    /**
     * 按路径分配的写锁。同一文件的读—改—写串行，不同文件并行。
     *
     * 约定：`受锁方法内部不要再调用会加锁的公开方法` —— 这里用的是不可重入的
     * [kotlinx.coroutines.sync.Mutex]，重入会等到超时。
     */
    private val pathLocks = WorkspacePathLock()

    // ── 挂载管理 ──────────────────────────────────────────────────────────────

    /** 全部挂载，默认挂载排第一。 */
    suspend fun mounts(): List<WorkspaceMount> = mountStore.mounts()

    /** 按标识查一条挂载。 */
    suspend fun findMount(id: String): WorkspaceMount? = mountStore.find(id)

    /**
     * 新增或更新一条挂载。
     *
     * 只登记授权，不动磁盘上的文件。
     */
    suspend fun upsertMount(mount: WorkspaceMount): WorkspaceMountStore.MountStoreResult {
        val result = mountStore.upsert(mount)
        if (result is WorkspaceMountStore.MountStoreResult.Success) invalidateFs(mount.id)
        return result
    }

    /**
     * 移除一条挂载。
     *
     * 同时清掉各会话里对它的引用，避免留下悬空标识。
     *
     * @return 是否真的删掉了一条
     */
    suspend fun removeMount(id: String): Boolean {
        val removed = mountStore.remove(id)
        if (removed) {
            invalidateFs(id)
            bindingStore.forgetMount(id)
        }
        return removed
    }

    /** 记录一次使用时间，供界面排序。 */
    suspend fun touchMount(id: String) {
        mountStore.touch(id)
    }

    /** 某个会话可用的挂载标识；null 表示不限制。 */
    suspend fun sessionMountIds(sessionId: String?): Set<String>? = bindingStore.allowedMountIds(sessionId)

    /** 设置某个会话可用的挂载；传空集合表示取消限制。 */
    suspend fun setSessionMounts(sessionId: String, ids: Set<String>) {
        bindingStore.setAllowed(sessionId, ids)
    }

    // ── 内部：解析目标 ────────────────────────────────────────────────────────

    /** 一次解析的结果：落在哪条挂载、相对路径、用哪个文件系统。 */
    private class Target(
        val mount: WorkspaceMount,
        val relative: String,
        val fs: WorkspaceFs,
        val display: String,
    )

    /**
     * 把一条用户路径解析成可执行的目标。
     *
     * 三段校验：挂载是否存在、本会话是否可见、能力位是否满足；再拒绝 `..` 段。
     *
     * @param path 原始路径，可带 `mount:/` 前缀
     * @param need 本次操作需要的能力，null 表示不校验
     * @param scope 本会话可用的挂载集合，null 表示不限制
     * @param allowRoot 是否允许直接指向挂载根目录
     */
    private suspend fun target(path: String, need: MountCapability?, scope: Set<String>?, allowRoot: Boolean): Target {
        val ids = mountStore.mountIds()
        val parsed = WorkspacePathParser.parse(path, ids)
        val mountId = parsed.mountId ?: WorkspacePathParser.DEFAULT_MOUNT_ID
        val mount = mountStore.find(mountId) ?: throw WorkspaceIoException("挂载不存在: $mountId")
        checkVisible(mount, scope)
        checkCapability(mount, need)
        checkPath(parsed, allowRoot)
        return Target(mount, parsed.relative, fsFor(mount), displayOf(mount, parsed.relative))
    }

    /** 会话可见范围校验。 */
    private fun checkVisible(mount: WorkspaceMount, scope: Set<String>?) {
        if (scope != null && mount.id !in scope) {
            throw WorkspaceIoException("「${mount.label}」不在本会话可用范围")
        }
    }

    /** 能力位校验。 */
    private fun checkCapability(mount: WorkspaceMount, need: MountCapability?) {
        if (need != null && need !in mount.capabilities) {
            throw WorkspaceIoException(deniedMessage(mount, need))
        }
    }

    /** 路径段校验：拒绝 `..`；按需拒绝挂载根目录本身。 */
    private fun checkPath(parsed: WorkspacePath, allowRoot: Boolean) {
        if (WorkspacePathParser.hasParentSegment(parsed.relative)) {
            throw WorkspaceIoException("路径不能包含 ..")
        }
        if (!allowRoot && parsed.isMountRoot) {
            throw WorkspaceIoException("该操作不能直接作用于挂载根目录")
        }
    }

    /** 能力不足时的提示语。 */
    private fun deniedMessage(mount: WorkspaceMount, need: MountCapability): String = when (need) {
        MountCapability.WRITE -> "「${mount.label}」是只读挂载，不能写入、删除或移动"
        MountCapability.READ -> "「${mount.label}」没有读取权限"
        MountCapability.LIST -> "「${mount.label}」不能列目录"
    }

    /** 取（并缓存）一条挂载的文件系统。 */
    private fun fsFor(mount: WorkspaceMount): WorkspaceFs {
        synchronized(fsLock) {
            fsCache[mount.id]?.let { return it }
            val fs = when (mount.provider) {
                MountProvider.LOCAL -> {
                    val root = mount.rootPath?.takeIf { it.isNotBlank() }
                        ?: throw WorkspaceIoException("挂载「${mount.label}」缺少目录路径")
                    LocalWorkspaceFs(File(root))
                }
                MountProvider.SAF -> {
                    val uri = mount.treeUri?.takeIf { it.isNotBlank() }
                        ?: throw WorkspaceIoException("挂载「${mount.label}」缺少目录授权")
                    SafWorkspaceFs(context, Uri.parse(uri))
                }
            }
            fsCache[mount.id] = fs
            return fs
        }
    }

    private fun invalidateFs(mountId: String) {
        synchronized(fsLock) { fsCache.remove(mountId) }
    }

    /** 默认挂载用相对路径，其他挂载加回前缀，便于调用方原样回传。 */
    private fun displayOf(mount: WorkspaceMount, relative: String): String =
        if (mount.id == WorkspaceMount.MAIN_ID) relative else WorkspacePathParser.format(mount.id, relative)

    // ── 内部：路径锁 ──────────────────────────────────────────────────────────

    /** 在 [t] 这条路径的锁内执行 [block]。 */
    private suspend fun <T> lockedBy(t: Target, block: suspend () -> T): T =
        pathLocks.withLock(t.mount.id, t.relative, t.display, block)

    /**
     * 同时在两条路径的锁内执行 [block]（移动、复制这类要改两头的操作）。
     *
     * 按键排序获取，`A→B` 与 `B→A` 并发时两边都从同一个键开始，不会互等。
     */
    private suspend fun <T> lockedByBoth(first: Target, second: Target, block: suspend () -> T): T {
        val keyA = pathLocks.keyOf(first.mount.id, first.relative)
        val keyB = pathLocks.keyOf(second.mount.id, second.relative)
        if (keyA == keyB) return lockedBy(first, block)
        val (head, tail) = if (keyA < keyB) first to second else second to first
        return lockedBy(head) { lockedBy(tail, block) }
    }

    // ── 目录与文件 ────────────────────────────────────────────────────────────

    /**
     * 列目录。
     *
     * 默认挂载的根目录额外带上其余挂载：根目录是所有入口的起点，
     * 只列一个盘的内容会让别的挂载在默认视图里隐身。
     *
     * @param relativePath 相对路径，可带挂载前缀，空串表示默认挂载根目录
     * @param scope 本会话可用的挂载集合，null 表示不限制
     */
    suspend fun listDir(relativePath: String, scope: Set<String>? = null): ListResult = withContext(Dispatchers.IO) {
        try {
            val t = target(relativePath, MountCapability.LIST, scope, allowRoot = true)
            val entries = t.fs.list(t.relative).map { entry ->
                WorkspaceEntry(
                    name = entry.name,
                    relativePath = displayOf(t.mount, entry.relativePath),
                    isDirectory = entry.isDirectory,
                    size = entry.size,
                    lastModified = entry.lastModified,
                )
            }
            val atDefaultRoot = t.mount.id == WorkspaceMount.MAIN_ID && t.relative.isEmpty()
            ListResult.Success(if (atDefaultRoot) otherMountEntries(scope) + entries else entries)
        } catch (e: WorkspaceIoException) {
            ListResult.Error(e.message ?: "列目录失败")
        }
    }

    /** 根视图里的其余挂载入口：一条挂载一行，回传路径写成 `挂载标识:/` 即落在该挂载。 */
    private suspend fun otherMountEntries(scope: Set<String>?): List<WorkspaceEntry> =
        mountStore.mounts()
            .filter { it.id != WorkspaceMount.MAIN_ID }
            .filter { scope == null || it.id in scope }
            .filter { it.canList }
            .map { mount ->
                WorkspaceEntry(
                    name = mount.id,
                    relativePath = WorkspacePathParser.format(mount.id, ""),
                    isDirectory = true,
                    size = 0L,
                    lastModified = mount.lastUsedAt,
                )
            }

    /**
     * 读文本文件（上限 [MAX_READ_BYTES]）。
     *
     * 整份读要过路径锁：同一份文件正在被写时，读到的必须是写完之后的内容，
     * 不能是截断到一半的中间态。`workspace_edit` 的读—判—写另见 [editFile]。
     */
    suspend fun readFile(relativePath: String, scope: Set<String>? = null): ReadResult = withContext(Dispatchers.IO) {
        try {
            val t = target(relativePath, MountCapability.READ, scope, allowRoot = false)
            lockedBy(t) {
                val size = t.fs.size(t.relative)
                if (size > MAX_READ_BYTES) {
                    return@lockedBy ReadResult.Error("文件过大($size 字节),读取上限为 $MAX_READ_BYTES 字节")
                }
                if (!t.fs.exists(t.relative) || t.fs.isDirectory(t.relative)) {
                    return@lockedBy ReadResult.Error("文件不存在: ${t.display}")
                }
                ReadResult.Success(t.fs.readText(t.relative))
            }
        } catch (e: WorkspaceIoException) {
            ReadResult.Error(e.message ?: "读取失败")
        }
    }

    /** 读文本文件；大文件按字符区间分页。 */
    suspend fun readFilePage(
        relativePath: String,
        offsetChars: Int? = null,
        lengthChars: Int? = null,
        scope: Set<String>? = null,
    ): ReadResult = withContext(Dispatchers.IO) {
        try {
            val t = target(relativePath, MountCapability.READ, scope, allowRoot = false)
            lockedBy(t) {
                if (!t.fs.exists(t.relative) || t.fs.isDirectory(t.relative)) {
                    return@lockedBy ReadResult.Error("文件不存在: ${t.display}")
                }
                val paginated = offsetChars != null || lengthChars != null || t.fs.size(t.relative) > MAX_READ_BYTES
                val content = if (paginated) {
                    t.fs.readTextRange(
                        relative = t.relative,
                        offsetChars = offsetChars ?: 0,
                        lengthChars = lengthChars ?: io.zer0.muse.tools.TOOL_OUTPUT_READ_PAGE_CHARS,
                    )
                } else {
                    t.fs.readText(t.relative)
                }
                ReadResult.Success(content)
            }
        } catch (e: WorkspaceIoException) {
            ReadResult.Error(e.message ?: "读取失败")
        }
    }

    /** 读原始字节（文档预览等二进制场景）。 */
    suspend fun readBytes(relativePath: String, scope: Set<String>? = null): ByteArray? = withContext(Dispatchers.IO) {
        runCatching {
            val t = target(relativePath, MountCapability.READ, scope, allowRoot = false)
            t.fs.readBytes(t.relative)
        }.getOrNull()
    }

    /** 写文本文件（同一路径的写入串行，不同文件并行）。 */
    suspend fun writeFile(relativePath: String, content: String, scope: Set<String>? = null): OpResult = withContext(Dispatchers.IO) {
        if (content.toByteArray(Charsets.UTF_8).size.toLong() > MAX_WRITE_BYTES) {
            return@withContext OpResult.Error("内容过大,写入上限为 $MAX_WRITE_BYTES 字节")
        }
        try {
            val t = target(relativePath, MountCapability.WRITE, scope, allowRoot = false)
            lockedBy(t) { t.fs.writeText(t.relative, content, append = false) }
            OpResult.Success
        } catch (e: WorkspaceIoException) {
            OpResult.Error(e.message ?: "写入失败")
        }
    }

    /**
     * 读—改—写：在同一把路径锁内读完整个文件、交给 [decide] 判定、按判定结果写回。
     *
     * 锁必须盖住从读到写的整段，否则两个并发调用会各自读到旧内容再各自写回，
     * 后写的那次把先写的那次丢掉，而两边都返回成功。
     *
     * @param relativePath 文件路径
     * @param scope 本会话可用的挂载集合，null 表示不限制
     * @param decide 在完整原文上做判定；只做纯计算，不要再发起会加锁的调用
     */
    suspend fun editFile(
        relativePath: String,
        scope: Set<String>? = null,
        decide: (original: String) -> EditDecision,
    ): EditResult = withContext(Dispatchers.IO) {
        try {
            val t = target(relativePath, MountCapability.READ, scope, allowRoot = false)
            lockedBy(t) {
                val size = t.fs.size(t.relative)
                if (size > MAX_READ_BYTES) {
                    return@lockedBy EditResult.Failed("文件过大($size 字节),读取上限为 $MAX_READ_BYTES 字节")
                }
                if (!t.fs.exists(t.relative) || t.fs.isDirectory(t.relative)) {
                    return@lockedBy EditResult.Failed("文件不存在: ${t.display}")
                }
                commitEdit(relativePath, scope, t.fs.readText(t.relative), decide)
            }
        } catch (e: WorkspaceIoException) {
            EditResult.Failed(e.message ?: "编辑失败")
        }
    }

    /** [editFile] 持锁段的后半：判定 + 写回。调用方必须已经持有该路径的锁。 */
    private suspend fun commitEdit(
        relativePath: String,
        scope: Set<String>?,
        original: String,
        decide: (original: String) -> EditDecision,
    ): EditResult {
        val decision = decide(original)
        if (decision is EditDecision.Abort) return EditResult.Rejected(decision.message)
        val content = (decision as EditDecision.Commit).content
        if (content.toByteArray(Charsets.UTF_8).size.toLong() > MAX_WRITE_BYTES) {
            return EditResult.Failed("内容过大,写入上限为 $MAX_WRITE_BYTES 字节")
        }
        // 写回前才判写权限：只读挂载上读取是合法的，该报的是「不能写」。
        val writable = target(relativePath, MountCapability.WRITE, scope, allowRoot = false)
        writable.fs.writeText(writable.relative, content, append = false)
        return EditResult.Applied(original, content)
    }

    /** 追加写入。 */
    suspend fun appendFile(relativePath: String, content: String, scope: Set<String>? = null): OpResult = withContext(Dispatchers.IO) {
        if (content.toByteArray(Charsets.UTF_8).size.toLong() > MAX_WRITE_BYTES) {
            return@withContext OpResult.Error("内容过大,单次追加上限为 $MAX_WRITE_BYTES 字节")
        }
        try {
            val t = target(relativePath, MountCapability.WRITE, scope, allowRoot = false)
            lockedBy(t) { t.fs.writeText(t.relative, content, append = true) }
            OpResult.Success
        } catch (e: WorkspaceIoException) {
            OpResult.Error(e.message ?: "追加失败")
        }
    }

    /** 删除文件或目录（目录递归）。 */
    suspend fun delete(relativePath: String, scope: Set<String>? = null): OpResult = withContext(Dispatchers.IO) {
        try {
            val t = target(relativePath, MountCapability.WRITE, scope, allowRoot = false)
            lockedBy(t) { t.fs.delete(t.relative) }
            OpResult.Success
        } catch (e: WorkspaceIoException) {
            OpResult.Error(e.message ?: "删除失败")
        }
    }

    /** 创建目录（含多级）。 */
    suspend fun mkdir(relativePath: String, scope: Set<String>? = null): OpResult = withContext(Dispatchers.IO) {
        try {
            val t = target(relativePath, MountCapability.WRITE, scope, allowRoot = false)
            lockedBy(t) { t.fs.mkdirs(t.relative) }
            OpResult.Success
        } catch (e: WorkspaceIoException) {
            OpResult.Error(e.message ?: "目录创建失败")
        }
    }

    /** 移动或重命名（限同一条挂载内）。源与目标两条路径都上锁。 */
    suspend fun move(from: String, to: String, scope: Set<String>? = null): OpResult = withContext(Dispatchers.IO) {
        try {
            val src = target(from, MountCapability.WRITE, scope, allowRoot = false)
            val dst = target(to, MountCapability.WRITE, scope, allowRoot = false)
            if (src.mount.id != dst.mount.id) {
                return@withContext OpResult.Error("不能跨挂载移动,请改用复制")
            }
            lockedByBoth(src, dst) { src.fs.move(src.relative, dst.relative) }
            OpResult.Success
        } catch (e: WorkspaceIoException) {
            OpResult.Error(e.message ?: "移动失败")
        }
    }

    /** 复制（同一挂载内直接复制，跨挂载按字节搬运）。源与目标两条路径都上锁。 */
    suspend fun copy(from: String, to: String, scope: Set<String>? = null): OpResult = withContext(Dispatchers.IO) {
        try {
            val src = target(from, MountCapability.READ, scope, allowRoot = false)
            val dst = target(to, MountCapability.WRITE, scope, allowRoot = false)
            lockedByBoth(src, dst) { copyLocked(src, dst) }
        } catch (e: WorkspaceIoException) {
            OpResult.Error(e.message ?: "复制失败")
        }
    }

    /** 持锁段内的复制本体。 */
    private suspend fun copyLocked(src: Target, dst: Target): OpResult {
        if (src.mount.id == dst.mount.id) {
            src.fs.copy(src.relative, dst.relative)
            return OpResult.Success
        }
        if (src.fs.isDirectory(src.relative)) return OpResult.Error("跨挂载只能复制单个文件")
        val bytes = src.fs.readBytes(src.relative)
            ?: return OpResult.Error("读取源文件失败: ${src.display}")
        bytes.inputStream().use { dst.fs.writeStream(dst.relative, it, MAX_WRITE_BYTES) }
        return OpResult.Success
    }

    // ── 搜索 ──────────────────────────────────────────────────────────────────

    /**
     * 在工作区内搜索内容。
     *
     * 遍历本会话可见的挂载，逐文件扫描。跳过超限文件、二进制文件与无法解码的文件。
     *
     * @param pattern 要找的文本，或正则（[isRegex] 为真时）
     * @param relativePath 搜索起点，默认全部挂载根目录
     * @param scope 本会话可用的挂载集合，null 表示不限制
     */
    suspend fun grep(
        pattern: String,
        relativePath: String = "",
        isRegex: Boolean = false,
        maxResults: Int = DEFAULT_GREP_MAX_RESULTS,
        scope: Set<String>? = null,
    ): GrepResult = withContext(Dispatchers.IO) {
        if (pattern.isEmpty()) return@withContext GrepResult.Error("pattern 不能为空")
        val regex = if (isRegex) {
            matchOrNull(pattern) ?: return@withContext GrepResult.Error("正则语法错误: $pattern")
        } else {
            null
        }
        try {
            val starts = resolveGrepStarts(relativePath, scope)
            val matches = mutableListOf<GrepMatch>()
            var scanned = 0
            var truncated = false
            for (start in starts) {
                if (matches.size >= maxResults) {
                    truncated = true
                    break
                }
                val outcome = scanMount(start, regex, pattern, maxResults, matches)
                scanned += outcome.scanned
                if (outcome.reachedCap) truncated = true
            }
            GrepResult.Success(matches, scanned, truncated)
        } catch (e: WorkspaceIoException) {
            GrepResult.Error(e.message ?: "搜索失败")
        }
    }

    /** 一次扫描的结果：扫了多少文件、是否撞上命中上限。 */
    private data class ScanOutcome(val scanned: Int, val reachedCap: Boolean)

    /** 搜索起点：一条路径对应一个挂载；空路径时展开到所有可见挂载。 */
    private suspend fun resolveGrepStarts(relativePath: String, scope: Set<String>?): List<Target> {
        val ids = mountStore.mountIds()
        val parsed = WorkspacePathParser.parse(relativePath, ids)
        if (parsed.mountId != null || parsed.relative.isNotEmpty()) {
            return listOf(target(relativePath, MountCapability.READ, scope, allowRoot = true))
        }
        return mountStore.mounts()
            .filter { scope == null || it.id in scope }
            .filter { MountCapability.READ in it.capabilities }
            .map { Target(it, "", fsFor(it), it.id) }
    }

    /** 扫描一条挂载，把命中追加到 [out]。 */
    private suspend fun scanMount(
        start: Target,
        regex: Regex?,
        pattern: String,
        maxResults: Int,
        out: MutableList<GrepMatch>,
    ): ScanOutcome {
        val files = try {
            start.fs.walkFiles(start.relative, MAX_GREP_FILES)
        } catch (e: WorkspaceIoException) {
            Logger.w(TAG, "grep 遍历失败: ${e.message}")
            return ScanOutcome(0, false)
        }
        var scanned = 0
        for (rel in files) {
            if (out.size >= maxResults) return ScanOutcome(scanned, true)
            scanned++
            if (collectHits(start, rel, regex, pattern, maxResults, out)) return ScanOutcome(scanned, true)
        }
        return ScanOutcome(scanned, false)
    }

    /** 扫一个文件并把命中写进 [out]；返回是否因为达到上限而要停。 */
    private suspend fun collectHits(
        start: Target,
        rel: String,
        regex: Regex?,
        pattern: String,
        maxResults: Int,
        out: MutableList<GrepMatch>,
    ): Boolean {
        val size = runCatching { start.fs.size(rel) }.getOrDefault(0L)
        if (size > MAX_GREP_FILE_BYTES) return false
        val text = runCatching { start.fs.readText(rel) }.getOrNull() ?: return false
        if (text.indexOf('\u0000') >= 0) return false
        val display = displayOf(start.mount, rel)
        text.lineSequence().forEachIndexed { idx, line ->
            if (out.size >= maxResults) return@forEachIndexed
            val hit = if (regex != null) regex.containsMatchIn(line) else line.contains(pattern)
            if (hit) out.add(GrepMatch(display, idx + 1, line.take(MAX_GREP_LINE_CHARS)))
        }
        return out.size >= maxResults
    }

    /** 编译正则，语法非法时返回 null。 */
    private fun matchOrNull(pattern: String): Regex? = try {
        Regex(pattern)
    } catch (e: Exception) {
        Logger.w(TAG, "grep 正则编译失败: ${e.message}")
        null
    }

    // ── 导入 ──────────────────────────────────────────────────────────────────

    /**
     * 从系统文件选择器选中的 URI 导入一个文件。
     *
     * 流式拷贝，不把整个文件读进内存；目标重名时自动追加序号。
     * 序号判定与落盘在同一次持锁内完成（见 [importInto]）：
     * 只锁写入的话，两个并发导入会算出同一个序号，后写的那次把先写的覆盖掉。
     * URI 只在本次调用期间读取，不保留长期授权。
     *
     * @param uri 系统选择器返回的文件 URI
     * @param targetDirectory 目标目录，空串表示默认挂载根目录
     * @param displayName 可选目标文件名，为空时向系统查询
     */
    suspend fun importFile(uri: Uri, targetDirectory: String, displayName: String? = null, scope: Set<String>? = null): FileImportResult =
        withContext(Dispatchers.IO) {
            try {
                val dir = target(targetDirectory, MountCapability.WRITE, scope, allowRoot = true)
                val rawName = displayName?.trim()?.takeIf { it.isNotEmpty() }
                    ?: queryDisplayName(uri)
                    ?: "imported-file"
                importInto(dir, sanitizeImportedName(rawName), uri, scope)
            } catch (e: WorkspaceIoException) {
                FileImportResult.Error(e.message ?: "文件导入失败")
            } catch (e: Exception) {
                Logger.w(TAG, "importFile 失败: ${e.message}", e)
                FileImportResult.Error("文件导入失败: ${e.message}")
            }
        }

    /**
     * 持锁的导入本体：先在「目标目录 + 基础文件名」这把锁下算序号，再落盘。
     *
     * 对外层这把基础名锁，是为了让同名的并发导入排队而不是各自算出一模一样的序号；
     * 对实际文件名再取一把锁，是为了跟显式写这个文件的调用也串行。
     * 两把锁只有导入这条路径会同时持有，且顺序固定（先基础名、后实际名）；
     * 若与 move/copy 这类双路径操作碰到同一对路径，最坏是一次等锁超时，不会永久挂住。
     */
    private suspend fun importInto(dir: Target, safeName: String, uri: Uri, scope: Set<String>?): FileImportResult {
        val base = target(displayOf(dir.mount, joinRelative(dir.relative, safeName)), MountCapability.WRITE, scope, allowRoot = false)
        return lockedBy(base) {
            val candidateRel = uniqueName(dir, safeName)
            val dest = target(displayOf(dir.mount, candidateRel), MountCapability.WRITE, scope, allowRoot = false)
            if (pathLocks.keyOf(dest.mount.id, dest.relative) == pathLocks.keyOf(base.mount.id, base.relative)) {
                streamInto(dest, uri)
            } else {
                lockedBy(dest) { streamInto(dest, uri) }
            }
        }
    }

    /** 把 URI 流式拷进 [dest]（调用方负责持锁）。 */
    private suspend fun streamInto(dest: Target, uri: Uri): FileImportResult {
        val input = context.contentResolver.openInputStream(uri)
            ?: return FileImportResult.Error("无法读取所选文件")
        var bytes = 0L
        input.use { stream -> bytes = dest.fs.writeStream(dest.relative, stream, MAX_IMPORT_BYTES) }
        return FileImportResult.Success(dest.display, bytes)
    }

    /** 在目标目录里找一个不冲突的文件名。 */
    private suspend fun uniqueName(dir: Target, safeName: String): String {
        var candidate = joinRelative(dir.relative, safeName)
        if (!dir.fs.exists(candidate)) return candidate
        val dot = safeName.lastIndexOf('.')
        val stem = if (dot > 0) safeName.substring(0, dot) else safeName
        val extension = if (dot > 0) safeName.substring(dot) else ""
        var suffix = 1
        while (true) {
            candidate = joinRelative(dir.relative, "$stem ($suffix)$extension")
            if (!dir.fs.exists(candidate)) return candidate
            suffix++
        }
    }

    /** 把目录相对路径与文件名接起来。 */
    private fun joinRelative(parent: String, name: String): String = if (parent.isEmpty()) name else "$parent/$name"

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (index >= 0) cursor.getString(index) else null
            } else {
                null
            }
        }
    }.onFailure { Logger.w(TAG, "读取导入文件名失败: ${it.message}") }.getOrNull()

    private fun sanitizeImportedName(raw: String): String {
        val leaf = raw.trim().substringAfterLast('/').substringAfterLast('\\')
        val cleaned = leaf
            .replace(Regex("[\\u0000-\\u001F]"), "_")
            .replace("..", "_")
            .trim()
        return cleaned.take(MAX_FILE_NAME_LENGTH).ifBlank { "imported-file-${UUID.randomUUID().toString().take(8)}" }
    }

    // ── 结果类型（便于 UI/工具统一处理）──────────────────────────────────────

    /**
     * 工作区内的一条目录/文件条目。
     *
     * @param name 显示名（不含路径）
     * @param relativePath 可回传的路径：默认挂载为相对路径，其他挂载带 `mount:/` 前缀
     * @param isDirectory 是否为目录
     * @param size 字节数（目录恒为 0）
     * @param lastModified 最后修改时间戳（毫秒）
     */
    data class WorkspaceEntry(
        val name: String,
        val relativePath: String,
        val isDirectory: Boolean,
        val size: Long,
        val lastModified: Long,
    )

    /**
     * 一条内容命中。
     *
     * @param path 文件路径
     * @param lineNumber 行号（1 起）
     * @param line 命中行内容
     */
    data class GrepMatch(
        val path: String,
        val lineNumber: Int,
        val line: String,
    )

    /** [grep] 的结果。 */
    sealed class GrepResult {
        /**
         * @param matches 命中列表（最多 maxResults 条）
         * @param scannedFiles 实际扫描的文件数
         * @param truncated 是否因命中数达到上限而提前停止
         */
        data class Success(
            val matches: List<GrepMatch>,
            val scannedFiles: Int,
            val truncated: Boolean,
        ) : GrepResult()

        data class Error(val message: String) : GrepResult()
    }

    /** listDir 结果。 */
    sealed class ListResult {
        data class Success(val entries: List<WorkspaceEntry>) : ListResult()

        data class Error(val message: String) : ListResult()
    }

    /** readFile 结果。 */
    sealed class ReadResult {
        data class Success(val content: String) : ReadResult()

        data class Error(val message: String) : ReadResult()
    }

    /** 通用操作（write/delete/mkdir/move/copy）结果。 */
    sealed class OpResult {
        object Success : OpResult()

        data class Error(val message: String) : OpResult()
    }

    /**
     * 读—改—写里「改」这一步的结论，由 [editFile] 的调用方在完整原文上给出。
     */
    sealed class EditDecision {
        /** 放弃写入，[message] 原样回给调用方；文件保持原样。 */
        data class Abort(val message: String) : EditDecision()

        /** 写回 [content]。 */
        data class Commit(val content: String) : EditDecision()
    }

    /** [editFile] 的结果。 */
    sealed class EditResult {
        /** 已写回；[original] 与 [updated] 供调用方渲染 diff。 */
        data class Applied(val original: String, val updated: String) : EditResult()

        /** 判定阶段拒绝，文件未改动。 */
        data class Rejected(val message: String) : EditResult()

        /** 读或写失败（含等锁超时）。 */
        data class Failed(val message: String) : EditResult()
    }

    /** 文件导入结果。 */
    sealed class FileImportResult {
        data class Success(val relativePath: String, val bytes: Long) : FileImportResult()

        data class Error(val message: String) : FileImportResult()
    }

    companion object {
        private const val TAG = "WorkspaceManager"

        /** 默认挂载的目录名。 */
        private const val MAIN_DIR_NAME = "workspace"

        /** 读取文件大小上限：1MB。 */
        const val MAX_READ_BYTES: Long = 1L * 1024 * 1024

        /** 写入文件大小上限：10MB。 */
        const val MAX_WRITE_BYTES: Long = 10L * 1024 * 1024

        /** 文件导入上限：256MB，流式复制不产生整文件内存峰值。 */
        const val MAX_IMPORT_BYTES: Long = 256L * 1024 * 1024

        private const val MAX_FILE_NAME_LENGTH = 180
    }
}

/** grep 单文件大小上限：超过则不读。 */
private const val MAX_GREP_FILE_BYTES: Long = 2L * 1024 * 1024

/** grep 最多检查的文件数。 */
private const val MAX_GREP_FILES: Int = 2000

/** grep 默认命中数上限。 */
private const val DEFAULT_GREP_MAX_RESULTS: Int = 100

/** grep 命中行截断长度。 */
private const val MAX_GREP_LINE_CHARS: Int = 400
