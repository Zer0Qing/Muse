package io.zer0.muse.data.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 语义化版本解析与优先级测试（插件降级保护与 minAppVersion 判断都依赖它）。 */
class PluginVersionTest {

    @Test
    fun parsesWellFormedVersions() {
        assertEquals(PluginVersion(1, 0, 0), PluginVersion.parse("1.0.0"))
        assertEquals(PluginVersion(2, 31, 4), PluginVersion.parse("2.31.4"))
        assertEquals(PluginVersion(1, 0, 0, listOf("rc", "1")), PluginVersion.parse("1.0.0-rc.1"))
        // 构建元数据不参与比较，解析时直接忽略。
        assertEquals(PluginVersion(1, 2, 3), PluginVersion.parse("1.2.3+build.5"))
        assertEquals(PluginVersion(1, 2, 3, listOf("beta")), PluginVersion.parse("1.2.3-beta+exp.sha.5114f85"))
        assertEquals("1.2.3-beta", PluginVersion.parse("1.2.3-beta").toString())
    }

    @Test
    fun rejectsMalformedVersions() {
        listOf("", "1.0", "1", "v1.0.0", "1.0.0.0", "1.0.0-", "1.0.0-01", "1.0.0-alpha..1", "abc", "1.0.x", " 1.0.0 " ).forEach {
            // 允许首尾空白的裁剪，但其它形态必须判为不合法，避免「猜一个版本」。
            if (it == " 1.0.0 ") {
                assertEquals(PluginVersion(1, 0, 0), PluginVersion.parse(it))
            } else {
                assertNull("应判为不合法: '$it'", PluginVersion.parse(it))
            }
        }
    }

    @Test
    fun ordersByMajorMinorPatch() {
        assertTrue(PluginVersion.parse("1.0.0")!! < PluginVersion.parse("1.0.1")!!)
        assertTrue(PluginVersion.parse("1.0.1")!! < PluginVersion.parse("1.1.0")!!)
        assertTrue(PluginVersion.parse("1.1.0")!! < PluginVersion.parse("2.0.0")!!)
        assertEquals(0, PluginVersion.parse("1.2.3")!!.compareTo(PluginVersion.parse("1.2.3+build")!!))
    }

    @Test
    fun prereleaseHasLowerPrecedenceThanRelease() {
        val release = PluginVersion.parse("1.0.0")!!
        assertTrue(PluginVersion.parse("1.0.0-rc.1")!! < release)
        assertTrue(PluginVersion.parse("1.0.0-alpha")!! < PluginVersion.parse("1.0.0-beta")!!)
        assertTrue(PluginVersion.parse("1.0.0-alpha")!! < PluginVersion.parse("1.0.0-alpha.1")!!)
        assertTrue(PluginVersion.parse("1.0.0-alpha.1")!! < PluginVersion.parse("1.0.0-alpha.beta")!!)
        assertTrue(PluginVersion.parse("1.0.0-rc.1")!! < PluginVersion.parse("1.0.0-rc.2")!!)
        assertTrue(PluginVersion.parse("1.0.0-rc.2")!! < PluginVersion.parse("1.0.0-rc.10")!!)
        // 数字标识优先级低于字母标识。
        assertTrue(PluginVersion.parse("1.0.0-1")!! < PluginVersion.parse("1.0.0-alpha")!!)
    }

    @Test
    fun stableFlagReflectsPrereleasePresence() {
        assertTrue(PluginVersion.parse("1.0.0")!!.isStable)
        assertFalse(PluginVersion.parse("1.0.0-rc.1")!!.isStable)
    }

    @Test
    fun comparingAgainstNewerRequiredAppVersion() {
        val app = PluginVersion.parse("1.0.89")!!
        assertTrue(PluginVersion.parse("1.0.88")!! <= app)
        assertTrue(PluginVersion.parse("1.1.0")!! > app)
    }
}
