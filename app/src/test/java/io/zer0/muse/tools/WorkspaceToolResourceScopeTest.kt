package io.zer0.muse.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WorkspaceToolResourceScopeTest {

    private val knownMountIds = setOf("main", "docs")

    @Test
    fun `declared file and descendants are writable`() {
        assertNull(
            WorkspaceTool.resourceScopeError(
                WorkspaceTool.NAME_WRITE,
                mapOf("path" to "main:/docs/notes.md"),
                "docs/notes.md",
                knownMountIds,
            ),
        )
        assertNull(
            WorkspaceTool.resourceScopeError(
                WorkspaceTool.NAME_EDIT,
                mapOf("path" to "main:/docs/drafts/one.md"),
                "main:/docs",
                knownMountIds,
            ),
        )
    }

    @Test
    fun `multiple declared paths are accepted individually`() {
        assertNull(
            WorkspaceTool.resourceScopeError(
                WorkspaceTool.NAME_WRITE,
                mapOf("path" to "main:/notes.md"),
                "notes.md, reports/",
                knownMountIds,
            ),
        )
        assertNull(
            WorkspaceTool.resourceScopeError(
                WorkspaceTool.NAME_WRITE,
                mapOf("path" to "main:/reports/weekly.md"),
                "notes.md, reports/",
                knownMountIds,
            ),
        )
    }

    @Test
    fun `out of scope mount and parent paths are refused`() {
        assertEquals(
            "目标超出任务声明范围: main:/private.txt",
            WorkspaceTool.resourceScopeError(
                WorkspaceTool.NAME_WRITE,
                mapOf("path" to "main:/private.txt"),
                "main:/docs",
                knownMountIds,
            ),
        )
        assertEquals(
            "目标超出任务声明范围: docs:/notes.md",
            WorkspaceTool.resourceScopeError(
                WorkspaceTool.NAME_WRITE,
                mapOf("path" to "docs:/notes.md"),
                "main:/notes.md",
                knownMountIds,
            ),
        )
        assertEquals(
            "目标包含越界路径: main:/docs/../private.txt",
            WorkspaceTool.resourceScopeError(
                WorkspaceTool.NAME_DELETE,
                mapOf("path" to "main:/docs/../private.txt"),
                "main:/docs",
                knownMountIds,
            ),
        )
    }

    @Test
    fun `empty resource scope denies workspace mutations`() {
        assertEquals(
            "任务未声明可写资源范围",
            WorkspaceTool.resourceScopeError(
                WorkspaceTool.NAME_MKDIR,
                mapOf("path" to "docs/new"),
                "",
                knownMountIds,
            ),
        )
    }

    @Test
    fun `move requires both paths to stay within scope`() {
        assertEquals(
            "目标超出任务声明范围: main:/private.md",
            WorkspaceTool.resourceScopeError(
                WorkspaceTool.NAME_MOVE,
                mapOf("from" to "main:/docs/a.md", "to" to "main:/private.md"),
                "main:/docs",
                knownMountIds,
            ),
        )
    }
}
