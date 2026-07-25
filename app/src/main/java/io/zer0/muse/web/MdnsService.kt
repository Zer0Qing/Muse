package io.zer0.muse.web

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import io.zer0.common.Logger
import io.zer0.common.resultOf

/**
 * Phase 8.11: mDNS 服务发现(NSD — Network Service Discovery)。
 *
 * 在局域网内注册 `_http._tcp` 服务,让同一 Wi-Fi 下的设备能通过
 * Bonjour/Avahi/NSD 浏览器发现 muse Web 服务器。
 *
 * 实现:
 *  - [NsdManager.registerService] 注册服务(API 16+,无需额外权限)
 *  - 服务名 "muse-web",类型 "_http._tcp",端口与 WebServer 一致
 *  - [unregister] 在 WebServer 停止时注销服务
 *
 * 限制:
 *  - NSD 在某些国产 ROM 上可能被省电策略限制(后台不可发现)
 *  - 仅同一子网内可发现(跨 VLAN/VPN 不可见)
 *  - 服务注册是异步的,[register] 返回后不代表已上线
 */
class MdnsService(private val context: Context) {

    private val nsdManager: NsdManager =
        context.getSystemService(Context.NSD_SERVICE) as NsdManager

    private var registrationListener: NsdManager.RegistrationListener? = null

    /** 服务名(局域网内显示的名称)。 */
    private var registeredName: String? = null

    /**
     * 注册 mDNS 服务。
     * @param port WebServer 监听端口
     * @param serviceName 服务名(默认 "muse-web")
     */
    fun register(port: Int, serviceName: String = DEFAULT_SERVICE_NAME) {
        // 先注销旧的(避免重复注册导致 NsdManager 报错)
        unregister()

        val serviceInfo = NsdServiceInfo().apply {
            this.serviceName = serviceName
            this.serviceType = SERVICE_TYPE
            this.port = port
        }

        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                registeredName = info.serviceName
                Logger.i(TAG, "mDNS 服务已注册: ${info.serviceName} :${info.port}")
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Logger.e(TAG, "mDNS 注册失败: errorCode=$errorCode, service=${info.serviceName}")
                registrationListener = null
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) {
                Logger.i(TAG, "mDNS 服务已注销: ${info.serviceName}")
                registeredName = null
            }

            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Logger.w(TAG, "mDNS 注销失败: errorCode=$errorCode")
            }
        }

        registrationListener = listener
        resultOf {
            nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)
        }.onError { msg, _ ->
            Logger.e(TAG, "registerService 异常: $msg")
            registrationListener = null
        }
    }

    /** 注销 mDNS 服务(幂等,未注册时无操作)。 */
    fun unregister() {
        registrationListener?.let { listener ->
            resultOf { nsdManager.unregisterService(listener) }
            registrationListener = null
            registeredName = null
        }
    }

    companion object {
        private const val TAG = "MdnsService"
        private const val DEFAULT_SERVICE_NAME = "muse-web"
        /** NSD 服务类型(HTTP over TCP)。 */
        private const val SERVICE_TYPE = "_http._tcp."
    }
}
