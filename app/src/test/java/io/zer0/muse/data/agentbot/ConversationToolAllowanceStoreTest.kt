package io.zer0.muse.data.agentbot

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 「本对话内允许」的持久化语义。
 *
 * 旧实现是内存 Map，切会话即失效 —— 用户会以为按钮坏了，转而点"始终允许"。
 * 这里钉住：允许要跨进程存活，且用户必须能收回。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ConversationToolAllowanceStoreTest {

    private lateinit var store: ConversationToolAllowanceStore

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        store = ConversationToolAllowanceStore(context)
    }

    @Test
    fun `allowing a tool is readable back through the flow`() = runTest {
        store.allow("session-1", "workspace_write")

        // 读回走的是 DataStore 的 data 流，不是内存缓存 ——
        // 这正是旧实现缺的那一步（旧的只写 ConcurrentHashMap，进程一死就没了）。
        val persisted = store.allowancesFlow.first()
        assertTrue(
            "允许必须落进 DataStore，否则切会话回来又弹审批",
            persisted["session-1"]?.contains("workspace_write") == true,
        )
        assertTrue(store.isAllowed("session-1", "workspace_write"))
    }

    @Test
    fun `allowance is scoped to its own session`() = runTest {
        store.allow("session-1", "workspace_write")
        assertFalse(
            "别的会话不该继承这个允许",
            store.isAllowed("session-2", "workspace_write"),
        )
    }

    @Test
    fun `allowance does not leak across tools`() = runTest {
        store.allow("session-1", "workspace_write")
        assertFalse(store.isAllowed("session-1", "device_shell"))
    }

    @Test
    fun `revoke removes exactly one tool`() = runTest {
        store.allow("session-1", "workspace_write")
        store.allow("session-1", "workspace_read")

        assertTrue(store.revoke("session-1", "workspace_write"))
        assertFalse(store.isAllowed("session-1", "workspace_write"))
        assertTrue("别的工具不该被一起收回", store.isAllowed("session-1", "workspace_read"))
    }

    @Test
    fun `revoking an unknown tool reports false instead of throwing`() = runTest {
        assertFalse(store.revoke("session-1", "never_allowed"))
    }

    @Test
    fun `clearing a session drops only that session`() = runTest {
        store.allow("session-1", "workspace_write")
        store.allow("session-2", "workspace_write")

        store.clearSession("session-1")
        assertFalse(store.isAllowed("session-1", "workspace_write"))
        assertTrue(store.isAllowed("session-2", "workspace_write"))
    }

    @Test
    fun `clearAll drops everything`() = runTest {
        store.allow("session-1", "a")
        store.allow("session-2", "b")
        store.clearAll()

        assertFalse(store.isAllowed("session-1", "a"))
        assertFalse(store.isAllowed("session-2", "b"))
    }

    @Test
    fun `blank session or tool is ignored instead of polluting storage`() = runTest {
        store.allow("", "workspace_write")
        store.allow("session-1", "")
        assertFalse(store.isAllowed("", "workspace_write"))
        assertEquals(emptySet<String>(), store.allowedTools("session-1"))
    }
}
