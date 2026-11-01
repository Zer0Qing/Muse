package io.zer0.muse.automation.vdisplay

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * v2.2.1 虚拟屏:面向调用方(工具/Agent)的便捷客户端。
 *
 * 负责:ensure 服务端+虚拟屏、单帧截图、在虚拟屏里打开应用、销毁。
 * 输入注入不在本类(用 `input -d <displayId>` 走既有 shell 执行器)。
 */
class VirtualDisplayClient(
    private val context: Context,
    private val manager: VirtualDisplayServerManager,
) {
    data class DisplayHandle(val displayId: Int, val width: Int, val height: Int, val dpi: Int = DEFAULT_DPI)

    @Volatile
    private var compatibilityWidth: Int = DEFAULT_WIDTH

    @Volatile
    private var compatibilityHeight: Int = DEFAULT_HEIGHT

    @Volatile
    private var compatibilityDpi: Int = DEFAULT_DPI

    /**
     * 虚拟屏通道是否可用（"ready(Shizuku)" / "ready(Root)" / 不可用原因）。
     *
     * 给「权限状态」类工具用：模型在选 virtual_* 之前就能知道能不能用，
     * 而不是先试一次、失败再换方案（每次失败都烧一轮）。
     */
    suspend fun availability(): String = manager.channelState()

    /** 确保服务端 + 虚拟屏就绪(同尺寸复用);失败携带可读原因。 */
    suspend fun ensureDisplay(width: Int = DEFAULT_WIDTH, height: Int = DEFAULT_HEIGHT, dpi: Int = DEFAULT_DPI): Result<DisplayHandle> =
        withContext(Dispatchers.IO) {
            val started =
                try {
                    manager.ensureStarted()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    return@withContext Result.failure(error)
                }
            val proxy = started.getOrElse { return@withContext Result.failure(it) }
            val id =
                try {
                    proxy.ensureDisplay(width, height, dpi)
                } catch (e: Exception) {
                    -1
                }
            if (id < 0) {
                return@withContext Result.failure(
                    IllegalStateException("虚拟屏创建失败(详见 /data/local/tmp/muse-vd-server.log)"),
                )
            }
            compatibilityWidth = width
            compatibilityHeight = height
            compatibilityDpi = dpi
            manager.lastDisplayId = id
            Result.success(DisplayHandle(id, width, height, dpi))
        }

    /** Recreate or reuse the process-wide compatibility display after a service restart. */
    suspend fun ensureCompatibilityDisplay(): Result<DisplayHandle> =
        ensureDisplay(compatibilityWidth, compatibilityHeight, compatibilityDpi)

    /** 创建本次调用独占的虚拟屏，不复用 `ensureDisplay` 的兼容屏；调用方负责 finally 销毁。 */
    suspend fun createDisplay(width: Int = DEFAULT_WIDTH, height: Int = DEFAULT_HEIGHT, dpi: Int = DEFAULT_DPI): Result<DisplayHandle> =
        withContext(Dispatchers.IO) {
            val proxy = manager.ensureStarted().getOrElse { return@withContext Result.failure(it) }
            val id = try {
                // Once the remote side effect starts, do not let cancellation hide the allocated displayId.
                withContext(NonCancellable) { proxy.createDisplay(width, height, dpi) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                -1
            }
            if (id < 0) {
                return@withContext Result.failure(
                    IllegalStateException("独立虚拟屏创建失败(服务端容量已满或详见 /data/local/tmp/muse-vd-server.log)"),
                )
            }
            try {
                currentCoroutineContext().ensureActive()
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) { runCatching { proxy.destroyDisplay(id) } }
                throw cancelled
            }
            Result.success(DisplayHandle(id, width, height, dpi))
        }

    /** 抓取一帧(JPEG 字节);兼容虚拟屏失效时自动刷新一次 display。 */
    suspend fun screenshot(displayId: Int = manager.lastDisplayId): ByteArray? = withContext(Dispatchers.IO) {
        // The compatibility display is the only display this client can safely recreate.
        // Dedicated GUI-Agent displays are owned by the caller and must not be replaced here.
        val compatibilityRequest = displayId >= 0 && displayId == manager.lastDisplayId
        val proxy = manager.ensureStarted().getOrNull() ?: return@withContext null
        val id = if (displayId >= 0) displayId else manager.lastDisplayId
        if (id < 0) return@withContext null
        try {
            proxy.requestScreenshot(id)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            if (!compatibilityRequest) {
                clearCachedDisplayIf(id)
                return@withContext null
            }
            clearCachedDisplayIf(id)
            val refreshed = ensureCompatibilityDisplay().getOrNull() ?: return@withContext null
            val refreshedProxy = manager.ensureStarted().getOrNull() ?: return@withContext null
            try {
                refreshedProxy.requestScreenshot(refreshed.displayId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                clearCachedDisplayIf(refreshed.displayId)
                null
            }
        }
    }

    /** 在虚拟屏里打开应用:兼容屏失效时自动刷新一次 display。 */
    suspend fun openApp(packageName: String, displayId: Int = manager.lastDisplayId): Boolean = withContext(Dispatchers.IO) {
        if (!PACKAGE_REGEX.matches(packageName) || displayId < 0) return@withContext false
        val compatibilityRequest = displayId == manager.lastDisplayId
        val proxy = manager.existingLiveProxy()
        if (proxy != null) {
            try {
                return@withContext proxy.launchApp(packageName, displayId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (!compatibilityRequest) {
                    clearCachedDisplayIf(displayId)
                    return@withContext false
                }
                clearCachedDisplayIf(displayId)
            }
        } else if (!compatibilityRequest) {
            return@withContext launchViaShell(packageName, displayId)
        }

        if (compatibilityRequest) {
            val refreshed = ensureCompatibilityDisplay().getOrNull() ?: return@withContext false
            val refreshedProxy = manager.ensureStarted().getOrNull()
            if (refreshedProxy != null) {
                try {
                    return@withContext refreshedProxy.launchApp(packageName, refreshed.displayId)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    clearCachedDisplayIf(refreshed.displayId)
                    return@withContext false
                }
            }
            return@withContext launchViaShell(packageName, refreshed.displayId)
        }

        return@withContext launchViaShell(packageName, displayId)
    }

    private suspend fun launchViaShell(packageName: String, displayId: Int): Boolean {
        val resolved = manager.exec("cmd package resolve-activity --brief $packageName")
        val component =
            resolved.output
                .lineSequence()
                .map { it.trim() }
                .lastOrNull { it.contains('/') }
                ?: return false
        // 防注入:组件名出现 shell 元字符直接拒绝(包名已白名单,这里是双保险)
        if (component.any { it in INJECTION_CHARS }) return false
        return manager.exec("am start --display $displayId -n $component").exitCode == 0
    }

    /** 销毁虚拟屏(服务端不存在时返回 false,不算错误)。 */
    suspend fun destroy(displayId: Int = manager.lastDisplayId): Boolean = withContext(Dispatchers.IO) {
        val proxy = manager.existingLiveProxy() ?: run {
            if (displayId == manager.lastDisplayId) manager.lastDisplayId = -1
            return@withContext false
        }
        runCatching {
            proxy.destroyDisplay(displayId)
            if (displayId == manager.lastDisplayId) manager.lastDisplayId = -1
            true
        }.getOrDefault(false)
    }

    private fun clearCachedDisplayIf(displayId: Int) {
        if (displayId >= 0 && displayId == manager.lastDisplayId) {
            manager.lastDisplayId = -1
        }
    }

    companion object {
        private const val DEFAULT_WIDTH = 720
        private const val DEFAULT_HEIGHT = 1280
        private const val DEFAULT_DPI = 320

        private val PACKAGE_REGEX = Regex("^[a-zA-Z][a-zA-Z0-9_.]*$")

        /** shell 元字符集合(组件名出现即拒绝,防注入双保险)。 */
        private val INJECTION_CHARS = setOf(' ', ';', '&', '|', '<', '>', '\$', '`', '\'', '"', '\\')
    }
}
