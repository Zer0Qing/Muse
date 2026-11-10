package io.zer0.muse.tools

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 工具审批策略存储测试。
 *
 * 关键契约：**未配置 ≠ 用户显式允许**。数据层必须能区分这两种状态，
 * 否则审批主路径会把「没配置过」当成「用户已放行」，ASK/STRICT 模式下
 * 按风险等级应有的审批会被整段跳过（历史缺陷）。
 *
 * DataStore 在同一测试进程内共享，因此每个用例使用不同的工具名保证隔离。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ToolConfigStoreTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val store get() = ToolConfigStore(context)

    @Test
    fun unconfiguredToolHasNoExplicitPolicy() = runBlocking {
        assertNull(store.getConfiguredPolicy("probe_unconfigured"))
        // 兼容访问器仍返回「默认放行」语义，但审批主路径不应使用它做判定。
        assertEquals(ToolApprovalPolicy.ALWAYS_ALLOW, store.getPolicy("probe_unconfigured"))
        assertEquals(ToolApprovalState.Auto, store.resolveApprovalState("probe_unconfigured"))
    }

    @Test
    fun explicitAllowIsStoredAndDistinguishableFromUnset() = runBlocking {
        store.setPolicy("probe_explicit_allow", ToolApprovalPolicy.ALWAYS_ALLOW)

        assertEquals(ToolApprovalPolicy.ALWAYS_ALLOW, store.getConfiguredPolicy("probe_explicit_allow"))
        assertNull(store.getConfiguredPolicy("probe_still_unset"))
    }

    @Test
    fun denyAndAskPoliciesRoundTrip() = runBlocking {
        store.setPolicy("probe_deny", ToolApprovalPolicy.ALWAYS_DENY)
        store.setPolicy("probe_ask", ToolApprovalPolicy.ASK_EVERY_TIME)

        assertEquals(ToolApprovalPolicy.ALWAYS_DENY, store.getConfiguredPolicy("probe_deny"))
        assertEquals(ToolApprovalPolicy.ASK_EVERY_TIME, store.getConfiguredPolicy("probe_ask"))
        // 断字段而不是 toString()：toString 把数据类的形状当契约，加一个字段就红一片，
        // 而这里真正要钉的是"原因文本"和"这是谁决定的"。
        val denied = store.resolveApprovalState("probe_deny")
        assertTrue("ALWAYS_DENY 必须给终态", denied is ToolApprovalState.Denied)
        denied as ToolApprovalState.Denied
        assertEquals("Tool disabled by user", denied.reason)
        assertEquals(
            "工具早先被禁用不是本轮用户的动作，来源必须是 POLICY（否则会被算成用户拒绝）",
            DenySource.POLICY,
            denied.source,
        )
        assertEquals(ToolApprovalState.Pending, store.resolveApprovalState("probe_ask"))
    }

    @Test
    fun unconfiguredToolStillRequiresApprovalUnderAskMode() = runBlocking {
        // 生产路径的接线契约：未配置 → null → 判定器按模式+风险等级决定。
        val policy = store.getConfiguredPolicy("probe_ask_high")
        assertEquals(
            ToolApprovalState.Pending,
            ToolPermissionResolver.resolve(
                toolName = "probe_ask_high",
                risk = ToolRiskLevel.HIGH,
                mode = SessionPermissionMode.ASK,
                perToolPolicy = policy,
            ),
        )
        assertEquals(
            ToolApprovalState.Pending,
            ToolPermissionResolver.resolve(
                toolName = "probe_ask_high",
                risk = ToolRiskLevel.NORMAL,
                mode = SessionPermissionMode.ASK,
                perToolPolicy = policy,
            ),
        )
    }

    @Test
    fun explicitAllowStillBypassesApprovalInAskMode() = runBlocking {
        // 用户点过「始终允许」之后必须继续免打扰，这条语义不能被上面的修复破坏。
        store.setPolicy("probe_allow_bypass", ToolApprovalPolicy.ALWAYS_ALLOW)
        val policy = store.getConfiguredPolicy("probe_allow_bypass")

        assertEquals(
            ToolApprovalState.Auto,
            ToolPermissionResolver.resolve(
                toolName = "probe_allow_bypass",
                risk = ToolRiskLevel.HIGH,
                mode = SessionPermissionMode.ASK,
                perToolPolicy = policy,
            ),
        )
    }
}
