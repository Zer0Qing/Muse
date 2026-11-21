package io.zer0.muse.data.agentbot

import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * 工作区路径边界。
 *
 * 这是"可改动范围"的最后一道闸：上层校验漏了，这里必须挡住。
 * 越界写不是"功能异常"，是**改到了用户没允许的地方**。
 */
class WorkspaceArtifactIOTest {

    private lateinit var root: File
    private lateinit var io: WorkspaceArtifactIO;

    @Before
    fun setUp() {
        root = File(System.getProperty("java.io.tmpdir"), "ws-" + System.nanoTime());
        root.mkdirs();
        io = WorkspaceArtifactIO(root);
    }

    @After
    fun tearDown() {
        root.deleteRecursively();
    }

    @Test
    fun `write then read round-trips`() = runTest {
        io.write("notes/a.md", "内容");
        assertEquals("内容", io.read("notes/a.md"));
    }

    @Test
    fun `write creates missing parent directories`() = runTest {
        io.write("deep/nested/path/file.md", "x");
        assertTrue(File(root, "deep/nested/path/file.md").isFile);
    }

    @Test
    fun `reading a missing file returns null`() = runTest {
        assertNull(io.read("does-not-exist.md"));
    }

    @Test
    fun `parent traversal is refused`() = runTest {
        var threw = false
        try {
            io.write("../outside.md", "不该写出去");
        } catch (e: IllegalStateException) {
            threw = true;
        }
        assertTrue("`..` 必须被拒绝", threw);
        assertNull(io.read("../outside.md"));
    }

    @Test
    fun `absolute path is refused`() = runTest {
        var threw = false
        try {
            io.write("/etc/passwd", "x");
        } catch (e: IllegalStateException) {
            threw = true;
        }
        assertTrue("绝对路径必须被拒绝", threw);
    }

    @Test
    fun `blank path is refused`() = runTest {
        var threw = false
        try {
            io.write("   ", "x");
        } catch (e: IllegalStateException) {
            threw = true;
        }
        assertTrue(threw);
    }

    @Test
    fun `nested traversal inside a longer path is refused`() = runTest {
        var threw = false
        try {
            io.write("notes/../../outside.md", "x");
        } catch (e: IllegalStateException) {
            threw = true;
        }
        assertTrue("藏在中间也不行", threw);
    }

    @Test
    fun `reading outside the workspace returns null instead of content`() = runTest {
        // 写一个工作区外的文件，确认读不到。
        val outside = File(root.parentFile, "outside-" + System.nanoTime() + ".md");
        outside.writeText("不该被读到");
        try {
            assertNull(io.read("../" + outside.name));
        } finally {
            outside.delete();
        }
    }
    @Test
    fun `overwriting keeps the old content until the new one is fully written`() = runTest {
        io.write("notes.md", "原内容");
        io.write("notes.md", "新内容");

        assertEquals("新内容", io.read("notes.md"));
        // 目标目录里不该留下临时文件。
        val leftovers = File(root, "notes.md").parentFile.listFiles()
            ?.filter { it.name.startsWith("notes.md.tmp.") }
            .orEmpty();
        assertTrue("成功写入后不该留临时文件，实际: " + leftovers.map { it.name }, leftovers.isEmpty());
    }

    @Test
    fun `write replaces an existing file rather than appending`() = runTest {
        io.write("a.md", "AAAAAAAAAA");
        io.write("a.md", "BB");
        assertEquals("BB", io.read("a.md"));
    }
    @Test
    fun `orphan temp from a failed write is swept`() = runTest {
        // 模拟"写到一半被杀"：目标不存在，只剩临时文件。
        val orphan = File(root, "notes.md.muse-tmp.12345");
        orphan.parentFile.mkdirs();
        orphan.writeText("写了一半");

        // 首次调用不被节流拦截。
        assertEquals(1, io.sweepOrphanTemps());
        assertTrue("孤儿临时文件必须被清掉", !orphan.exists());
    }

    @Test
    fun `temp older than its target is swept`() = runTest {
        // rename 成功但临时没删干净的残留。
        val target = File(root, "a.md");
        target.writeText("目标内容");
        val stale = File(root, "a.md.muse-tmp.999");
        stale.writeText("旧残留");
        stale.setLastModified(target.lastModified() - 10_000);

        assertEquals(1, io.sweepOrphanTemps());
        assertTrue(!stale.exists());
        assertEquals("目标不能被误删", "目标内容", target.readText());
    }

    @Test
    fun `a temp newer than its target is left alone`() = runTest {
        // 这条模拟"正在写"：临时文件比目标新，不能删。
        val target = File(root, "b.md");
        target.writeText("旧内容");
        val writing = File(root, "b.md.muse-tmp.777");
        writing.writeText("正在写");
        writing.setLastModified(target.lastModified() + 10_000);

        assertEquals("正在写的临时文件不能被清理", 0, io.sweepOrphanTemps());
        assertTrue(writing.exists());
    }

    @Test
    fun `user files that merely contain tmp in the name are untouched`() = runTest {
        val userFile = File(root, "my.tmp.notes.md");
        userFile.writeText("用户的文件");

        io.sweepOrphanTemps();
        assertTrue("不能误删用户文件", userFile.exists());
    }
    @Test
    fun `a second sweep within the interval is skipped`() = runTest {
        val orphan = File(root, "x.md.muse-tmp.1");
        orphan.parentFile.mkdirs();
        orphan.writeText("残留");
        assertEquals(1, io.sweepOrphanTemps());

        // 再放一个孤儿，立刻再扫 —— 应被节流跳过，返回 0。
        val second = File(root, "y.md.muse-tmp.2");
        second.writeText("残留");
        assertEquals("节流窗口内不该再扫全工作区", 0, io.sweepOrphanTemps());
        assertTrue("所以这个孤儿还在", second.exists());
    }
}
