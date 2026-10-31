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
     * v2.6.6 关闭过一轮（当时 UI 与交互未定稿）；v2.6.12 起已重写为独立模块
     * （`:miniphone`，自带数据库与美术体系），重新对外开放。
     */
    const val MINI_PHONE: Boolean = true

    /**
     * 小手机是否在首页与设置分类页露出入口。
     *
     * 与本功能本身的存放分开：[MINI_PHONE] 控制模块与路由是否存在，
     * 这一项只控制“看不看得见”。半成品阶段不给主推入口，
     * 搜索仍能命中、链接仍能进入，模块照常打进包里。
     */
    const val MINI_PHONE_ENTRY: Boolean = false

    /** 小手机内嵌的"AI 朋友圈"（Moments）。依赖 [MINI_PHONE]。 */
    const val MOMENTS: Boolean = MINI_PHONE
}
