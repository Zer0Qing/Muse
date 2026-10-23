package io.zer0.muse.feature

/**
 * v2.6.7: 功能开关 —— 未成熟功能的统一显隐闸门。
 *
 * 为什么用编译期常量而不是运行时设置：
 *  - 运行时开关（如 `miniPhoneEnabledFlow`）对用户可见、可绕过，隐藏不彻底；
 *  - 编译期常量能让 R8 把不可达分支裁掉，产物里连入口代码都不剩；
 *  - "之后再加回来"只需把常量改回 true，**代码一行不删**。
 *
 * 约定：所有对外入口（首页菜单、设置条目、导航图路由注册）都必须过这里，
 * 不能只藏 UI 而留着路由 —— 否则通过 deep link / 恢复导航仍能进入。
 */
object FeatureFlags {
    /**
     * 小手机（MiniPhone）完整功能。
     *
     * v2.6.6 发版时关闭：该功能尚在打磨，UI 形态与交互未定稿，暂不对外发布。
     * 相关代码、数据表、设置项全部保留，改回 `true` 即恢复。
     */
    const val MINI_PHONE: Boolean = false

    /** 小手机内嵌的"AI 朋友圈"（Moments）。依赖 [MINI_PHONE]。 */
    const val MOMENTS: Boolean = MINI_PHONE
}
