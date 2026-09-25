package io.zer0.muse.tools

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.mockk.mockk
import io.zer0.muse.tools.defaultTool.UIToolsRegistrar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * P0-3 回归:工具风险等级三处台账一致性护栏。
 *
 * 三份台账:
 *  1. 注册台账 — 各 Registrar 注册 ToolDef 时的 riskLevel 字段
 *  2. 显式风险表 — [ToolPermissionResolver.explicitRiskTable](唯一真源)
 *  3. 运行时解析 — [ToolPermissionResolver.riskLevelFor](显式表 + 前缀推断)
 *
 * 约束:
 *  - 任何在显式风险表中出现的工具,注册台账不得低于表内等级(尤其 HIGH 不能被降级为 NORMAL,
 *    否则子代理 / 定时任务 / TRUSTED 模式会绕过审批);
 *  - riskLevelFor 对已知高风险工具必须解析为 HIGH,与注册值无关。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ToolRiskLedgerConsistencyTest {

    private fun buildLedger(): ToolRegistry {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val registry = ToolRegistry(context)
        // 与 App 启动链路一致地实例化注册器(见 ToolRegistrarBootstrapper 的注册器清单)。
        // 覆盖必须显式:构造失败的注册器要列进 UNAVAILABLE_REGISTRARS,两边都没有的注册器会让本测试失败——
        // 否则台账覆盖会随重构悄悄缩水,一致性护栏就形同不存在。
        val unavailable = mutableListOf<String>()
        REGISTRARS.forEach { (name, build) ->
            runCatching { build(context, registry) }.onFailure { unavailable += name }
        }
        val unexpected = unavailable - UNAVAILABLE_REGISTRARS
        assertTrue(
            "以下注册器无法实例化,风险台账出现覆盖缺口(若刻意为请加入 UNAVAILABLE_REGISTRARS 并写明理由):\n" +
                unexpected.joinToString("\n"),
            unexpected.isEmpty(),
        )
        assertTrue(
            "注册器覆盖过少(${REGISTRARS.size - unavailable.size}/${REGISTRARS.size}),台账护栏已退化",
            REGISTRARS.size - unavailable.size >= 12,
        )
        // pin_memory / unpin_memory 由 AgentToolsRegistrar 动态注册,静态 toolDef 直接并入台账
        registry.register(PinMemoryTool.toolDef()) { PinMemoryTool.execute(emptyMap(), mockk()) }
        registry.register(UnpinMemoryTool.toolDef()) { UnpinMemoryTool.execute(emptyMap(), mockk()) }
        return registry
    }

    private companion object {
        /** 未纳入构造的注册器:每项都必须有理由,避免覆盖被静默削减。 */
        val UNAVAILABLE_REGISTRARS: Set<String> = setOf("AgentToolsRegistrar")

        /**
         * 与 `ToolRegistrarBootstrapper` 的注册器清单保持一致的构造表。
         *
         * 只构造(注册发生在各 Registrar 的 init{});本测试只关心注册出来的 ToolDef 名字与风险等级,
         * 不执行任何工具,所以依赖重的用 relaxed mock 即可。
         */
        val REGISTRARS: List<Pair<String, (Context, ToolRegistry) -> Unit>> = listOf(
            "EncodingToolsRegistrar" to { c, r -> EncodingToolsRegistrar(c, r) },
            "CoreToolsRegistrar" to { c, r -> CoreToolsRegistrar(c, r) },
            "WeatherToolsRegistrar" to { c, r -> WeatherToolsRegistrar(c, r) },
            "ClipboardToolsRegistrar" to { c, r -> ClipboardToolsRegistrar(c, r) },
            "NetworkTextToolsRegistrar" to { c, r -> NetworkTextToolsRegistrar(c, r) },
            "ReminderToolsRegistrar" to { c, r -> ReminderToolsRegistrar(c, r) },
            "CalendarToolsRegistrar" to { c, r -> CalendarToolsRegistrar(c, r) },
            "PhoneToolsRegistrar" to { c, r -> PhoneToolsRegistrar(c, r) },
            "SystemToolsRegistrar" to { c, r -> SystemToolsRegistrar(c, r) },
            "ResourceToolsRegistrar" to { c, r -> ResourceToolsRegistrar(c, r) },
            "QuickNoteToolsRegistrar" to { c, r -> QuickNoteToolsRegistrar(c, r) },
            "ScheduledTaskToolsRegistrar" to { c, r -> ScheduledTaskToolsRegistrar(c, r) },
            "TranslateToolsRegistrar" to { _, r -> TranslateToolsRegistrar(r) },
            "TtsToolsRegistrar" to { c, r -> TtsToolsRegistrar(c, r) },
            "WorkspaceToolsRegistrar" to { _, r ->
                WorkspaceToolsRegistrar(r, mockk(relaxed = true))
            },
            "FileToolsRegistrar" to { c, r -> FileToolsRegistrar(r, c, c.filesDir) },
            "ShellSandboxToolRegistrar" to { c, r -> ShellSandboxToolRegistrar(r, c.filesDir) },
            "UIToolsRegistrar" to { c, r ->
                UIToolsRegistrar(r, mockk(relaxed = true), c)
            },
            "PdfVisionToolsRegistrar" to { c, r ->
                PdfVisionToolsRegistrar(r, mockk(relaxed = true), c, c.filesDir)
            },
            // P2-23: 媒体生成工具(图片/视频/二维码),原由 ChatViewModel 注册
            "MediaGenToolsRegistrar" to { c, r ->
                MediaGenToolsRegistrar(
                    r,
                    MediaGenToolsImpl(c, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true)),
                )
            },
            // v2.0: OAuth 连接器工具(connector_list / call_connector)
            "ConnectorToolsRegistrar" to { _, r ->
                ConnectorToolsRegistrar(r, mockk(relaxed = true))
            },
            // v2.0.1: 插件市场工具(plugin_market_search / plugin_market_install)
            "PluginMarketToolsRegistrar" to { _, r ->
                PluginMarketToolsRegistrar(r, mockk(relaxed = true))
            },
        )
    }

    @Test
    fun `registration ledger must not downgrade tools below explicit risk table`() {
        val registry = buildLedger()
        val explicitTable = ToolPermissionResolver.explicitRiskTable()

        val mismatches = registry.listTools()
            .filter { def -> explicitTable[def.name]?.let { it != def.riskLevel } == true }
            .map { def ->
                "${def.name}: registered=${def.riskLevel}, table=${explicitTable[def.name]}"
            }
            .sorted()

        assertTrue(
            "注册台账与显式风险表不一致(注册值低于唯一真源,会导致子代理/定时任务/TRUSTED 绕过审批):\n" +
                mismatches.joinToString("\n"),
            mismatches.isEmpty(),
        )
    }

    @Test
    fun `high risk tools resolve to HIGH regardless of registration`() {
        val highNames = listOf(
            "send_email", "open_url", "open_maps",
            "toggle_wifi", "toggle_bluetooth", "toggle_flashlight",
            "set_brightness", "set_volume", "set_alarm", "set_timer",
            "workspace_write", "workspace_delete", "workspace_mkdir", "workspace_move",
            "pin_memory", "unpin_memory", "make_phone_call",
        )
        for (name in highNames) {
            assertEquals("$name 必须解析为 HIGH", ToolRiskLevel.HIGH, ToolPermissionResolver.riskLevelFor(name))
        }
    }

    @Test
    fun `safe and normal tools keep their classification`() {
        assertEquals(ToolRiskLevel.SAFE, ToolPermissionResolver.riskLevelFor("web_search"))
        assertEquals(ToolRiskLevel.SAFE, ToolPermissionResolver.riskLevelFor("read_file"))
        assertEquals(ToolRiskLevel.SAFE, ToolPermissionResolver.riskLevelFor("get_current_time"))
        assertEquals(ToolRiskLevel.NORMAL, ToolPermissionResolver.riskLevelFor("write_file"))
        assertEquals(ToolRiskLevel.NORMAL, ToolPermissionResolver.riskLevelFor("notify"))
        assertEquals(ToolRiskLevel.NORMAL, ToolPermissionResolver.riskLevelFor("schedule_reminder"))
    }
}
