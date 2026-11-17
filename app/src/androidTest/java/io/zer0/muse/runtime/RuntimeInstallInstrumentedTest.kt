package io.zer0.muse.runtime

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 运行时安装的端到端验证（跑在真机/模拟器上）。
 *
 * 覆盖的是最容易出问题、又最难靠单测覆盖的那一段：
 * 联网下载真实包 → 校验 sha256 → 解 tar.gz（含符号链接）→ proot 里跑 `node -v` 自检 → 写指针。
 *
 * 需要网络。慢网下可能超过几分钟，所以不放进常规单测集，只做手工/CI 验证。
 */
@RunWith(AndroidJUnit4::class)
class RuntimeInstallInstrumentedTest {

    @Test
    fun installsRuntimeAndRunsNode() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = RuntimeStore(context)
        val id = RuntimeId.UBUNTU_NODE

        val manifest = RuntimeManifestSource(context, remoteBaseUrl = "http://127.0.0.1:1").load(id)
        assertNotNull("当前 ABI 没有可用的运行时清单", manifest)

        // 从干净状态开始，避免「上次装过」把问题盖住
        store.uninstall(id)

        // 先单独把包下下来，确认字节对得上 —— 解压报错时容易误判成解析器问题
        // 若测试前已把包推进 filesDir（离线验证），直接用本地包，跳过下载。
        val prePushed = java.io.File(context.filesDir, "test-runtime.tar.gz")
        val archive = java.io.File(store.rootDir(), "probe-${manifest!!.revision}.tar.gz")
        if (prePushed.isFile && prePushed.length() > 0) {
            prePushed.copyTo(archive, overwrite = true)
        } else {
            val dl = io.zer0.muse.runtime.RuntimeDownloader().download(manifest, archive)
            assertTrue("下载失败：${dl.exceptionOrNull()?.message}", dl.isSuccess)
        }
        val head = ByteArray(2)
        archive.inputStream().use { it.read(head) }
        val headHex = head.joinToString(" ") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
        assertTrue("包头部不是 gzip（期望 1f 8b），实际 $headHex，size=${archive.length()}", head[0] == 0x1f.toByte() && head[1] == 0x8b.toByte())

        // 再往下验一层：gzip 解开后必须是 tar 头（首字符 '.'，对应成员 './'）
        val tarHead = ByteArray(2)
        java.util.zip.GZIPInputStream(archive.inputStream()).use { it.read(tarHead) }
        assertTrue(
            "tar 头部异常（期望 '.'），实际 ${tarHead.joinToString(" ") { (it.toInt() and 0xff).toString(16) }}",
            tarHead[0] == '.'.code.toByte(),
        )

        val installer = RuntimeInstaller(context, store)
        val result = installer.installFromArchive(id, manifest, archive)
        assertTrue("安装失败：${result.exceptionOrNull()?.message}", result.isSuccess)
        val state = store.state(id)
        assertTrue("安装后状态应为 Ready，实际 $state", state is RuntimeState.Ready)

        // 自检必须真的能跑起来；这一步过了，说明 proot + rootfs + node 都通了
        val version = ProotRuntime(context, store).selfCheck(id)
        assertNotNull("node -v 没跑出结果", version)
        assertTrue("版本号不合预期：$version", version!!.startsWith("v"))
    }
}
