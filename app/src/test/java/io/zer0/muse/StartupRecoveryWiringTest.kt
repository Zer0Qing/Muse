package io.zer0.muse

import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class StartupRecoveryWiringTest {

    @Test
    fun `runtime services are deferred until interrupted restore rollback completes`() {
        val source =
            listOf(
                Path.of("src/main/java/io/zer0/muse/MuseApp.kt"),
                Path.of("app/src/main/java/io/zer0/muse/MuseApp.kt"),
            )
                .firstOrNull(Files::exists)
                ?.let { Files.newBufferedReader(it).use { reader -> reader.readText() } }
                ?: error("MuseApp.kt not found")

        val recovery = source.indexOf("recoverIncompleteRestore(gateAlreadyHeld = true)")
        val runtimeInit = source.indexOf("this@MuseApp.initializeRuntime()")

        assertTrue(source.contains("val initializeRuntime: MuseApp.() -> Unit"))
        assertTrue(source.contains("startupReady"))
        assertTrue(source.contains("ProcessWriteGate.begin()"))
        assertTrue(recovery >= 0)
        assertTrue(runtimeInit > recovery)
    }
}
