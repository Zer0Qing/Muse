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

    // -- reflection helpers (read/write @Volatile var fields only, non-final) --

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
