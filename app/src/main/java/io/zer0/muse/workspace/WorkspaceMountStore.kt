package io.zer0.muse.workspace

import android.content.Context
import io.zer0.common.Logger
import io.zer0.common.resultOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 挂载表的持久化。
 *
 * 存一份 JSON 到应用私有目录：挂载是「配置」而不是「数据」，
 * 用文件比引一张新表更轻，也不用跟着数据库版本走迁移。
 *
 * 内置挂载不落盘，每次读取时现拼：它们指向应用沙盒内的固定目录，
 * 用户既删不掉也不该被改坏。
 *
 * @param context 应用 Context，用于定位 filesDir
 */
class WorkspaceMountStore(private val context: Context) {

    /** 挂载表文件。 */
    private val storeFile: File = File(context.filesDir, "workspace_mounts.json")

    /** 默认挂载对应的沙盒目录，沿用历史路径，老数据原地可用。 */
    private val mainDir: File = File(context.filesDir, MAIN_DIR_NAME)

    /** 工具长输出目录，只读内置挂载的根。 */
    private val toolOutputsDir: File = File(context.filesDir, TOOL_OUTPUTS_DIR_NAME)

    /** 读写互斥，避免界面与工具同时改表时互相覆盖。 */
    private val mutex = Mutex()

    /** 内存缓存；文件是唯一事实来源，缓存只用于减少重复解析。 */
    @Volatile
    private var cache: List<WorkspaceMount>? = null

    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    /**
     * 全部挂载，内置挂载在前，其余按落盘顺序。
     *
     * 内置挂载不参与持久化：即使文件为空或损坏，工作区也始终有一个可用的根，
     * 以及一个能被列出、能被读到的工具输出目录。
     */
    suspend fun mounts(): List<WorkspaceMount> = withContext(Dispatchers.IO) {
        mutex.withLock { builtInMounts() + readStored().filter { it.id !in BUILT_IN_MOUNT_IDS } }
    }

    /** 当前所有已注册的挂载标识。 */
    suspend fun mountIds(): Set<String> = mounts().mapTo(mutableSetOf()) { it.id }

    /** 按标识查找一条挂载，找不到返回 null。 */
    suspend fun find(id: String): WorkspaceMount? = mounts().firstOrNull { it.id == id }

    /** 默认挂载。 */
    fun mainMount(): WorkspaceMount {
        if (!mainDir.exists()) mainDir.mkdirs()
        return WorkspaceMount(
            id = WorkspaceMount.MAIN_ID,
            label = MAIN_LABEL,
            provider = MountProvider.LOCAL,
            rootPath = mainDir.absolutePath,
            capabilities = WorkspaceMount.DEFAULT_CAPABILITIES,
            system = true,
        )
    }

    /**
     * 工具长输出目录的只读挂载。
     *
     * 目录本身由工具写入（不受这份能力位约束）；挂在工作区上是给人/模型看的入口，
     * 所以只给列出与读取：从这里改写或删除工具输出没有任何正当用途。
     */
    fun toolOutputsMount(): WorkspaceMount {
        if (!toolOutputsDir.exists()) toolOutputsDir.mkdirs()
        return WorkspaceMount(
            id = TOOL_OUTPUTS_MOUNT_ID,
            label = TOOL_OUTPUTS_LABEL,
            provider = MountProvider.LOCAL,
            rootPath = toolOutputsDir.absolutePath,
            capabilities = WorkspaceMount.READ_ONLY_CAPABILITIES,
            system = true,
        )
    }

    /** 内置挂载（不落盘、不可删、不可改名）。 */
    private fun builtInMounts(): List<WorkspaceMount> = listOf(mainMount(), toolOutputsMount())

    /**
     * 新增或更新一条挂载。
     *
     * 校验：标识符合命名规则、非系统保留、LOCAL 挂载的目录必须存在、目录不与其他挂载重复。
     *
     * @return 成功后返回落盘后的挂载列表；失败返回带原因的错误
     */
    suspend fun upsert(mount: WorkspaceMount): MountStoreResult = withContext(Dispatchers.IO) {
        if (mount.id in BUILT_IN_MOUNT_IDS) {
            return@withContext MountStoreResult.Error("内置挂载不可修改")
        }
        if (!WorkspaceMount.isValidId(mount.id)) {
            return@withContext MountStoreResult.Error("挂载标识只能是小写字母开头的字母、数字、下划线或连字符")
        }
        if (mount.provider == MountProvider.LOCAL) {
            val root = mount.rootPath?.let(::File)
            if (root == null || !root.isDirectory) {
                return@withContext MountStoreResult.Error("目录不存在或不是文件夹")
            }
            if (root.absolutePath == toolOutputsDir.absolutePath) {
                return@withContext MountStoreResult.Error("工具输出目录已作为内置挂载存在,不需要再挂一次")
            }
        } else {
            if (mount.treeUri.isNullOrBlank()) {
                return@withContext MountStoreResult.Error("缺少目录授权")
            }
        }
        mutex.withLock {
            val stored = readStored()
            if (stored.any { it.id != mount.id && sameRoot(it, mount) }) {
                return@withLock MountStoreResult.Error("该目录已经挂载过了")
            }
            val next = stored.filter { it.id != mount.id } + mount
            if (!writeStored(next)) return@withLock MountStoreResult.Error("保存失败")
            cache = next
            MountStoreResult.Success(builtInMounts() + next)
        }
    }

    /**
     * 删除一条挂载。
     *
     * 只删登记，不动磁盘上的文件——挂载是一条授权记录，不是所有权。
     *
     * @return 是否真的删掉了一条
     */
    suspend fun remove(id: String): Boolean = withContext(Dispatchers.IO) {
        if (id in BUILT_IN_MOUNT_IDS) return@withContext false
        mutex.withLock {
            val stored = readStored()
            val next = stored.filter { it.id != id }
            if (next.size == stored.size) return@withLock false
            if (!writeStored(next)) return@withLock false
            cache = next
            true
        }
    }

    /** 记录一次使用，用于界面按最近使用排序。 */
    suspend fun touch(id: String): Unit = withContext(Dispatchers.IO) {
        if (id in BUILT_IN_MOUNT_IDS) return@withContext
        mutex.withLock {
            val stored = readStored()
            val index = stored.indexOfFirst { it.id == id }
            if (index < 0) return@withLock
            val next = stored.toMutableList()
            next[index] = next[index].copy(lastUsedAt = System.currentTimeMillis())
            if (writeStored(next)) cache = next
        }
    }

    /** 读取落盘的挂载表；文件缺失或损坏时返回空表，不让坏文件挡住整个工作区。 */
    private fun readStored(): List<WorkspaceMount> {
        cache?.let { return it }
        if (!storeFile.exists()) return emptyList()
        val parsed: List<WorkspaceMount> = resultOf {
            val text = storeFile.readText(Charsets.UTF_8)
            if (text.isBlank()) {
                emptyList()
            } else {
                json.decodeFromString(ListSerializer(WorkspaceMount.serializer()), text)
            }
        }.onError { message, _ -> Logger.w(TAG, "读取挂载表失败: $message") }
            .getOrNull() ?: return emptyList()
        val cleaned = parsed.filter { it.id !in BUILT_IN_MOUNT_IDS && WorkspaceMount.isValidId(it.id) }
        cache = cleaned
        return cleaned
    }

    /** 原子写入：先写临时文件再改名，避免中途被杀留下半份 JSON。 */
    private fun writeStored(mounts: List<WorkspaceMount>): Boolean = resultOf {
        val payload = json.encodeToString(ListSerializer(WorkspaceMount.serializer()), mounts)
        val temp = File(storeFile.parentFile, "${storeFile.name}.tmp")
        temp.writeText(payload, Charsets.UTF_8)
        if (!temp.renameTo(storeFile)) {
            storeFile.writeText(payload, Charsets.UTF_8)
            temp.delete()
        }
    }.onError { message, _ -> Logger.w(TAG, "写入挂载表失败: $message") }.isSuccess

    /** 两条挂载是否指向同一个位置。 */
    private fun sameRoot(a: WorkspaceMount, b: WorkspaceMount): Boolean = when {
        a.provider != b.provider -> false
        a.provider == MountProvider.SAF -> a.treeUri == b.treeUri
        else -> a.rootPath == b.rootPath
    }

    /** [upsert] 的结果。 */
    sealed class MountStoreResult {
        data class Success(val mounts: List<WorkspaceMount>) : MountStoreResult()

        data class Error(val message: String) : MountStoreResult()
    }

    companion object {
        private const val TAG = "WorkspaceMountStore"

        /** 默认挂载的目录名。 */
        private const val MAIN_DIR_NAME = "workspace"

        /** 默认挂载的展示名。 */
        private const val MAIN_LABEL = "工作区"

        /** 工具长输出的目录名，也是它作为内置挂载的标识。 */
        private const val TOOL_OUTPUTS_DIR_NAME = "tool_outputs"

        /** 工具输出挂载的标识。 */
        const val TOOL_OUTPUTS_MOUNT_ID: String = TOOL_OUTPUTS_DIR_NAME

        /** 工具输出挂载的展示名。 */
        private const val TOOL_OUTPUTS_LABEL = "工具输出"

        /** 内置挂载：不落盘、不可删除、不可改名。 */
        private val BUILT_IN_MOUNT_IDS: Set<String> = setOf(WorkspaceMount.MAIN_ID, TOOL_OUTPUTS_MOUNT_ID)
    }
}
