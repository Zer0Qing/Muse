package io.zer0.muse.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 绝对符号链接的虚拟根校验。
 *
 * 为什么单独成组：真实 rootfs 里有 20 个绝对链接（var/run -> /run 这类），
 * 旧实现拿宿主机 File("/run").canonicalFile 去比 rootfs 前缀，必然误判为越界，
 * 安装会停在解包中途。这里把真实链接逐条钉住，避免回归。
 */
class TarGzExtractorLinkTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** 真实 rootfs 里出现的 20 个绝对链接，全部必须放行。 */
    private val realAbsoluteLinks = listOf(
        "/run",
        "/run/lock",
        "/etc/alternatives/pager",
        "/etc/alternatives/awk",
        "/etc/alternatives/nawk",
        "/etc/alternatives/which",
        "/etc/alternatives/rmt",
        "/usr/sbin/rmt",
        "/lib/systemd/system/apt-daily-upgrade.timer",
        "/lib/systemd/system/motd-news.timer",
        "/lib/systemd/system/e2scrub_all.timer",
        "/lib/systemd/system/fstrim.timer",
        "/lib/systemd/system/dpkg-db-backup.timer",
        "/lib/systemd/system/apt-daily.timer",
        "/lib/systemd/system/e2scrub_reap.service",
        "/usr/sbin/rmt-tar",
        "/bin/more",
        "/usr/bin/mawk",
        "/usr/bin/which.debianutils",
        "/usr/bin/apt",
    )

    @Test
    fun `真实 rootfs 的绝对链接都不算越界`() {
        val root = tmp.newFolder("rootfs")
        for (link in realAbsoluteLinks) {
            assertFalse("$link 被误判为越界", TarGzExtractor.absoluteLinkEscapesRoot(link))
            assertNotNull("$link 应能生成链接目标", TarGzExtractor.resolveLinkTarget(root, root, link))
        }
    }

    @Test
    fun `绝对链接里的点点仍在根内则放行`() {
        assertFalse(TarGzExtractor.absoluteLinkEscapesRoot("/run/../etc"))
        assertFalse(TarGzExtractor.absoluteLinkEscapesRoot("/usr/./bin"))
    }

    @Test
    fun `绝对链接里的点点逃出根外则拒绝`() {
        assertTrue(TarGzExtractor.absoluteLinkEscapesRoot("/../outside"))
        assertTrue(TarGzExtractor.absoluteLinkEscapesRoot("/usr/../../outside"))
    }

    @Test
    fun `相对链接仍然按宿主真实路径校验`() {
        val root = tmp.newFolder("rootfs2")
        val parent = File(root, "etc/alternatives").apply { mkdirs() }
        // 相对链接指向 rootfs 内部：放行
        assertNotNull(TarGzExtractor.resolveLinkTarget(root, parent, "../../usr/bin/mawk"))
        // 相对链接逃出 rootfs：拒绝
        assertNull(TarGzExtractor.resolveLinkTarget(root, parent, "../../../../outside"))
    }

    @Test
    fun `空链接一律拒绝`() {
        val root = tmp.newFolder("rootfs3")
        assertNull(TarGzExtractor.resolveLinkTarget(root, root, ""))
        assertNull(TarGzExtractor.resolveLinkTarget(root, root, "   "))
    }
}
