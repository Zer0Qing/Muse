package io.zer0.muse.data.plugin

import android.content.Context
import java.io.File

/**
 * 插件总闸（安全模式用）：一个标志文件，关掉之后**所有插件的一切都不执行**。
 *
 * 与「插件总开关」（设置里的普通开关）刻意分成两层：
 *
 *  - 普通开关存在设置里，由 [io.zer0.muse.data.SettingsRepository] 读写，随设置导入导出走；
 *  - 这一层是**文件标志**，不依赖 Koin、不依赖 DataStore、不依赖数据库。
 *
 * 为什么非要单独一层：安全模式本身就是「上次启动崩了」的降级界面，
 * 那时 Koin 可能起不来、设置可能读不出、甚至崩溃原因就是某个插件 ——
 * 在这种界面上提供「关掉所有插件」，开关本身必须是最原始、最不可能失败的东西。
 *
 * 关掉之后的行为：
 *  - 工具/命令/界面提交：直接返回「所有插件已在安全模式中停用」；
 *  - 激活事件、钩子、定时任务、提示词注入：一律不派发；
 *  - 桥接动作：一律拒绝（不会出现"插件没跑但动作放行了"的缝）。
 *
 * 恢复只有一条路：用户在插件页把开关打开（[setDisabled] 传 false）。**不自动恢复** ——
 * 崩溃过一次的设备不该在下次启动时自己把可疑的东西放回来。
 */
object PluginKillSwitch {

    /** 标志文件名（放在应用私有目录）。 */
    const val FLAG_FILE = "plugins_disabled.flag"

    /** 关闭时给用户看的原因（工具结果、命令输出共用）。 */
    const val DISABLED_MESSAGE = "所有插件已在安全模式中停用（插件管理页可恢复）"

    fun flagFile(context: Context): File = File(context.applicationContext.filesDir, FLAG_FILE)

    /**
     * 用"同目录里的标志文件"判定。
     *
     * 给那些只拿得到某个 filesDir 下文件、拿不到 Context 的组件用
     * （例如权限账本只持有自己的 storeFile）。两者都在 filesDir 下，因此同目录。
     */
    fun isDisabledBeside(sibling: File): Boolean =
        runCatching { File(sibling.parentFile, FLAG_FILE).isFile }.getOrDefault(false)

    /** 是否已被总闸关掉。读文件失败按「未关闭」处理：不能因为读不到就让用户以为关了。 */
    fun isDisabled(context: Context): Boolean =
        runCatching { flagFile(context).isFile }.getOrDefault(false)

    /**
     * 设置总闸状态。
     *
     * @return 写入后是否处于关闭状态（写失败时返回真实状态，而不是乐观地返回目标值）
     */
    fun setDisabled(context: Context, disabled: Boolean): Boolean {
        val file = flagFile(context)
        if (disabled) {
            runCatching {
                file.parentFile?.mkdirs()
                file.writeText(System.currentTimeMillis().toString())
            }
        } else {
            runCatching { if (file.exists()) file.delete() }
        }
        return isDisabled(context)
    }
}
