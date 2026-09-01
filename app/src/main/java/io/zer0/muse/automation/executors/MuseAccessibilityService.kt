package io.zer0.muse.automation.executors

/**
 * Muse 无障碍服务的主 App 组件。
 *
 * 组件名保持在 [io.zer0.muse.automation.executors.MuseAccessibilityService],
 * 这样已经在系统设置中开启过旧版本服务的用户可以继续沿用同一个授权项。
 * 具体读屏、手势和输入实现统一继承自 :accessibility 模块,避免主 App 与库模块
 * 各注册一份服务导致“系统已开启但应用检查的是另一份实例”。
 */
class MuseAccessibilityService : io.zer0.muse.accessibility.MuseAccessibilityService() {
    companion object {
        /** 当前主 App 无障碍服务实例;基类实例由系统生命周期负责维护。 */
        val instance: MuseAccessibilityService?
            get() = io.zer0.muse.accessibility.MuseAccessibilityService.instance as? MuseAccessibilityService

        /** 服务是否已经完成系统连接。 */
        fun isConnected(): Boolean = instance != null
    }
}
