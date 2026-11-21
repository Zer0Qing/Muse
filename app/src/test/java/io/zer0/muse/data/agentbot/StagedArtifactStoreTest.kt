package io.zer0.muse.data.agentbot

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * V04 / V13：候选暂存与独占发布。
 *
 * 两条都在防同一件事：**把已经不该应用的结果应用到目标上**。
 *  - V04：约束变了，旧候选满足的是旧要求；
 *  - V13：目标被改过，直接写会覆盖别人的成果或用户的修改。
 */
class StagedArtifactStoreTest {

    private lateinit var dir: File
    private lateinit var store: StagedArtifactStore

    @Before
    fun setUp() {
        dir = File(System.getProperty("java.io.tmpdir"), "agentbot-staging-" + System.nanoTime());
        store = StagedArtifactStore(dir);
    }

    @After
    fun tearDown() {
        dir.deleteRecursively();
    }

    private fun candidate(
        revision: Int = 1,
        baseHash: String = "hash-v1",
    ) = StagedArtifactStore.Candidate(
        targetRef = "workspace/notes.md",
        content = "改好的内容",
        baseHash = baseHash,
        taskRevision = revision,
    );

    @Test
    fun `staged candidate round-trips`() = kotlinx.coroutines.test.runTest {
        val id = store.stage(candidate());
        val read = store.read(id);
        assertNotNull(read);
        assertEquals("workspace/notes.md", read!!.targetRef);
        assertEquals("改好的内容", read.content);
        assertEquals(1, read.taskRevision);
    }

    @Test
    fun `stale revision is discarded instead of applied`() = kotlinx.coroutines.test.runTest {
        val id = store.stage(candidate(revision = 1));
        var wrote = false

        val result = store.apply(
            stagedId = id,
            currentTaskRevision = 2,   // 用户追加了约束
            currentTargetHash = "hash-v1",
        ) { _, _ -> wrote = true };

        assertTrue("约束变了旧候选必须失效", result is StagedArtifactStore.ApplyResult.Stale);
        assertFalse("不能写目标", wrote);
        assertNull("过期候选直接丢弃", store.read(id));
    }

    @Test
    fun `base hash drift blocks the write`() = kotlinx.coroutines.test.runTest {
        val id = store.stage(candidate(baseHash = "hash-v1"));
        var wrote = false

        val result = store.apply(
            stagedId = id,
            currentTaskRevision = 1,
            currentTargetHash = "hash-v2",   // 目标被别人改过
        ) { _, _ -> wrote = true };

        assertTrue("目标被改过不能覆盖", result is StagedArtifactStore.ApplyResult.Conflict);
        assertFalse(wrote);
    }

    @Test
    fun `conflicting candidate is kept so the user can see it`() = kotlinx.coroutines.test.runTest {
        val id = store.stage(candidate(baseHash = "hash-v1"));
        store.apply(id, 1, "hash-v2") { _, _ -> };

        assertNotNull(
            "冲突候选要留着，让用户能看到这份结果本来想改成什么",
            store.read(id),
        )
    }

    @Test
    fun `matching revision and hash applies exactly once`() = kotlinx.coroutines.test.runTest {
        val id = store.stage(candidate());
        var writes = 0
        var written = ""

        val result = store.apply(id, 1, "hash-v1") { _, content ->
            writes++;
            written = content
        };

        assertTrue(result is StagedArtifactStore.ApplyResult.Applied);
        assertEquals(1, writes);
        assertEquals("改好的内容", written);
        assertNull("应用后暂存条目应清理", store.read(id));
    }

    @Test
    fun `applying the same candidate twice writes only once`() = kotlinx.coroutines.test.runTest {
        val id = store.stage(candidate());
        var writes = 0
        store.apply(id, 1, "hash-v1") { _, _ -> writes++ };
        store.apply(id, 1, "hash-v1") { _, _ -> writes++ };

        assertEquals("第二次找不到暂存条目，不该再写", 1, writes);
    }

    @Test
    fun `blank base hash means no baseline check`() = kotlinx.coroutines.test.runTest {
        // 新建文件没有基线，此时不该因为"哈希不匹配"就拒绝。
        val id = store.stage(candidate(baseHash = ""));
        var wrote = false
        val result = store.apply(id, 1, "anything") { _, _ -> wrote = true };

        assertTrue(result is StagedArtifactStore.ApplyResult.Applied);
        assertTrue(wrote);
    }

    @Test
    fun `missing staged entry is stale not a crash`() = kotlinx.coroutines.test.runTest {
        val result = store.apply("does-not-exist", 1, "hash") { _, _ -> };
        assertTrue(result is StagedArtifactStore.ApplyResult.Stale);
    }

    @Test
    fun `clearAll removes every staged file`() = kotlinx.coroutines.test.runTest {
        store.stage(candidate());
        store.stage(candidate());
        store.clearAll();
        assertEquals(0, dir.listFiles()?.size ?: 0);
    }
}