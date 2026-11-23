package io.zer0.muse.runtime

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.zer0.muse.tools.TOOL_OUTPUTS_DIR
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PythonRuntimeSelfCheckTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun pythonNativeRuntimeIsNotPackaged() {
        val nativeDir = File(context.applicationInfo.nativeLibraryDir)
        val pythonLibraries = nativeDir.listFiles().orEmpty().map(File::getName).filter { name ->
            name == "libmuse_python.so" || name.startsWith("libpython") ||
                name.endsWith("_python.so") || ".cpython-" in name
        }
        assertTrue("Python native runtime must come from the downloadable rootfs: $pythonLibraries", pythonLibraries.isEmpty())
    }

    @Test
    fun ubuntuPythonRunsWithStandardLibrary() = runBlocking {
        assumeTrue("Install the Ubuntu runtime before running this check", PythonRuntime.isReady(context))
        PythonRuntime.ensureData(context).getOrThrow()

        val result = PythonRuntime.execPython(
            context,
            listOf("-c", "import ssl, sqlite3, json; print('python-rootfs-ok')"),
            timeoutMs = 30_000,
        )

        assertEquals("stderr=${result.stderr}", 0, result.exitCode)
        assertTrue(result.stdout.contains("python-rootfs-ok"))
    }

    @Test
    fun pythonCanReadAndWriteTheBoundApplicationFilesDirectory() = runBlocking {
        assumeTrue("Install the Ubuntu runtime before running this check", PythonRuntime.isReady(context))
        val output = File(context.filesDir, "runtime/python-test-${System.nanoTime()}.txt")
        val script = File(PythonRuntime.runtimeDir(context), "python-test-${System.nanoTime()}.py")
        script.parentFile?.mkdirs()
        script.writeText(
            "from pathlib import Path\n" +
                "import os\n" +
                "Path(os.environ['MUSE_TEST_OUTPUT']).write_text('proot-file-ok')\n" +
                "print(Path(os.environ['MUSE_TEST_OUTPUT']).read_text())\n",
        )

        try {
            val result = PythonRuntime.execPython(
                context,
                listOf(script.absolutePath),
                timeoutMs = 30_000,
                env = mapOf("MUSE_TEST_OUTPUT" to output.absolutePath),
            )
            assertEquals("stderr=${result.stderr}", 0, result.exitCode)
            assertEquals("proot-file-ok", output.readText())
        } finally {
            script.delete()
            output.delete()
        }
    }

    @Test
    fun largePythonOutputUsesTheSharedOutputCapture() = runBlocking {
        assumeTrue("Install the Ubuntu runtime before running this check", PythonRuntime.isReady(context))
        val result = PythonRuntime.execPython(
            context,
            listOf("-c", "print('x' * 100000)"),
            timeoutMs = 30_000,
        )

        assertEquals("stderr=${result.stderr}", 0, result.exitCode)
        assertTrue(result.stdout.contains(TOOL_OUTPUTS_DIR))
        assertTrue(result.stdoutFile?.isFile == true)
    }
}
