package io.zer0.muse.tools.system

import io.zer0.muse.tools.system.ShellExecutor.Channel
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * P3-3: [ShellExecutor] 路由逻辑单元测试。
 *
 * 测试纯逻辑函数 [ShellExecutor.selectLevel] / [ShellExecutor.selectShellChannel],
 * 不依赖 Android runtime(无需 mock Context/Shizuku SDK)。
 *
 * 关键验证点:
 *  - [selectLevel] ROOT 优先(等级最高)
 *  - [selectShellChannel] SHIZUKU 优先(更安全),ROOT 降级
 *  - 两者优先级不同:root+shizuku 同时可用时,level=ROOT, channel=SHIZUKU
 *  - ACCESSIBILITY 不支持 shell(selectShellChannel 仅在 shizuku/root 均不可用时返回 ACCESSIBILITY)
 */
class ShellExecutorTest {

    // ── selectLevel:ROOT 优先 ────────────────────────────────────────────────

    @Test
    fun `selectLevel returns NONE when no channel available`() {
        assertEquals(
            AndroidPermissionLevel.NONE,
            ShellExecutor.selectLevel(rootOk = false, shizukuOk = false, a11yOk = false),
        )
    }

    @Test
    fun `selectLevel returns ACCESSIBILITY when only a11y available`() {
        assertEquals(
            AndroidPermissionLevel.ACCESSIBILITY,
            ShellExecutor.selectLevel(rootOk = false, shizukuOk = false, a11yOk = true),
        )
    }

    @Test
    fun `selectLevel returns SHIZUKU when only shizuku available`() {
        assertEquals(
            AndroidPermissionLevel.SHIZUKU,
            ShellExecutor.selectLevel(rootOk = false, shizukuOk = true, a11yOk = false),
        )
    }

    @Test
    fun `selectLevel returns ROOT when only root available`() {
        assertEquals(
            AndroidPermissionLevel.ROOT,
            ShellExecutor.selectLevel(rootOk = true, shizukuOk = false, a11yOk = false),
        )
    }

    @Test
    fun `selectLevel prefers ROOT over SHIZUKU and ACCESSIBILITY`() {
        // root + shizuku + a11y 都可用时,ROOT 优先(等级最高)
        assertEquals(
            AndroidPermissionLevel.ROOT,
            ShellExecutor.selectLevel(rootOk = true, shizukuOk = true, a11yOk = true),
        )
    }

    @Test
    fun `selectLevel prefers SHIZUKU over ACCESSIBILITY when root unavailable`() {
        assertEquals(
            AndroidPermissionLevel.SHIZUKU,
            ShellExecutor.selectLevel(rootOk = false, shizukuOk = true, a11yOk = true),
        )
    }

    // ── selectShellChannel:SHIZUKU 优先 ──────────────────────────────────────

    @Test
    fun `selectShellChannel returns NONE when no channel available`() {
        assertEquals(
            Channel.NONE,
            ShellExecutor.selectShellChannel(shizukuOk = false, rootOk = false, a11yOk = false),
        )
    }

    @Test
    fun `selectShellChannel returns SHIZUKU when shizuku available`() {
        assertEquals(
            Channel.SHIZUKU,
            ShellExecutor.selectShellChannel(shizukuOk = true, rootOk = false, a11yOk = false),
        )
    }

    @Test
    fun `selectShellChannel returns ROOT when only root available`() {
        assertEquals(
            Channel.ROOT,
            ShellExecutor.selectShellChannel(shizukuOk = false, rootOk = true, a11yOk = false),
        )
    }

    @Test
    fun `selectShellChannel prefers SHIZUKU over ROOT`() {
        // 关键:root+shizuku 同时可用时,shell 走 SHIZUKU(更安全),而非 ROOT
        assertEquals(
            Channel.SHIZUKU,
            ShellExecutor.selectShellChannel(shizukuOk = true, rootOk = true, a11yOk = true),
        )
    }

    @Test
    fun `selectShellChannel returns ACCESSIBILITY only when shizuku and root unavailable`() {
        // ACCESSIBILITY 不支持 shell,仅在没有 shell 通道时返回(供 UI 操作判断)
        assertEquals(
            Channel.ACCESSIBILITY,
            ShellExecutor.selectShellChannel(shizukuOk = false, rootOk = false, a11yOk = true),
        )
    }

    @Test
    fun `selectShellChannel does not return ACCESSIBILITY when shizuku available`() {
        assertEquals(
            Channel.SHIZUKU,
            ShellExecutor.selectShellChannel(shizukuOk = true, rootOk = false, a11yOk = true),
        )
    }

    // ── 优先级差异验证 ─────────────────────────────────────────────────────────

    @Test
    fun `level and shell channel differ when root and shizuku both available`() {
        // root + shizuku 同时可用:
        //  - selectLevel = ROOT(等级最高)
        //  - selectShellChannel = SHIZUKU(shell 优先 Shizuku 更安全)
        val rootOk = true
        val shizukuOk = true
        val a11yOk = false
        assertEquals(
            AndroidPermissionLevel.ROOT,
            ShellExecutor.selectLevel(rootOk, shizukuOk, a11yOk),
        )
        assertEquals(
            Channel.SHIZUKU,
            ShellExecutor.selectShellChannel(shizukuOk, rootOk, a11yOk),
        )
    }
}
