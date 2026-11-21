package io.zer0.muse.data.agentbot

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * V04：冲突候选必须留审计，不能被静默删掉。
 *
 * 计划要求"历史候选仍留审计"。之前的实现里冲突候选确实保留了，
 * 但没有任何入口能列出它们 —— 用户看不到"这份结果本来想改成什么"。
 */
class StagedCandidateAuditTest {

    private lateinit var dir: File
    private lateinit var store: StagedArtifactStore

    @Before
    fun setUp() {
        dir = File(System.getProperty("java.io.tmpdir"), "audit-" + System.nanoTime());
        store = StagedArtifactStore(dir);
    }

    @After
    fun tearDown() {
        dir.deleteRecursively();
    }

    private fun candidate(ref: String = "notes.md") = StagedArtifactStore.Candidate(
        targetRef = ref,
        content = "改好的内容",
        baseHash = "hash-v1",
        taskRevision = 1,
    );

    @Test
    fun `staged candidates can be listed`() {
        store.stage(candidate("a.md"));
        store.stage(candidate("b.md"));

        val list = store.listStaged();
        assertEquals(2, list.size);
        assertTrue(list.any { it.candidate.targetRef == "a.md" });
        assertTrue(list.any { it.candidate.targetRef == "b.md" });
    }

    @Test
    fun `a conflicted candidate still shows up in the list`() = kotlinx.coroutines.test.runTest {
        val id = store.stage(candidate());
        // 模拟基线漂移导致冲突。
        store.apply(id, currentTaskRevision = 1, currentTargetHash = "hash-v2") { _, _ -> };

        val list = store.listStaged();
        assertEquals("冲突候选必须留审计", 1, list.size);
        assertEquals(id, list.single().stagedId);
    }

    @Test
    fun `an applied candidate disappears from the list`() = kotlinx.coroutines.test.runTest {
        val id = store.stage(candidate());
        store.apply(id, currentTaskRevision = 1, currentTargetHash = "hash-v1") { _, _ -> };

        assertTrue("应用后不该还在候选列表里", store.listStaged().isEmpty());
    }

    @Test
    fun `an expired candidate disappears from the list`() = kotlinx.coroutines.test.runTest {
        val id = store.stage(candidate());
        store.apply(id, currentTaskRevision = 2, currentTargetHash = "hash-v1") { _, _ -> };

        assertTrue("版本过期后直接丢弃，不占列表", store.listStaged().isEmpty());
    }

    @Test
    fun `discarding removes it from the list`() {
        val id = store.stage(candidate());
        store.discard(id);
        assertTrue(store.listStaged().isEmpty());
    }

    @Test
    fun `empty staging dir returns an empty list not a crash`() {
        val fresh = StagedArtifactStore(File(dir, "never-created"));
        assertTrue(fresh.listStaged().isEmpty());
    }
}
