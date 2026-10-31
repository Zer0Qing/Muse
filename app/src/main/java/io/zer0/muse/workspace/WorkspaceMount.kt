package io.zer0.muse.workspace

import kotlinx.serialization.Serializable

/**
 * 挂载的来源类型。
 *
 * - [LOCAL]：可直接用 [java.io.File] 访问的目录（应用私有目录、应用可读的共享目录）。
 * - [SAF]：通过系统目录选择器授权的目录，用持久化 URI 访问，绕开分区存储限制。
 */
@Serializable
enum class MountProvider {
    LOCAL,
    SAF,
}

/**
 * 一项挂载能力。
 *
 * 能力用枚举而不是布尔开关：同一份挂载可以「可读不可写」，
 * 也可以「能读能写但不出现在列表里」，互相独立。
 */
@Serializable
enum class MountCapability {
    /** 允许列出目录内容。 */
    LIST,

    /** 允许读取文件内容与搜索。 */
    READ,

    /** 允许写入、追加、删除、移动、创建目录。 */
    WRITE,
}

/**
 * 一块被授权给 AI 使用的目录。
 *
 * 工作区不再是一个固定沙盒，而是一组挂载。每条挂载自带能力位，
 * 路径里用 `id:/相对路径` 指到具体挂载；不带前缀时落到默认挂载 [MAIN_ID]。
 *
 * @param id 挂载标识，工具与路径里用它做前缀。只允许小写字母、数字、下划线与连字符。
 * @param label 展示名，可含中文，仅用于界面。
 * @param provider 访问方式，决定用 [java.io.File] 还是持久化 URI。
 * @param rootPath [MountProvider.LOCAL] 时的绝对路径。
 * @param treeUri [MountProvider.SAF] 时的持久化目录 URI 字符串。
 * @param capabilities 能力集合，缺项即拒绝。
 * @param system 系统内置挂载（默认工作区），不允许删除或改成只读。
 * @param createdAt 创建时间戳（毫秒）。
 * @param lastUsedAt 最近使用时间戳（毫秒），用于界面排序。
 */
@Serializable
data class WorkspaceMount(
    val id: String,
    val label: String,
    val provider: MountProvider,
    val rootPath: String? = null,
    val treeUri: String? = null,
    val capabilities: Set<MountCapability> = DEFAULT_CAPABILITIES,
    val system: Boolean = false,
    val createdAt: Long = 0L,
    val lastUsedAt: Long = 0L,
) {
    /** 是否允许列出目录。 */
    val canList: Boolean get() = MountCapability.LIST in capabilities

    /** 是否允许读取与搜索。 */
    val canRead: Boolean get() = MountCapability.READ in capabilities

    /** 是否允许写入类操作。 */
    val canWrite: Boolean get() = MountCapability.WRITE in capabilities

    /** 只读挂载：能看不能改。 */
    val isReadOnly: Boolean get() = !canWrite

    companion object {
        /** 默认挂载的固定标识。 */
        const val MAIN_ID: String = "main"

        /** 全能力：可列、可读、可写。 */
        val DEFAULT_CAPABILITIES: Set<MountCapability> = setOf(MountCapability.LIST, MountCapability.READ, MountCapability.WRITE)

        /** 只读能力：可列、可读。 */
        val READ_ONLY_CAPABILITIES: Set<MountCapability> = setOf(MountCapability.LIST, MountCapability.READ)

        /** 标识合法性：小写字母开头，允许小写字母、数字、下划线、连字符。 */
        private val ID_PATTERN = Regex("^[a-z][a-z0-9_-]{0,31}$")

        /** 判断一个标识是否符合命名规则。 */
        fun isValidId(id: String): Boolean = ID_PATTERN.matches(id)
    }
}
