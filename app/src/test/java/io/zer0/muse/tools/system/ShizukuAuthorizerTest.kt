package io.zer0.muse.tools.system

import io.zer0.common.Logger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Field

/**
 * T0.3: Shizuku unauthorized-rejection path unit test.
 *
 * Shizuku audit verdict: SAFE — the authorization gate precedes the IShellService call;
 * when unauthorized, execute short-circuits to an exitCode=-1 denial result.
 * This test locks in that invariant:
 *
 *  - unauthorized (checkPermissionProbe={false}): execute returns "Shizuku 未授权"
 *    and the shellService field is null (lazy binding was never triggered).
 *  - authorized but service binding failed (backoff window hit): returns
 *    "绑定失败" / "不可用" — rejection happens before IShellService.execute.
 *
 * Robolectric provides the Android Context (the ShizukuAuthorizer constructor needs
 * a non-null Context but the rejection paths under test never call any Context method).
 * Each test case builds its own ShizukuAuthorizer instance (no shared field state).
 * lastBindFailureAt / shellService are non-final @Volatile var fields, safely
 * writable via JVM reflection.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ShizukuAuthorizerTest {

    @Before
    fun setUp() {
        Logger.enabled = false
    }

    private val context get() = org.robolectric.RuntimeEnvironment.application

    @Test
    fun `execute without authorization is denied and never touches the shell service`() {
        val authorizer = ShizukuAuthorizer(
            context = context,
            checkPermissionProbe = { false },
        )
        val result = runBlocking { authorizer.execute("id") }

        assertEquals(-1, result.exitCode)
        assertFalse(result.isSuccess)
        assertTrue(result.stderr.contains("Shizuku 未授权"))
        // shellService never bound when unauthorized — observable evidence that
        // IShellService was never reached
        assertEquals(null, readField(authorizer, "shellService"))
    }

    @Test
    fun `checkPermission reflects injected probe`() {
        assertFalse(ShizukuAuthorizer(context, checkPermissionProbe = { false }).checkPermission())
        assertTrue(ShizukuAuthorizer(context, checkPermissionProbe = { true }).checkPermission())
    }

    @Test
    fun `probe defaults to SDK path which is safely false in JVM unit test`() {
        // Without probe, the Shizuku SDK path is used (production default). Under JVM unit
        // tests the Shizuku service is unavailable; pingBinder failures are caught
        // gracefully, so checkPermission() MUST safely return false rather than throw
        // (if it threw, execute's short-circuit gate would be bypassed and the
        // unauthorized-rejection path would be broken).
        val authorizer = ShizukuAuthorizer(context = context)
        assertFalse(authorizer.checkPermission())
    }

    @Test
    fun `execute with authorization but service unavailable returns service-unavailable denial`() {
        val authorizer = ShizukuAuthorizer(
            context = context,
            checkPermissionProbe = { true },
        )
        // Bypass the 5s binding-failure backoff window: push lastBindFailureAt to now so
        // ensureServiceBound() hits the backoff check and returns false immediately,
        // avoiding a 3s real binding timeout
        writeVolatileField(authorizer, "lastBindFailureAt", System.currentTimeMillis())

        val result = runBlocking { authorizer.execute("id") }

        assertEquals(-1, result.exitCode)
        assertFalse(result.isSuccess)
        assertTrue(result.stderr.contains("绑定失败") || result.stderr.contains("不可用"))
        // shellService is still null when binding fails — rejection happens before
        // IShellService.execute
        assertEquals(null, readField(authorizer, "shellService"))
    }

    // ── 快路径优先，UserService 兜底 ───────────────────────────────────────

    @Test
    fun `快路径命中时完全不碰 UserService`() = kotlinx.coroutines.runBlocking {
        val authorizer = ShizukuAuthorizer(
            context = context,
            checkPermissionProbe = { true },
            remoteExec = ShizukuRemoteExecutor { fakeProcess(out = "uid=2000(shell)\n") },
        )

        val result = authorizer.execute("id")

        assertEquals(0, result.exitCode)
        assertEquals("uid=2000(shell)\n", result.stdout)
        assertEquals(ShizukuAuthorizer.PATH_REMOTE, authorizer.lastExecutionPath)
        // 没绑定过：既不该有服务代理，也不该留下绑定参数
        assertEquals(null, readField(authorizer, "shellService"))
        assertEquals(null, readField(authorizer, "boundArgs"))
    }

    @Test
    fun `快路径起不来才退回 UserService`() = kotlinx.coroutines.runBlocking {
        val authorizer = ShizukuAuthorizer(
            context = context,
            checkPermissionProbe = { true },
            remoteExec = ShizukuRemoteExecutor { null },
        )
        writeVolatileField(authorizer, "lastBindFailureAt", System.currentTimeMillis())

        val result = authorizer.execute("id")

        assertEquals(-1, result.exitCode)
        assertEquals(ShizukuAuthorizer.PATH_USER_SERVICE, authorizer.lastExecutionPath)
        assertTrue(result.stderr.contains("绑定失败") || result.stderr.contains("不可用"))
    }

    @Test
    fun `快路径超时也不退回，命令不会被执行两遍`() = kotlinx.coroutines.runBlocking {
        var started = 0
        val authorizer = ShizukuAuthorizer(
            context = context,
            checkPermissionProbe = { true },
            remoteExec = ShizukuRemoteExecutor {
                started++
                fakeProcess(exits = false)
            },
        )

        val result = authorizer.execute("rm /sdcard/x")

        assertEquals(1, started)
        assertEquals(-1, result.exitCode)
        assertEquals(ShizukuAuthorizer.PATH_REMOTE, authorizer.lastExecutionPath)
        assertTrue(result.stderr.contains("超时"))
    }

    @Test
    fun `未授权时快路径也不会被尝试`() = kotlinx.coroutines.runBlocking {
        var started = 0
        val authorizer = ShizukuAuthorizer(
            context = context,
            checkPermissionProbe = { false },
            remoteExec = ShizukuRemoteExecutor {
                started++
                fakeProcess()
            },
        )

        val result = authorizer.execute("id")

        assertEquals(0, started)
        assertEquals(ShizukuAuthorizer.PATH_NONE, authorizer.lastExecutionPath)
        assertTrue(result.stderr.contains("Shizuku 未授权"))
    }

    // ── 绑定策略 ──────────────────────────────────────────────────────────

    @Test
    fun `UserService 常驻，不在每次解绑时销毁进程`() {
        val args = ShizukuAuthorizer(context).shellServiceArgs
        // 版本号已经保证升级后换掉旧进程；再配 daemon(false) 只会让每次执行都付一次冷启动。
        assertTrue(readBooleanField(args, "daemon"))
    }

    @Test
    fun `连接等待超时返回 false 而不是抛`() = kotlinx.coroutines.runBlocking {
        val started = System.currentTimeMillis()
        assertFalse(ShizukuAuthorizer(context).awaitServiceConnection(200L))
        val cost = System.currentTimeMillis() - started
        assertTrue("等待应在超时附近返回,实际 ${cost}ms", cost in 150L..3_000L)
    }

    @Test
    fun `连接回调到达后等待立刻返回，不用等满超时`() = kotlinx.coroutines.runBlocking {
        val authorizer = ShizukuAuthorizer(context)
        val connector = kotlin.concurrent.thread {
            Thread.sleep(120L)
            writeVolatileField(authorizer, "shellService", fakeService())
            writeVolatileField(authorizer, "serviceBound", true)
            completeConnectionSignal(authorizer)
        }
        val started = System.currentTimeMillis()
        val connected = authorizer.awaitServiceConnection(10_000L)
        connector.join(3_000L)
        assertTrue(connected)
        val cost = System.currentTimeMillis() - started
        assertTrue("应由回调唤醒,实际等了 ${cost}ms", cost < 5_000L)
    }

    @Test
    fun `信号被完成但服务字段还没赋值时不算就绪`() = kotlinx.coroutines.runBlocking {
        val authorizer = ShizukuAuthorizer(context)
        // 旧信号残留会让下一次等待立刻通过，然后拿着 null 服务往下走。
        completeConnectionSignal(authorizer)
        assertFalse(authorizer.awaitServiceConnection(500L))
    }

    private fun fakeProcess(out: String = "", code: Int = 0, exits: Boolean = true): RemoteShellProcess {
        return object : RemoteShellProcess {
            override fun stdout(): java.io.InputStream = java.io.ByteArrayInputStream(out.toByteArray(Charsets.UTF_8))
            override fun stderr(): java.io.InputStream = java.io.ByteArrayInputStream(ByteArray(0))
            override fun waitFor(timeoutMs: Long): Boolean = exits
            override fun exitValue(): Int = code
            override fun destroy() = Unit
            override fun close() = Unit
        }
    }

    private fun fakeService(): IShellService = java.lang.reflect.Proxy.newProxyInstance(
        IShellService::class.java.classLoader,
        arrayOf(IShellService::class.java),
    ) { _, method, _ ->
        when (method.name) {
            "execute" -> "0\u0000ok\u0000"
            "executeToPipe" -> 0
            "destroy" -> null
            else -> null
        }
    } as IShellService

    // -- reflection helpers (read/write @Volatile var fields only, non-final) --

    private fun readBooleanField(target: Any, name: String): Boolean {
        val field: Field = target.javaClass.getDeclaredField(name)
        field.isAccessible = true
        return field.getBoolean(target)
    }

    /**
     * 模拟 onServiceConnected 里那次信号完成。反射调用避免在测试里写裸泛型转换。
     *
     * 取方法要按接口取、再放开可访问性：字段的运行时类型是 kotlinx.coroutines 的 internal 实现类，
     * 从它身上取到的 Method 即便签名一致，invoke 时也会被访问检查挡下来。
     */
    private fun completeConnectionSignal(authorizer: ShizukuAuthorizer) {
        val signal = readField(authorizer, "connectionSignal")!!
        val complete = kotlinx.coroutines.CompletableDeferred::class.java
            .getMethod("complete", Any::class.java)
        complete.isAccessible = true
        complete.invoke(signal, Unit)
    }

    private fun readField(target: Any, name: String): Any? {
        val field: Field = ShizukuAuthorizer::class.java.getDeclaredField(name)
        field.isAccessible = true
        return field.get(target)
    }

    private fun writeVolatileField(target: Any, name: String, value: Any) {
        val field: Field = ShizukuAuthorizer::class.java.getDeclaredField(name)
        field.isAccessible = true
        field.set(target, value)
    }

    private fun runBlocking(body: suspend () -> ShizukuAuthorizer.ShizukuExecResult): ShizukuAuthorizer.ShizukuExecResult {
        return kotlinx.coroutines.runBlocking { body() }
    }
}
