package io.zer0.muse.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class NdjsonTypeParityTest {

    private val serviceSource by lazy { File("src/main/java/io/zer0/muse/backup/BackupService.kt").readText() }
    private val botTypes = setOf(
        "botAnnotation",
        "botApproval",
        "botBinding",
        "botEvent",
        "botMessageCard",
        "botPublication",
        "botRoutine",
        "botTask",
        "botTaskRun",
        "botTaskStep",
    )

    @Test
    fun `bot table types are mapped exported and restored`() {
        val mapped = ndjsonTypeToMetaKey.keys.filter { it in botTypes }.toSet()
        val exported = Regex("writeTypedLines\\(writer,\\s*\"(bot[A-Za-z]+)\"")
            .findAll(serviceSource)
            .map { it.groupValues[1] }
            .toSet()
        val restoreBody = serviceSource.substringAfter("private suspend fun restoreSupplementalMuseRecord(")
        val restored = Regex("\"(bot[A-Za-z]+)\"\\s*->")
            .findAll(restoreBody)
            .map { it.groupValues[1] }
            .toSet()

        assertEquals(botTypes, mapped)
        assertEquals(botTypes, exported)
        assertEquals(botTypes, restored)
    }

    @Test
    fun `backlog dispatch and schedule records also have read back mappings`() {
        assertTrue("proactiveBacklogDispatch" in ndjsonTypeToMetaKey)
        assertTrue("proactiveBacklogSchedule" in ndjsonTypeToMetaKey)
    }
}
