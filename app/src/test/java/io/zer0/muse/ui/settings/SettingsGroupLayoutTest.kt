package io.zer0.muse.ui.settings

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
 * 首页分类数、每类的条目与顺序、全局条目的完整性全部写在 [SettingsCatalog] 里，
 * 首页 / 分类页 / 搜索索引都从这份清单取数据。这里直接断言清单本身：
 * 换类、换顺序、把某一项贴到两类里、加一项漏一项，都会让下面的断言失败。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SettingsGroupLayoutTest {

    /** 12 个均衡分类，按首页 4 区块从上到下的顺序；每类的条目按分类页从上到下的顺序。 */
    private val expectedLayout: List<Pair<SettingsGroupKind, List<SettingsEntryId>>> = listOf(
        SettingsGroupKind.GENERAL to listOf(
            SettingsEntryId.APPEARANCE,
            SettingsEntryId.CHAT,
            SettingsEntryId.SHARE,
            SettingsEntryId.USER_PROFILE,
            SettingsEntryId.STATS,
        ),
        SettingsGroupKind.ASSISTANTS to listOf(
            SettingsEntryId.ASSISTANTS,
            SettingsEntryId.AGENT,
            SettingsEntryId.AGENT_DIRECTIVES,
            SettingsEntryId.ASSISTANT_RESOURCES,
            SettingsEntryId.MINIPHONE,
        ),
        SettingsGroupKind.MODELS to listOf(
            SettingsEntryId.PROVIDER,
            SettingsEntryId.TASK_ROUTING,
            SettingsEntryId.RUNTIME,
        ),
        SettingsGroupKind.MEDIA to listOf(
            SettingsEntryId.TTS,
            SettingsEntryId.ASR,
            SettingsEntryId.VOICE_CLONING,
            SettingsEntryId.VISION,
            SettingsEntryId.IMAGE_VIDEO_GEN,
        ),
        SettingsGroupKind.MEMORY to listOf(
            SettingsEntryId.MEMORY,
            SettingsEntryId.RAG,
            SettingsEntryId.SESSION_TERMS,
        ),
        SettingsGroupKind.TOOLS to listOf(
            SettingsEntryId.TOOLS,
            SettingsEntryId.SKILLS,
            SettingsEntryId.WEB_SEARCH,
            SettingsEntryId.PROVIDER_PLUGINS,
            SettingsEntryId.MCP,
        ),
        SettingsGroupKind.CHANNELS to listOf(
            SettingsEntryId.CHANNEL,
        ),
        SettingsGroupKind.AUTOMATION to listOf(
            SettingsEntryId.PROACTIVE,
            SettingsEntryId.NOTIFICATION_LISTENER,
            SettingsEntryId.UI_AUTOMATION,
            SettingsEntryId.KEEP_ALIVE,
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
            SettingsEntryId.PERMISSION_WIZARD,
            SettingsEntryId.TOOL_APPROVAL,
            SettingsEntryId.AUDIT_LOG,
        ),
        SettingsGroupKind.CONNECTION to listOf(
            SettingsEntryId.BROWSER,
            SettingsEntryId.WEB_ACCESS,
            SettingsEntryId.PROXY,
        ),
        SettingsGroupKind.ABOUT to listOf(
            SettingsEntryId.TUTORIAL,
            SettingsEntryId.ABOUT,
            SettingsEntryId.CHECK_UPDATE,
        ),
        SettingsGroupKind.DEVELOPER to listOf(
            SettingsEntryId.DIAGNOSTICS,
            SettingsEntryId.EXPERIMENTS,
            SettingsEntryId.DEBUG_LOG,
            SettingsEntryId.CRASH_REPORT,
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
        assertEquals("$SETTINGS_GROUP_COUNT 个分类必须各有独立标题键", SETTINGS_GROUP_COUNT, groupTitleRes.distinct().size)

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
            ),
            SettingsGroupKind.DEVELOPER to listOf(
                SettingsEntryId.DIAGNOSTICS,
                SettingsEntryId.EXPERIMENTS,
                SettingsEntryId.DEBUG_LOG,
                SettingsEntryId.CRASH_REPORT,
            ),
        ).forEach { (kind, expected) ->
            val group = SettingsCatalog.group(kind)
            assertEquals("「$kind」应只有一张卡片", 1, group.sections.size)
            assertEquals("「$kind」的条目与顺序与方案不一致", expected, group.entries.map { it.id })
            assertNull("「$kind」不该再套分区标题", group.sections.single().labelRes)
        }
    }

    /**
     * 多条目分类保留中间页。
     *
     * 2026-11-22: 原来这条断言的是「单条目分类直接进它唯一的页面」，
     * 而 CHAT / APPEARANCE / KEEP_ALIVE 这几个单条目分类已并入相邻组，
     * 现在所有分类都是多条，`directEntryRoute()` 一律返回 null。
     * 断言改成守「多条目必须有中间页」——这正是合并带来的行为变化。
     */
    @Test
    fun `multi entry categories keep their intermediate page and channels navigates directly`() {
        assertNull("多条目分类仍需中间页", SettingsCatalog.group(SettingsGroupKind.MODELS).directEntryRoute())
        assertNull("多条目分类仍需中间页", SettingsCatalog.group(SettingsGroupKind.ASSISTANTS).directEntryRoute())
        assertNull("多条目分类仍需中间页", SettingsCatalog.group(SettingsGroupKind.CONNECTION).directEntryRoute())
        assertEquals(
            "消息渠道为一级直达入口，不应再套二级列表",
            io.zer0.muse.ui.navigation.ChannelSettingsRoute,
            SettingsCatalog.group(SettingsGroupKind.CHANNELS).directEntryRoute(),
        )
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
        // 2026-11-22: 17 → 12。CHAT / APPEARANCE / KEEP_ALIVE / MINI_PHONE / OFFLINE
        // 五个单条目分类并入相邻组 —— 一个分组只有一条时，分组没有提供任何信息。
        const val SETTINGS_GROUP_COUNT = 13
        const val EXPECTED_ENTRY_COUNT = 50

        /** 13 个一级分类各自的条目数（消息渠道为 1 项直达，其余每类 3–5 项）。 */
        val EXPECTED_GROUP_SIZES: List<Int> = listOf(5, 5, 3, 5, 3, 5, 1, 4, 5, 4, 3, 3, 4)
    }
}
