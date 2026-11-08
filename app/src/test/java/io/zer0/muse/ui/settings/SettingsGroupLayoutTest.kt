package io.zer0.muse.ui.settings

import io.zer0.muse.ui.navigation.KeepAliveSettingsRoute
import io.zer0.muse.ui.navigation.SettingsAppearanceRoute
import io.zer0.muse.ui.navigation.SettingsChatRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 设置入口清单的结构回归锁定。
 *
 * 首页 16 个分类、每类的条目与顺序、全局 47 项的完整性全部写在 [SettingsCatalog] 里，
 * 首页 / 分类页 / 搜索索引都从这份清单取数据。这里直接断言清单本身：
 * 换类、换顺序、把某一项贴到两类里、加一项漏一项，都会让下面的断言失败。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SettingsGroupLayoutTest {

    /** 16 个分类，按首页从上到下的顺序；每类的条目按分类页从上到下的顺序。 */
    private val expectedLayout: List<Pair<SettingsGroupKind, List<SettingsEntryId>>> = listOf(
        SettingsGroupKind.ASSISTANTS to listOf(
            SettingsEntryId.USER_PROFILE,
            SettingsEntryId.ASSISTANTS,
            SettingsEntryId.ASSISTANT_RESOURCES,
            SettingsEntryId.AGENT,
        ),
        SettingsGroupKind.CHAT to listOf(
            SettingsEntryId.CHAT,
        ),
        SettingsGroupKind.APPEARANCE to listOf(
            SettingsEntryId.APPEARANCE,
        ),
        SettingsGroupKind.MODELS to listOf(
            SettingsEntryId.PROVIDER,
            SettingsEntryId.TASK_ROUTING,
            SettingsEntryId.PROVIDER_PLUGINS,
        ),
        SettingsGroupKind.TOOLS to listOf(
            SettingsEntryId.TOOLS,
            SettingsEntryId.PERMISSION_WIZARD,
            SettingsEntryId.WEB_SEARCH,
        ),
        SettingsGroupKind.VOICE to listOf(
            SettingsEntryId.TTS,
            SettingsEntryId.ASR,
            SettingsEntryId.VOICE_CLONING,
        ),
        SettingsGroupKind.IMAGE to listOf(
            SettingsEntryId.IMAGE_VIDEO_GEN,
            SettingsEntryId.VISION,
        ),
        SettingsGroupKind.MEMORY to listOf(
            SettingsEntryId.MEMORY,
            SettingsEntryId.RAG,
            // v2.7.x(E2): 术语页 —— 新词与黑话在这里被确认或忽略
            SettingsEntryId.SESSION_TERMS,
        ),
        SettingsGroupKind.CONNECTION to listOf(
            SettingsEntryId.CHANNEL,
            SettingsEntryId.BROWSER,
            SettingsEntryId.WEB_ACCESS,
            SettingsEntryId.MCP,
            SettingsEntryId.PROXY,
        ),
        SettingsGroupKind.AUTOMATION to listOf(
            SettingsEntryId.PROACTIVE,
            SettingsEntryId.AGENT_DIRECTIVES,
            SettingsEntryId.NOTIFICATION_LISTENER,
            SettingsEntryId.UI_AUTOMATION,
        ),
        SettingsGroupKind.KEEP_ALIVE to listOf(
            SettingsEntryId.KEEP_ALIVE,
        ),
        SettingsGroupKind.MINI_PHONE to listOf(
            SettingsEntryId.MINIPHONE,
        ),
        SettingsGroupKind.DATA_STORAGE to listOf(
            SettingsEntryId.DATA_MANAGEMENT,
            SettingsEntryId.WORKSPACE,
            SettingsEntryId.BACKUP,
            SettingsEntryId.DATA_IMPORT,
            SettingsEntryId.ARCHIVED_CHATS,
        ),
        SettingsGroupKind.PRIVACY_SECURITY to listOf(
            SettingsEntryId.PII_GUARD,
            SettingsEntryId.SHARE,
        ),
        SettingsGroupKind.ABOUT to listOf(
            SettingsEntryId.TUTORIAL,
            SettingsEntryId.ABOUT,
            SettingsEntryId.CHECK_UPDATE,
            SettingsEntryId.DIAGNOSTICS,
        ),
        SettingsGroupKind.DEVELOPER to listOf(
            SettingsEntryId.STATS,
            SettingsEntryId.EXPERIMENTS,
            SettingsEntryId.DEBUG_LOG,
            SettingsEntryId.CRASH_REPORT,
            SettingsEntryId.AUDIT_LOG,
        ),
    )

    @Test
    fun `home lists exactly fourteen categories in the planned order`() {
        assertEquals(
            "首页分类与顺序与方案不一致:\n实际 = ${SettingsCatalog.groups.map { it.kind }}\n期望 = ${expectedLayout.map { it.first }}",
            expectedLayout.map { it.first },
            SettingsCatalog.groups.map { it.kind },
        )
    }

    @Test
    fun `each group holds the planned entries in order`() {
        expectedLayout.forEach { (kind, expected) ->
            assertEquals(
                "「$kind」条目与顺序与方案不一致",
                expected,
                SettingsCatalog.group(kind).entries.map { it.id },
            )
        }
    }

    @Test
    fun `each group holds the planned number of entries`() {
        assertEquals(
            "每类条目数与方案不一致:\n实际 = ${SettingsCatalog.groups.map { it.entries.size }}\n期望 = $EXPECTED_GROUP_SIZES",
            EXPECTED_GROUP_SIZES,
            SettingsCatalog.groups.map { it.entries.size },
        )
    }

    @Test
    fun `all entries appear exactly once`() {
        val ids = SettingsCatalog.entries.map { it.id }
        val duplicates = ids.groupBy { it }.filterValues { it.size > 1 }.keys

        assertEquals("全部条目应为 ${EXPECTED_ENTRY_COUNT} 项，实际 ${ids.size} 项: $ids", EXPECTED_ENTRY_COUNT, ids.size)
        assertTrue("同一入口出现在多个分类里: $duplicates", duplicates.isEmpty())
        assertEquals(
            "清单必须覆盖全部条目 id，不能加也不能漏",
            SettingsEntryId.entries.toSet(),
            ids.toSet(),
        )
    }

    @Test
    fun `category titles use their own keys and do not repeat entry titles`() {
        val groupTitleRes = SettingsCatalog.groups.map { it.titleRes }
        assertEquals("16 个分类必须各有独立标题键", SETTINGS_GROUP_COUNT, groupTitleRes.distinct().size)

        val entryTitleRes = SettingsCatalog.entries.map { it.titleRes }
        val shared = groupTitleRes.filter { it in entryTitleRes }
        assertTrue("分类标题与条目标题共用了资源键: $shared", shared.isEmpty())
    }

    @Test
    fun `about and developer are separate single card categories`() {
        listOf(
            SettingsGroupKind.ABOUT to listOf(
                SettingsEntryId.TUTORIAL,
                SettingsEntryId.ABOUT,
                SettingsEntryId.CHECK_UPDATE,
                SettingsEntryId.DIAGNOSTICS,
            ),
            SettingsGroupKind.DEVELOPER to listOf(
                SettingsEntryId.STATS,
                SettingsEntryId.EXPERIMENTS,
                SettingsEntryId.DEBUG_LOG,
                SettingsEntryId.CRASH_REPORT,
                SettingsEntryId.AUDIT_LOG,
            ),
        ).forEach { (kind, expected) ->
            val group = SettingsCatalog.group(kind)
            assertEquals("「$kind」应只有一张卡片", 1, group.sections.size)
            assertEquals("「$kind」的条目与顺序与方案不一致", expected, group.entries.map { it.id })
            assertNull("「$kind」不该再套分区标题", group.sections.single().labelRes)
        }
    }

    /** 单条目分类直接进它唯一的页面；多条目分类保留中间页。 */
    @Test
    fun `single entry categories expose their only page for direct navigation`() {
        assertEquals(SettingsChatRoute, SettingsCatalog.group(SettingsGroupKind.CHAT).directEntryRoute())
        assertEquals(SettingsAppearanceRoute, SettingsCatalog.group(SettingsGroupKind.APPEARANCE).directEntryRoute())
        assertEquals(KeepAliveSettingsRoute, SettingsCatalog.group(SettingsGroupKind.KEEP_ALIVE).directEntryRoute())
        assertNull("多条目分类仍需中间页", SettingsCatalog.group(SettingsGroupKind.MODELS).directEntryRoute())
    }

    @Test
    fun `entries that are switches stay inside their category page`() {
        val toggles = SettingsCatalog.entries.filter { it.action is SettingsEntryAction.Toggle }
        assertFalse("清单里应当保留开关条目（PII 守护）", toggles.isEmpty())
        assertEquals(
            listOf(SettingsEntryId.PII_GUARD),
            toggles.map { it.id },
        )
        val owner = SettingsCatalog.groups.single { group ->
            group.entries.any { it.id == SettingsEntryId.PII_GUARD }
        }
        assertEquals("开关条目必须挂在它所属的一级分类下", SettingsGroupKind.PRIVACY_SECURITY, owner.kind)
    }

    private companion object {
        const val SETTINGS_GROUP_COUNT = 16
        const val EXPECTED_ENTRY_COUNT = 47

        /** 与上方 expectedLayout 一一对应的条目数，用来锁住分布均衡、没有过大的单类。 */
        // v2.7.x(E2): 记忆类 +1（术语页）；2.6.24: 表情包库并入聊天设置，聊天类 -1
        val EXPECTED_GROUP_SIZES: List<Int> = listOf(4, 1, 1, 3, 3, 3, 2, 3, 5, 4, 1, 1, 5, 2, 4, 5)
    }
}
