package io.zer0.muse.data.plugin

import android.content.Context
import java.io.File

/**
 * 插件间**共享**的键值空间（`storage.shared:<名字>`）。
 *
 * 默认每个插件的 KV 是隔离的（`workspace/plugins/<id>/` 里）。共享空间存在的理由是
 * 明确的协作场景：一个插件抓数据、另一个展示；或者用户想让两个插件用同一份配置。
 *
 * 三条约束，都是为了"共享"不变成"串味"：
 *
 *  1. **必须显式声明**：清单里写 `storage.shared:notes` 才能碰 `notes` 这个名字。
 *     不声明就仍然隔离 —— 共享不是默认行为。
 *  2. **双方都要声明**：A 能写、B 没声明，B 就读不到。这样"谁能看到我的数据"
 *     由双方各自的清单决定，而不是由先来后到决定。
 *  3. **名字空间有限且合法**：只允许小写字母/数字/下划线/短横线，1–32 字符，
 *     避免出现 `../` 这种把路径拼出沙盒的名字。
 *
 * 容量与单插件 KV 一致（8MB / 单值 256KB），但**按名字空间计**：
 * 共享空间是多方共用的，不能让某一方把额度吃光后其它插件也跟着写不进。
 */
object PluginSharedStorage {

    /** 声明前缀：`storage.shared:<名字>`。 */
    const val CAPABILITY_PREFIX = "storage.shared:"

    /** 名字空间最大长度。 */
    const val MAX_NAMESPACE_CHARS = 32

    /** 共享空间的根目录名（在应用私有目录下）。 */
    const val ROOT_DIR_NAME = "plugin-shared-kv"

    /** 从清单能力里取出本插件声明过的共享名字空间。 */
    fun declaredNamespaces(capabilities: List<String>): Set<String> = capabilities.asSequence()
        .map { it.trim() }
        .filter { it.startsWith(CAPABILITY_PREFIX) }
        .map { it.removePrefix(CAPABILITY_PREFIX).trim() }
        .filter { isValidNamespace(it) }
        .toSet()

    /**
     * 名字是否合法。
     *
     * 判的是"能不能安全地当目录名"，而不是"像不像英文标识符"：
     * 中文名对路径完全安全，而这个应用是中文优先的 —— 作者写 `storage.shared:任务清单`
     * 是再自然不过的事，不该被一条 ASCII 白名单挡回去。
     *
     * 真正危险的是能改变路径结构的字符：分隔符、控制字符、以及 `..`。
     */
    fun isValidNamespace(name: String): Boolean {
        if (name.length !in 1..MAX_NAMESPACE_CHARS) return false
        if (name != name.trim()) return false
        if (name.any { it == '/' || it == '\\' || it.isISOControl() }) return false
        return !name.contains("..")
    }

    /** 某个名字空间的目录；名字非法时返回 null（调用方据此拒绝，而不是拼一个可疑路径）。 */
    fun dirFor(context: Context, namespace: String): File? {
        if (!isValidNamespace(namespace)) return null
        return File(File(context.applicationContext.filesDir, ROOT_DIR_NAME), namespace)
    }

    /** 某个名字空间的 KV 文件。 */
    fun fileFor(context: Context, namespace: String): File? = dirFor(context, namespace)?.let { File(it, "kv.json") }
}
