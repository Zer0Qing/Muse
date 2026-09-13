# Muse v1.0.87

本版本完成 Host Mode 远程界面基础能力、Android 动态布局滚动边界治理，并继续收口生成、记忆和会话稳定性。

## 重点更新

### Host Mode 与 Web 远程界面

- Android Muse 作为唯一运行时，Web 端通过认证 WebSocket 远程展示和操作真实会话。
- Host 协议加入 protocolVersion、connectionId、eventSeq、cursor、requestId、generationId、turnId 和 capabilityFlags。
- 支持 Host 会话新建、选择、重命名、归档、删除、重新生成、继续生成和停止生成。
- 支持 Web 工具审批卡片，审批决策仍由 Android 运行时执行。
- WebSocket 初始连接失败重试、断线重连、递增退避和 cursor 状态同步。
- React Host UI 构建产物自动打入 Android APK。

### 动态布局与滚动

- 快速记录标签和文件夹筛选改为有界横向滚动，标签数量增加时不会挤掉历史记录。
- 快速记录历史列表使用剩余空间布局，并补齐底部系统安全区。
- 贴纸分类、Provider/模型/生成参数、群聊成员、主持人、图片、工具附件、RAG 引用和通用多选资源统一治理动态内容边界。
- 用户数据驱动的动态筛选内容采用 LazyRow 或有界列表；固定数量的静态选项保留 FlowRow。

### 稳定性与体验

- Android 流式生成开始时自动收起顶部菜单，避免状态残留。
- 顶部操作控件满足 48dp 最小触控区域。
- 剪贴板标签和设置页 Agent 文案完成资源化。
- 群聊列表接入区域级数据错误隔离。
- Web Host 消息区域加入无障碍实时语义，支持系统深色模式。
- Web 会话缓存增加结构和过期校验。

## 构建信息

- versionName：1.0.87
- versionCode：187
- 构建类型：Release
- 架构：arm64-v8a、armeabi-v7a、universal
- 数据库版本：沿用现有数据库版本，无新增迁移
- 正式构建：由 CI tag `v1.0.87` 触发，并注入 versionName/versionCode

## 验证信息

- Web typecheck、test、security check、build
- Android detekt、compileDebugKotlin、testDebugUnitTest
- Host 协议与生成管理器定向测试
- APK 签名、版本、manifest 和 SHA-256 校验
- Android 15 x86_64 模拟器安装与运行回归
- 快速记录约 36 个标签的横向滚动和历史列表可见性验证

## 已知限制

- 尚未完成真实 Provider 长流、Host 局域网、多浏览器并发、后台/锁屏/进程回收的完整设备矩阵。
- 正式 Release 签名仅使用 CI secret keystore，本地不输出或读取签名密码。
