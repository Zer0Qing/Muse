package io.zer0.muse.miniphone.data.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 模块设置落盘:角色主动私信的开关与上次时间。
 *
 * 跑真 DataStore(临时文件),不替身 —— 要验的正是「键写进去、读回来还在」:
 * 默认值、写后读、时间戳三件事都是这条路径上的真行为。
 * 每个用例一份新文件,互不残留。
 */
class MpMomentAutoStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var storeScope: CoroutineScope
    private lateinit var store: MpMomentAutoStore

    @Before
    fun setUp() {
        storeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        // 文件名与生产一致(DataStore 按文件名加 .preferences_pb 后缀)
        val file = File(tempFolder.newFolder(), "miniphone_settings.preferences_pb")
        store = DataStoreMpMomentAutoStore(
            PreferenceDataStoreFactory.create(scope = storeScope, produceFile = { file }),
        )
    }

    @After
    fun tearDown() {
        storeScope.cancel()
    }

    @Test
    fun chatProactiveIsOffByDefault() = runTest {
        // 默认关:用户没打开过就一直不打扰
        assertFalse(store.isChatProactiveEnabled())
        assertFalse(store.chatProactiveEnabledFlow.first())
        // 上次时间也从没写过
        assertNull(store.lastProactiveChatAt())
    }

    @Test
    fun enablingChatProactiveIsReadBack() = runTest {
        store.setChatProactiveEnabled(true)

        assertTrue(store.isChatProactiveEnabled())
        assertTrue(store.chatProactiveEnabledFlow.first())
        // 自动发朋友圈的开关不受影响(两个键各管各的)
        assertTrue(store.isEnabled())
    }

    @Test
    fun markingProactiveChatKeepsTheTimestamp() = runTest {
        store.markProactiveChat(PROACTIVE_AT)

        assertEquals(PROACTIVE_AT, store.lastProactiveChatAt())
    }

    private companion object {
        const val PROACTIVE_AT: Long = 1_779_000_000_000L
    }
}
