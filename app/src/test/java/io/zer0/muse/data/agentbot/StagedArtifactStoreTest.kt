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
    fun `structured artifact metadata survives storage and older records still load`() {
        val staged = store.stageArtifact(
            StagedArtifactStore.Candidate(
                targetRef = "workspace/notes.md",
                content = "新的正文",
            baseHash = StagedArtifactStore.hashSnapshot("旧正文"),
                taskRevision = 3,
                sourceRunId = "run-3",
                verificationState = VerificationState.VERIFIED,
            hasBaseSnapshot = true,
            baseContent = "旧正文",
            ),
        )

        val restored = store.readArtifact(staged.ref)
        assertEquals("workspace/notes.md", restored?.targetPath)
        assertEquals(StagedArtifactStore.hashOf("新的正文"), restored?.contentHash)
        assertEquals(StagedArtifactStore.hashSnapshot("旧正文"), restored?.baseHash)
        assertEquals(VerificationState.VERIFIED, restored?.verificationState)
        assertEquals("run-3", restored?.sourceRunId)
        assertEquals("旧正文", store.read(staged.ref)?.baseContent)

        val oldV2 = File(dir, "cand-v2")
        oldV2.writeText(
            listOf(
                "MUSE_ARTIFACT_V2",
                "workspace/v2.md",
                "old-hash",
                "2",
                "run-v2",
                VerificationState.PROPOSED,
                StagedArtifactStore.hashOf("v2 body"),
                "v2 body",
            ).joinToString("\n"),
        )
        assertEquals("v2 body", store.read("cand-v2")?.content)
        assertFalse(store.read("cand-v2")?.hasBaseSnapshot ?: true)

        val oldFile = File(dir, "cand-legacy")
        oldFile.writeText("workspace/old.md\nold-hash\n1\nlegacy body")
        val legacy = store.read("cand-legacy")
        assertEquals("legacy body", legacy?.content)
        assertEquals(1, legacy?.taskRevision)
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
        assertEquals(VerificationState.APPLIED_PENDING_COMMIT, store.readArtifact(id)?.verificationState)
    }

    @Test
    fun `retry after target write recognizes the applied content without writing twice`() = kotlinx.coroutines.test.runTest {
        val id = store.stage(candidate());
        var writes = 0
        val first = store.apply(id, 1, "hash-v1") { _, _ -> writes++ }
        val retry = store.apply(id, 1, StagedArtifactStore.hashOf("改好的内容")) { _, _ -> writes++ }

        assertTrue(first is StagedArtifactStore.ApplyResult.Applied)
        assertTrue(retry is StagedArtifactStore.ApplyResult.Applied)
        assertEquals("恢复重试只能识别已写入的内容", 1, writes)
        assertNotNull(store.read(id))
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
    fun `new file candidate only applies while the target is still missing`() = kotlinx.coroutines.test.runTest {
        val conflictId = store.stage(candidate(baseHash = StagedArtifactStore.MISSING_TARGET_HASH))
        var writes = 0

        val conflict = store.apply(conflictId, 1, StagedArtifactStore.hashSnapshot("created by user")) { _, _ -> writes++ }
        assertTrue("用户新建的同路径文件不能被覆盖", conflict is StagedArtifactStore.ApplyResult.Conflict)
        assertEquals(0, writes)
        assertEquals(VerificationState.CONFLICTED, store.readArtifact(conflictId)?.verificationState)

        val missingId = store.stage(candidate(baseHash = StagedArtifactStore.MISSING_TARGET_HASH))
        val apply = store.apply(missingId, 1, StagedArtifactStore.MISSING_TARGET_HASH) { _, _ -> writes++ }
        assertTrue(apply is StagedArtifactStore.ApplyResult.Applied)
        assertEquals(1, writes)
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

    @Test
    fun `an empty staging dir is a successful empty result`() {
        val fresh = StagedArtifactStore(File(dir, "never-created"));

        val result = fresh.listStagedResult();

        assertTrue(
            "没有候选时必须是成功结果，而不是 Error",
            result is StagedArtifactStore.StagedListResult.Success,
        );
        assertTrue((result as StagedArtifactStore.StagedListResult.Success).entries.isEmpty());
    }

    @Test
    fun `a staging path that is not a directory is an error`() {
        // 目录名被一个普通文件占住：listFiles() 只会返回 null，与"空目录"分不开。
        val blocked = File(System.getProperty("java.io.tmpdir"), "agentbot-blocked-" + System.nanoTime());
        blocked.writeText("not a directory");
        try {
            val result = StagedArtifactStore(blocked).listStagedResult();

            assertTrue(
                "读失败不能被伪装成空列表",
                result is StagedArtifactStore.StagedListResult.Error,
            );
        } finally {
            blocked.delete();
        }
    }
}
