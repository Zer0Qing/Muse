package io.zer0.muse.automation.executors

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import io.zer0.muse.automation.core.AutomationExecutor
import io.zer0.muse.automation.core.PermissionLevel
import io.zer0.muse.automation.core.ScreenInfo
import io.zer0.muse.automation.core.UiNode
import io.zer0.common.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Shell 执行器 —— 第二层 UI 自动化。
 *
 * 两种工作模式:
 * 1. 已 root 设备: 通过 su 执行命令(此时由 [RootExecutor] 继承使用)
 * 2. Shizuku/adb: 通过 `adb shell` 或 Shizuku 服务执行
 *
 * 未授权时所有命令返回失败,但不会崩溃。
 *
 * 注: 纯 `adb shell` 无法直接持久化(App 进程不能继承 adb shell 的 uid)。
 * 当前实现通过 [io.zer0.muse.tools.system.ShizukuAuthorizer] 复用已授权的 Shizuku UserService；
 * RootExecutor 复用本类的 `su` 原语作为降级通道，普通应用 `sh` 不再被当成第二层权限。
 */
open class ShellExecutor(
    protected val context: Context,
    /** 第二层必须显式走 Shizuku；null 仅供 RootExecutor 复用本类的 su 原语。 */
    protected val shizukuAuthorizer: io.zer0.muse.tools.system.ShizukuAuthorizer? = null,
) : AutomationExecutor {

    override val level = PermissionLevel.SHELL

    /** 仅在 RootExecutor 复用时使用本地 su；第二层不会以普通 sh 冒充授权。 */
    protected open val shellPrefix: List<String> = listOf("sh")

    override suspend fun isAvailable(): Boolean = withContext(Dispatchers.IO) {
        if (shizukuAuthorizer != null) return@withContext shizukuAuthorizer.checkPermission()
        val result = exec("id")
        result.isSuccess && result.getOrDefault("").isNotBlank()
    }

    // ── 屏幕读取 ──────────────────────────────────────────────

    override suspend fun screenshot(): ByteArray? = withContext(Dispatchers.IO) {
        try {
            // Shizuku AIDL 传输文本，使用 base64 保证 PNG 二进制不被破坏。
            val result = if (shizukuAuthorizer != null) {
                exec("screencap -p | base64").mapCatching {
                    android.util.Base64.decode(it.filterNot(Char::isWhitespace), android.util.Base64.DEFAULT)
                }
            } else {
                execBinary("screencap -p")
            }
            if (result.isSuccess) result.getOrNull() else null
        } catch (e: Exception) {
            Logger.w(TAG, "screenshot failed: ${e.message}")
            null
        }
    }

    override suspend fun readScreen(): ScreenInfo = withContext(Dispatchers.IO) {
        val pkg = currentPackage()
        // dumpsys window 拿到当前焦点窗口和控件信息(比无障碍粗)
        val dump = exec("dumpsys window displays").getOrDefault("")
        val activityName = Regex("mCurrentFocus=.*?\\s+([\\w.]+/[\\w.$]+)")
            .find(dump)?.groupValues?.get(1)

        // uiautomator dump 可以拿到完整控件树 XML,但较慢(约 200-500ms)
        val nodes = dumpUiAutomator()

        val metrics = context.resources.displayMetrics
        ScreenInfo(
            packageName = pkg,
            activityName = activityName,
            nodes = nodes,
            screenWidth = metrics.widthPixels,
            screenHeight = metrics.heightPixels,
            source = "shell",
        )
    }

    override suspend fun currentPackage(): String? = withContext(Dispatchers.IO) {
        val result = exec("dumpsys window | grep mCurrentFocus")
        result.getOrNull()?.let { output ->
            Regex("([\\w.]+)/[\\w.\$]+").find(output)?.groupValues?.get(1)
        }
    }

    // ── 输入动作 ──────────────────────────────────────────────

    override suspend fun tap(x: Int, y: Int): Boolean =
        exec("input tap $x $y").isSuccess

    override suspend fun longPress(x: Int, y: Int, durationMs: Long): Boolean = withContext(Dispatchers.IO) {
        // input swipe 同坐标 + 时长 模拟长按
        exec("input swipe $x $y $x $y $durationMs").isSuccess
    }

    override suspend fun swipe(
        x1: Int, y1: Int,
        x2: Int, y2: Int,
        durationMs: Long,
    ): Boolean = exec("input swipe $x1 $y1 $x2 $y2 $durationMs").isSuccess

    override suspend fun inputText(text: String): Boolean = withContext(Dispatchers.IO) {
        // input text 不支持中文,中文走剪贴板 + KEYCODE_PASTE
        val escaped = text.replace(" ", "%s").replace("\"", "\\\"")
        val asciiOk = text.all { it.code < 128 }
        if (asciiOk) {
            exec("input text \"$escaped\"").isSuccess
        } else {
            // 写剪贴板需要 service call,这里用 base64 + 粘贴
            try {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("auto", text))
                // KEYCODE_PASTE = 279
                exec("input keyevent 279").isSuccess
            } catch (e: Exception) {
                Logger.w(TAG, "inputText i18n failed: ${e.message}")
                false
            }
        }
    }

    override suspend fun pressKey(keyCode: Int): Boolean =
        exec("input keyevent $keyCode").isSuccess

    override suspend fun launchApp(packageName: String): Boolean =
        exec("monkey -p $packageName -c android.intent.category.LAUNCHER 1").isSuccess

    override suspend fun openNotifications(): Boolean = withContext(Dispatchers.IO) {
        // 展开通知栏: cmd statusbar expand-notifications (API 24+) 或 service call
        val result = if (Build.VERSION.SDK_INT >= 24) {
            exec("cmd statusbar expand-notifications")
        } else {
            exec("service call statusbar 1")
        }
        result.isSuccess
    }

    override suspend fun openQuickSettings(): Boolean = withContext(Dispatchers.IO) {
        val result = if (Build.VERSION.SDK_INT >= 24) {
            exec("cmd statusbar expand-settings")
        } else {
            exec("service call statusbar 2")
        }
        result.isSuccess
    }

    // ── 命令执行 ──────────────────────────────────────────────

    data class ExecResult(val exitCode: Int, val stdout: String) {
        fun isSuccess() = exitCode == 0
        fun getOrDefault(default: String) = if (isSuccess()) stdout else default
    }

    /** 执行 shell 命令,返回 stdout 字符串。 */
    suspend fun exec(command: String): Result<String> = withContext(Dispatchers.IO) {
        if (shizukuAuthorizer != null) {
            if (!shizukuAuthorizer.checkPermission()) {
                return@withContext Result.failure(IllegalStateException("Shizuku 未授权"))
            }
            val result = shizukuAuthorizer.execute(command)
            return@withContext if (result.isSuccess) {
                Result.success(result.stdout)
            } else {
                Logger.w(TAG, "Shizuku exec failed (exit=${result.exitCode}): $command -> ${result.stderr.take(200)}")
                Result.failure(IllegalStateException(result.stderr.ifBlank { "Shizuku 命令执行失败" }))
            }
        }
        runCatching {
            val proc = ProcessBuilder(shellPrefix + listOf("-c", command))
                .redirectErrorStream(true)
                .start()
            val output = proc.inputStream.bufferedReader().use { it.readText() }
            val exit = proc.waitFor()
            if (exit != 0) {
                Logger.w(TAG, "exec failed (exit=$exit): $command -> ${output.take(200)}")
            }
            output
        }.onFailure { Logger.w(TAG, "exec error: ${it.message}") }
    }

    private suspend fun execBinary(command: String): Result<ByteArray> = withContext(Dispatchers.IO) {
        runCatching {
            val proc = ProcessBuilder(shellPrefix + listOf("-c", command))
                .redirectErrorStream(false)
                .start()
            val out = proc.inputStream.readBytes()
            proc.waitFor()
            out
        }
    }

    // ── uiautomator dump ──────────────────────────────────────

    private suspend fun dumpUiAutomator(): List<UiNode> = withContext(Dispatchers.IO) {
        try {
            // uiautomator dump 把 XML 写到文件,再读出来解析
            val dumpPath = "/sdcard/window_dump_${System.currentTimeMillis()}.xml"
            exec("uiautomator dump --compressed $dumpPath")
            val xml = exec("cat $dumpPath").getOrDefault("")
            exec("rm $dumpPath") // 清理
            parseUiAutomatorXml(xml)
        } catch (e: Exception) {
            Logger.w(TAG, "uiautomator dump failed: ${e.message}")
            emptyList()
        }
    }

    private fun parseUiAutomatorXml(xml: String): List<UiNode> {
        if (xml.isBlank()) return emptyList()
        val nodes = mutableListOf<UiNode>()
        // 轻量正则解析(不引 XML 解析器): 提取 <node ... />
        val nodeRegex = Regex("<node\\b([^>]*?)/?>")
        for (match in nodeRegex.findAll(xml)) {
            val attrs = match.groupValues[1]
            fun attr(name: String): String? =
                Regex("$name=\"([^\"]*)\"").find(attrs)?.groupValues?.get(1)

            val text = attr("text")?.takeIf { it.isNotBlank() }
            val desc = attr("content-desc")?.takeIf { it.isNotBlank() }
            val clickable = attr("clickable") == "true"
            val editable = attr("class")?.contains("EditText") == true
            val scrollable = attr("scrollable") == "true"
            val enabled = attr("enabled") != "false"
            val checked = when (attr("checked")) {
                "true" -> true
                "false" -> false
                else -> null
            }
            // bounds="[x1,y1][x2,y2]"
            val bounds = attr("bounds")?.let { b ->
                Regex("\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]").find(b)?.let { m ->
                    intArrayOf(
                        m.groupValues[1].toInt(), m.groupValues[2].toInt(),
                        m.groupValues[3].toInt(), m.groupValues[4].toInt(),
                    )
                }
            }

            // 只要有信息的节点
            if (text != null || desc != null || clickable || editable) {
                nodes.add(
                    UiNode(
                        text = text,
                        contentDescription = desc,
                        className = attr("class"),
                        viewIdResourceName = attr("resource-id"),
                        boundsLeft = bounds?.get(0) ?: 0,
                        boundsTop = bounds?.get(1) ?: 0,
                        boundsRight = bounds?.get(2) ?: 0,
                        boundsBottom = bounds?.get(3) ?: 0,
                        isClickable = clickable,
                        isEditable = editable,
                        isScrollable = scrollable,
                        isChecked = checked,
                        isEnabled = enabled,
                    ),
                )
            }
        }
        return nodes
    }

    companion object {
        private const val TAG = "ShellExec"
    }
}
