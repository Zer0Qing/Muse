# Muse v2.6.26 测试候选（未公开发行）

本包是供本地测试的候选包，从当前未提交工作树构建，未提交、未推送、未打标签、未建 Release、未部署官网。

## 本次可测的改动

- 修复了开机引导语言页默认勾选「简体中文」、界面却是英文的问题；现在默认勾选与实际界面语言一致，主动点选仍会即时切换。
- 修复了引导命名页键盘弹起时「上一步 / 下一步」被完全遮挡的问题，输入框补上了键盘「下一步 / 完成」动作。
- 修复了引导模型列表里标题和副标题重复显示同一个模型名的问题。
- 修复了聊天气泡长消息右侧圆角被裁、模型名标签贴屏幕边缘被截断的问题。
- 修复了小手机自绘状态栏时钟与系统状态栏重叠、底部标签文字压住手势导航条的问题。
- 修复了小手机「发现」页十几个入口全都用同一个指南针图标、长标签逐字符断行的问题。
- 修正了设置里「外观」分组仍提到小手机、以及一处英文标点不统一的问题。

## 请重点测试

- 小手机从旧版本升级到本包时的 v5→v8 数据库迁移：有旧数据的设备覆盖安装、备份恢复、升级中途被强杀。
- 全新安装后的开机引导全流程，以及配置自定义 OpenAI 兼容供应商后的首轮对话。
- 上述修复在窄屏、大字号、深色模式、手势导航下的观感。

## 已知情况

- 本包为脏工作树构建，未跑完整五条发行门禁；静态护栏存在工作树原有的存量告警，与本批次改动无关。
- 单测已覆盖本批次改动相关的定向用例，全量回归与真机验收由测试环节完成。

---

# Muse v2.6.26 (English)

This is a local test candidate built from the current uncommitted working tree. It has not been committed, pushed, tagged, published as a Release, or deployed to the website.

## What to test

- Fixed the setup language page showing Simplified Chinese as selected while the interface stayed in English; the default selection now matches the actual interface language, and picking a language still switches immediately.
- Fixed the setup name page where the Previous / Next buttons were fully hidden behind the keyboard; the fields now expose proper keyboard next / done actions.
- Fixed the model picker showing the same model name twice as title and subtitle.
- Fixed long chat bubbles having their right corner clipped and model-name labels being cut off at the screen edge.
- Fixed the Mini Phone status-bar clock overlapping the system status bar and the bottom tab labels colliding with the gesture navigation bar.
- Fixed the Mini Phone Discover grid using the same compass icon for almost every entry and long labels breaking mid-word.
- Corrected an outdated Appearance group description and one inconsistent punctuation mark.

## Focus areas

- Mini Phone v5 to v8 database migration on a device with existing data: upgrade install, backup and restore, and being killed mid-upgrade.
- The full first-run setup on a clean install, followed by a first chat after configuring a custom OpenAI-compatible provider.
- Visual checks on narrow screens, large font, dark theme, and gesture navigation.

## Known conditions

- Built from a dirty working tree; the full five-lane release gate was not run. Some static guards report pre-existing warnings unrelated to this batch.
- Targeted unit tests for this batch pass; full regression and real-device acceptance are left to the testing phase.
