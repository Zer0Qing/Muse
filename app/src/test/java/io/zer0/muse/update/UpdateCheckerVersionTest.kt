package io.zer0.muse.update

import io.zer0.common.AppJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.0.72: 更新检查核心逻辑测试。
 *
 * 覆盖:
 *  - 语义版本比较(带 v 前缀/多段版本/长度不等)
 *  - ReleaseInfo 序列化往返(UI Banner 缓存依赖)
 *  - "升级到同版本后不再提示"的判定逻辑(compareVersions >= 0 即不提示)
 *
 * 注意:不依赖网络,纯逻辑测试,CI 可稳定运行。
 */
class UpdateCheckerVersionTest {

    // ── 版本比较(UpdateChecker.compareVersions) ──

    @Test
    fun `current older than latest returns negative`() {
        assertTrue(UpdateChecker.compareVersions("1.0.70", "v1.0.71") < 0)
        assertTrue(UpdateChecker.compareVersions("1.0.70", "1.0.71") < 0)
        assertTrue(UpdateChecker.compareVersions("1.0.70", "1.1.0") < 0)
        assertTrue(UpdateChecker.compareVersions("1.0.70", "1.0.100") < 0)
    }

    @Test
    fun `current equal to latest returns zero`() {
        assertEquals(0, UpdateChecker.compareVersions("1.0.71", "v1.0.71"))
        assertEquals(0, UpdateChecker.compareVersions("v1.0.71", "v1.0.71"))
        assertEquals(0, UpdateChecker.compareVersions("1.0.71", "1.0.71"))
    }

    @Test
    fun `current newer than latest returns positive`() {
        assertTrue(UpdateChecker.compareVersions("1.0.72", "v1.0.71") > 0)
        assertTrue(UpdateChecker.compareVersions("1.1.0", "1.0.99") > 0)
    }

    @Test
    fun `unequal length versions compare by missing segments as zero`() {
        // "1.0" 视为 [1,0],与 "1.0.1"([1,0,1]) 比较 → 1.0 < 1.0.1
        assertTrue(UpdateChecker.compareVersions("1.0", "1.0.1") < 0)
        // "1.0.1" > "1.0"
        assertTrue(UpdateChecker.compareVersions("1.0.1", "1.0") > 0)
    }

    @Test
    fun `non numeric segments are ignored`() {
        // "1.0.70-beta" split('.') → ["1","0","70-beta"],非数字整段忽略 → [1,0]
        // 与 [1,0,70] 比较:缺段按 0 → 1.0.70-beta < v1.0.70
        assertTrue(UpdateChecker.compareVersions("1.0.70-beta", "v1.0.70") < 0)
        // 纯数字段仍正常比较
        assertTrue(UpdateChecker.compareVersions("1.0.70", "1.0.71") < 0)
    }

    // ── ReleaseInfo 序列化往返(UI Banner 缓存) ──

    @Test
    fun `release info round trips through json`() {
        val release = UpdateChecker.ReleaseInfo(
            tagName = "v1.0.72",
            name = "v1.0.72",
            body = "修复更新检查",
            htmlUrl = "https://github.com/Zer0Qing/Muse/releases/tag/v1.0.72",
            publishedAt = 1_770_000_000_000L,
            apkAssets = listOf(
                UpdateChecker.ApkAsset(
                    name = "Muse_v1.0.72_arm64-v8a.apk",
                    downloadUrl = "https://example.com/muse.apk",
                    size = 42_000_000L,
                ),
            ),
        )
        val json = AppJson.encodeToString(UpdateChecker.ReleaseInfo.serializer(), release)
        val decoded = AppJson.decodeFromString(UpdateChecker.ReleaseInfo.serializer(), json)

        assertEquals("v1.0.72", decoded.tagName)
        assertEquals(1_770_000_000_000L, decoded.publishedAt)
        assertEquals(1, decoded.apkAssets.size)
        assertEquals("Muse_v1.0.72_arm64-v8a.apk", decoded.apkAssets[0].name)
        assertEquals(42_000_000L, decoded.apkAssets[0].size)
    }

    // ── Banner 显示判定(升级到同版本后不再提示) ──

    @Test
    fun `banner should not show when current equals cached latest`() {
        // HomeScreen 判定:compareVersions(current, cached.tagName) < 0 才显示 Banner
        val current = "1.0.71"
        val cached = "v1.0.71"
        assertTrue("升级到同版本后不应显示 Banner", UpdateChecker.compareVersions(current, cached) >= 0)
    }

    @Test
    fun `banner should show when current older than cached`() {
        val current = "1.0.70"
        val cached = "v1.0.71"
        assertTrue("存在新版本时应显示 Banner", UpdateChecker.compareVersions(current, cached) < 0)
    }
}
