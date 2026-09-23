package io.zer0.muse.automation.core

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [WaitPrimitives].
 *
 * Uses a mutable state holder to simulate screen changes without Android dependencies.
 */
class WaitPrimitivesTest {

    private val state = SnapshotState()

    private fun snapshotProvider() = ScreenSnapshot(
        info = ScreenInfo(
            packageName = state.packageName,
            activityName = state.activityName,
            nodes = state.nodes,
        ),
        version = state.version++,
    )

    @Test
    fun `waitForIdle returns true when screen stabilizes quickly`() = runTest {
        // Start with a node, then stabilize after one change
        state.updateNodes(listOf(UiNode(text = "Button", boundsLeft = 10, boundsTop = 20, boundsRight = 50, boundsBottom = 60)))
        // Second call returns same state
        var callCount = 0
        val stableProvider = suspend {
            callCount++
            if (callCount == 1) {
                ScreenSnapshot(
                    info = ScreenInfo(packageName = "com.test", nodes = listOf(UiNode(text = "Loading"))),
                    version = 1,
                )
            } else {
                ScreenSnapshot(
                    info = ScreenInfo(packageName = "com.test", nodes = listOf(UiNode(text = "Button", boundsLeft = 10, boundsTop = 20, boundsRight = 50, boundsBottom = 60))),
                    version = 2,
                )
            }
        }

        val result = WaitPrimitives.waitForIdle(
            snapshotProvider = stableProvider,
            timeoutMs = 2000L,
            pollIntervalMs = 50L,
            stabilityWindowMs = 100L,
        )
        assertTrue("Should detect idle state", result)
    }

    @Test
    fun `waitForIdle returns false on timeout`() = runTest {
        var tick = 0
        val changingProvider = suspend {
            tick++
            ScreenSnapshot(
                info = ScreenInfo(
                    packageName = "com.test",
                    nodes = listOf(UiNode(text = "Tick $tick", boundsLeft = tick, boundsTop = 0, boundsRight = tick + 10, boundsBottom = 10)),
                ),
                version = tick,
            )
        }

        val result = WaitPrimitives.waitForIdle(
            snapshotProvider = changingProvider,
            timeoutMs = 150L,
            pollIntervalMs = 50L,
            stabilityWindowMs = 100L,
        )
        assertFalse("Should timeout on continuously changing screen", result)
    }

    @Test
    fun `waitForWindowChange detects package change`() = runTest {
        var tick = 0
        val changingProvider = suspend {
            tick++
            val pkg = if (tick == 1) "com.old.app" else "com.new.app"
            ScreenSnapshot(
                info = ScreenInfo(packageName = pkg, activityName = "com.new.app/.MainActivity", nodes = emptyList()),
                version = tick,
            )
        }

        val result = WaitPrimitives.waitForWindowChange(
            initialPackage = "com.old.app",
            initialActivity = null,
            snapshotProvider = changingProvider,
            timeoutMs = 2000L,
            pollIntervalMs = 50L,
        )
        assertTrue("Should detect package change", result)
    }

    @Test
    fun `waitForWindowChange returns false on timeout when no change`() = runTest {
        val stableProvider = suspend {
            ScreenSnapshot(
                info = ScreenInfo(packageName = "com.stable.app", activityName = "com.stable.app/.MainActivity", nodes = emptyList()),
                version = 1,
            )
        }

        val result = WaitPrimitives.waitForWindowChange(
            initialPackage = "com.stable.app",
            initialActivity = "com.stable.app/.MainActivity",
            snapshotProvider = stableProvider,
            timeoutMs = 150L,
            pollIntervalMs = 50L,
        )
        assertFalse("Should timeout when no window change", result)
    }

    @Test
    fun `waitForText finds text on screen`() = runTest {
        val provider = suspend {
            ScreenSnapshot(
                info = ScreenInfo(
                    packageName = "com.test",
                    nodes = listOf(
                        UiNode(text = "Welcome to App", boundsLeft = 0, boundsTop = 0, boundsRight = 100, boundsBottom = 20),
                    ),
                ),
                version = 1,
            )
        }

        val result = WaitPrimitives.waitForText(
            text = "Welcome",
            snapshotProvider = provider,
            timeoutMs = 1000L,
            pollIntervalMs = 50L,
        )
        assertTrue("Should find matching text", result)
    }

    @Test
    fun `waitForText returns false when text never appears`() = runTest {
        val provider = suspend {
            ScreenSnapshot(
                info = ScreenInfo(
                    packageName = "com.test",
                    nodes = listOf(
                        UiNode(text = "Hello World", boundsLeft = 0, boundsTop = 0, boundsRight = 100, boundsBottom = 20),
                    ),
                ),
                version = 1,
            )
        }

        val result = WaitPrimitives.waitForText(
            text = "Goodbye",
            snapshotProvider = provider,
            timeoutMs = 150L,
            pollIntervalMs = 50L,
        )
        assertFalse("Should timeout when text never appears", result)
    }

    @Test
    fun `waitForText case insensitive match`() = runTest {
        val provider = suspend {
            ScreenSnapshot(
                info = ScreenInfo(
                    packageName = "com.test",
                    nodes = listOf(
                        UiNode(text = "SUBMIT", boundsLeft = 0, boundsTop = 0, boundsRight = 100, boundsBottom = 20),
                    ),
                ),
                version = 1,
            )
        }

        val result = WaitPrimitives.waitForText(
            text = "submit",
            appear = true,
            snapshotProvider = provider,
            timeoutMs = 1000L,
            pollIntervalMs = 50L,
        )
        assertTrue("Should do case-insensitive match", result)
    }

    @Test
    fun `waitForText disappearance`() = runTest {
        var tick = 0
        val disappearingProvider = suspend {
            tick++
            val nodes = if (tick <= 1) {
                listOf(UiNode(text = "Loading...", boundsLeft = 0, boundsTop = 0, boundsRight = 100, boundsBottom = 20))
            } else {
                emptyList()
            }
            ScreenSnapshot(
                info = ScreenInfo(packageName = "com.test", nodes = nodes),
                version = tick,
            )
        }

        val result = WaitPrimitives.waitForText(
            text = "Loading",
            appear = false,
            snapshotProvider = disappearingProvider,
            timeoutMs = 1000L,
            pollIntervalMs = 50L,
        )
        assertTrue("Should detect text disappearance", result)
    }
}

/** Simple mutable state holder for test providers. */
private class SnapshotState {
    var packageName: String = "com.test"
    var activityName: String? = null
    var nodes: List<UiNode> = emptyList()
    var version: Int = 1

    fun updateNodes(nodes: List<UiNode>) {
        this.nodes = nodes
        version++
    }
}
