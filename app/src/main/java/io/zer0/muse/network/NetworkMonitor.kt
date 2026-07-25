package io.zer0.muse.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import io.zer0.common.Logger
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * v1.0.15: 网络状态监听器。
 *
 * 基于 [ConnectivityManager.NetworkCallback] 实时监听网络可用性变化,用于 SSE 流被
 * [io.zer0.ai.core.ChatStreamEvent.StreamInterrupted] 中断后,网络恢复时触发自动重连。
 *
 * 设计:
 *  - [isOnline]: 暴露当前网络状态的 [StateFlow],UI 可观察显示"网络中断"提示
 *  - [onlineEvents]: 网络从断开 → 恢复时发射一次 Unit,ChatViewModel 收集此 Flow
 *    在收到 StreamInterrupted 后等待网络恢复自动重新生成
 *  - 通过 Koin single 全局注册,App 生命周期内只注册一次 callback
 *
 * 注意: NetworkMonitor 是应用级单例,无需手动 unregister(App 进程销毁时系统自动清理)。
 */
class NetworkMonitor(context: Context) {

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private val _isOnline = MutableStateFlow(checkOnline())
    /** 当前网络是否可用(含 INTERNET + VALIDATED 能力) */
    val isOnline: StateFlow<Boolean> = _isOnline.asStateFlow()

    private val _onlineEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    /** 网络从断开 → 恢复时发射一次 Unit(过滤初始在线和在线→在线的冗余事件) */
    val onlineEvents: SharedFlow<Unit> = _onlineEvents.asSharedFlow()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            // 网络从无到有,通知等待方
            if (!_isOnline.value) {
                _isOnline.value = true
                _onlineEvents.tryEmit(Unit)
                Logger.i("NetworkMonitor", "网络已恢复")
            }
        }

        override fun onLost(network: Network) {
            val online = checkOnline()
            if (_isOnline.value && !online) {
                _isOnline.value = false
                Logger.w("NetworkMonitor", "网络已断开")
            }
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            val online = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            if (online && !_isOnline.value) {
                _isOnline.value = true
                _onlineEvents.tryEmit(Unit)
                Logger.i("NetworkMonitor", "网络能力恢复(VALIDATED)")
            } else if (!online && _isOnline.value) {
                _isOnline.value = false
                Logger.w("NetworkMonitor", "网络能力丢失(VALIDATED)")
            }
        }
    }

    init {
        runCatching {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            connectivityManager?.registerNetworkCallback(request, callback)
        }.onFailure {
            Logger.w("NetworkMonitor", "registerNetworkCallback 失败", it)
        }
    }

    private fun checkOnline(): Boolean {
        return try {
            val cm = connectivityManager ?: return false
            val activeNetwork = cm.activeNetwork
            val capabilities = cm.getNetworkCapabilities(activeNetwork)
            capabilities != null &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } catch (e: Exception) {
            false
        }
    }
}
