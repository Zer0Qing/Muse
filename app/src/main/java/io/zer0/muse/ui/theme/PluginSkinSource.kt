package io.zer0.muse.ui.theme

import kotlinx.coroutines.flow.Flow

/**
 * 已安装插件提供的声明式气泡皮肤。
 *
 * [skin] 必须已经通过 [BubbleSkinValidator.isValid];[enabled] 为 false 表示插件未确认或已禁用,
 * 此时该条目只允许用于设置页展示「不可用」,不得进入渲染路径。
 */
data class InstalledSkin(
    val pluginId: String,
    val pluginName: String,
    val version: String,
    val enabled: Boolean,
    val skin: BubbleSkin,
)

/**
 * 插件皮肤来源 — 宿主渲染层只依赖这个窄接口,不直接依赖插件管理器实现。
 *
 * 实现方必须保证:[listInstalledSkins] 返回的皮肤都经过 [BubbleSkinValidator] 校验,
 * 且只有当前可用(已确认、已启用、签名/信任/摘要复核通过)的插件才以 [InstalledSkin.enabled] = true 返回;
 * 插件被禁用、卸载或信任被撤销后,下一次读取不得再返回其可用皮肤。
 * 皮肤始终只是声明式数据,不允许把插件代码或 Compose 对象带过这个边界。
 */
interface PluginSkinSource {

    /**
     * 读取已安装插件的皮肤。
     *
     * @param includeDisabled true 时同时返回已安装但当前不可用的皮肤(供设置页提示),
     * 否则只返回可进入渲染路径的皮肤。
     */
    fun listInstalledSkins(includeDisabled: Boolean = false): List<InstalledSkin>

    /** 插件注册表变更信号;值变化表示皮肤列表可能已变化,收集方应重新读取。 */
    val revisionFlow: Flow<Int>
}
