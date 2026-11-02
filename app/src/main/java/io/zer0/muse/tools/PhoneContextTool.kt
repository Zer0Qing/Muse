package io.zer0.muse.tools

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import androidx.core.content.ContextCompat
import io.zer0.muse.context.PhoneContext
import io.zer0.muse.context.PhoneContextDispatcher
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * `phone_context`：把手机侧的个人数据按**统一信封**交给模型（对比清单 P2-02）。
 *
 * 与"再加几个查询工具"的区别在**说清状态**：每一项都带来源、覆盖范围、权限状态，
 * 并且把"未授权 / 设备不支持 / 确实没有"三种情况分开表述 ——
 * 现在模型看到空结果只会说"没有"，用户不知道该去开权限还是真的没数据。
 *
 * 本轮接入两个来源（日历、应用使用），其余明确报"尚未接入"而不是静默跳过。
 */
object PhoneContextTool {

    const val NAME = "phone_context"

    /** 单次最多返回多少条，避免把上下文撑爆。 */
    private const val MAX_ITEMS = 50

    fun toolDef() = ToolRegistry.ToolDef(
        name = NAME,
        description = "读取手机上下文（日历 / 应用使用 / 联系人 / 通知）。" +
            "每项都会标明来源、覆盖范围与权限状态；「未授权」和「确实没有」是两回事，请按提示处理。",
        parameters = mapOf(
            "sources" to "可选，逗号分隔：calendar / app_usage / contacts / notifications；默认 calendar",
            "days" to "可选，覆盖最近几天，默认 1",
        ),
        required = emptySet(),
        category = "built-in",
        parameterTypes = mapOf("days" to "integer"),
        riskLevel = ToolRiskLevel.HIGH,
    )

    fun execute(args: Map<String, String>, context: Context): String {
        val sources = args["sources"]
            ?.split(',')
            ?.mapNotNull { PhoneContext.Source.parse(it) }
            ?.takeIf { it.isNotEmpty() }
            ?: listOf(PhoneContext.Source.CALENDAR)
        val days = (args["days"]?.toIntOrNull() ?: 1).coerceIn(0, 30)
        val providers = mapOf(
            PhoneContext.Source.CALENDAR to PhoneContextDispatcher.Provider { window -> loadCalendar(context, window) },
            PhoneContext.Source.APP_USAGE to PhoneContextDispatcher.Provider { window -> loadAppUsage(context, window) },
            // 联系人：本应用还没有查询入口（只有 add_contact），如实报"尚未接入"
            PhoneContext.Source.CONTACTS to PhoneContextDispatcher.Provider {
                PhoneContextDispatcher.Loaded.unavailable("这个应用还没有接入联系人读取")
            },
            // 通知：没有历史存储（只有实时监听），同样如实报
            PhoneContext.Source.NOTIFICATIONS to PhoneContextDispatcher.Provider {
                PhoneContextDispatcher.Loaded.unavailable("这个应用还没有通知历史存储，无法回溯")
            },
        )
        return PhoneContextDispatcher.describe(
            sources = sources,
            days = days,
            nowMillis = System.currentTimeMillis(),
            providers = providers,
        )
    }

    /** 日历：复用 [CalendarSemantics] 的 Instances 查询，重复日程会按实例展开。 */
    private fun loadCalendar(context: Context, days: Int): PhoneContextDispatcher.Loaded {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return PhoneContextDispatcher.Loaded.denied("需要日历读取权限")
        }
        val now = System.currentTimeMillis()
        val start = now - days.coerceAtLeast(0) * 24L * 60 * 60 * 1000
        val uri = CalendarSemantics.instancesUri(start, now)
        val formatter = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
        val items = mutableListOf<String>()
        var truncated = false
        runCatching {
            context.contentResolver.query(uri, CalendarSemantics.INSTANCE_PROJECTION, null, null, null)?.use { cursor ->
                val titleIdx = cursor.getColumnIndexOrThrow(android.provider.CalendarContract.Instances.TITLE)
                val beginIdx = cursor.getColumnIndexOrThrow(android.provider.CalendarContract.Instances.BEGIN)
                while (cursor.moveToNext()) {
                    if (items.size >= MAX_ITEMS) {
                        truncated = true
                        break
                    }
                    val title = cursor.getString(titleIdx) ?: "(无标题)"
                    items += "${formatter.format(Date(cursor.getLong(beginIdx)))} $title"
                }
            }
        }.onFailure {
            return PhoneContextDispatcher.Loaded.unavailable("读取日历时出错：${it.message ?: it.javaClass.simpleName}")
        }
        return PhoneContextDispatcher.Loaded.available(items, truncated)
    }

    /** 应用使用：需要"使用情况访问"特殊权限，用 AppOps 判断而不是普通运行时权限。 */
    private fun loadAppUsage(context: Context, days: Int): PhoneContextDispatcher.Loaded {
        val appOps = context.getSystemService(AppOpsManager::class.java)
        val mode = appOps?.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName,
        )
        if (mode != AppOpsManager.MODE_ALLOWED) {
            return PhoneContextDispatcher.Loaded.denied("需要在系统设置里授予「使用情况访问」权限")
        }
        val manager = context.getSystemService(android.app.usage.UsageStatsManager::class.java)
            ?: return PhoneContextDispatcher.Loaded.unavailable("这台设备没有使用情况统计服务")
        val now = System.currentTimeMillis()
        val start = now - days.coerceAtLeast(0) * 24L * 60 * 60 * 1000
        val stats = runCatching { manager.queryUsageStats(android.app.usage.UsageStatsManager.INTERVAL_DAILY, start, now) }
            .getOrNull()
            .orEmpty()
        val items = stats
            .filter { it.totalTimeInForeground > 0 }
            .sortedByDescending { it.totalTimeInForeground }
            .take(MAX_ITEMS)
            .map { stat ->
                val minutes = stat.totalTimeInForeground / 60_000
                "${stat.packageName} | 前台 ${minutes} 分钟"
            }
        return PhoneContextDispatcher.Loaded.available(items, truncated = stats.size > MAX_ITEMS)
    }
}
