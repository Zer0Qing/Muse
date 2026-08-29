# Muse v1.0.84

本版本自 v1.0.83 以来的完整改动如下。

## 每日总结与主动陪伴

- 将每日总结调整为每天 09:00、12:00、21:00、00:00 四个独立时点。
- 09:00、12:00、21:00 总结当天内容，00:00 总结前一天内容，按系统本地时区计算日期边界。
- 使用四条独立的 WorkManager OneTimeWork 链，避免多个时点之间互相覆盖。
- 增加进程内准点触发器；应用进程仍存活时直接投递对应总结任务，WorkManager 继续承担进程被杀后的兜底执行。
- 使用 DataStore 持久化时点占位，避免进程内调度与 WorkManager 同时触发造成重复生成。
- 总结生成失败时释放时点占位，允许后续任务重试。
- 通知开关与总结生成解耦；关闭通知后仍保存总结并在首页展示，应用在前台时保持静默。
- 增加指定本地日期区间读取用户消息的 DAO 与 Repository 接口。

## 工具调用与模型能力

- 主聊天请求改为向模型一次性展示当前运行时 ToolRegistry 中的全部工具定义。
- 移除旧 Assistant.toolIdsJson 历史白名单对主聊天工具 schema 的截断，避免默认助手遗留的 31 项快照遮蔽 100+ 工具。
- 主聊天加载全部已启用 Skills，不再因旧助手或会话 Skill 白名单导致能力缺失。
- 移除弱工具模型对委派、子代理、任务计划和计划步骤工具的额外裁剪。
- 系统提示中的工具能力索引与当前运行时完整工具集合保持一致，避免提示词清单和实际 schema 不一致。
- 保留工具名去重、工具执行权限校验和高风险工具审批机制；完整展示不改变高风险工具的执行授权规则。
- 工具注册器启动时统一实例化，确保工作区、文件、网络、系统、UI 自动化和其他工具在首次进入聊天时已经进入运行时注册表。

## 任务计划与会话恢复

- 从已落库的 task_plan 与 update_plan_step 工具消息恢复历史任务计划。
- 重新进入会话后恢复计划卡，并将恢复结果同步回工具执行器缓存，后续可以继续更新步骤。
- AgentPlan 增加所属 sessionId，计划状态、最大工具轮次计算和执行过程按会话隔离。
- 修复多会话并行时计划互相覆盖、清空或串台的问题。
- 空步骤计划不会生成无意义的空计划卡。

## UI 自动化与系统权限

- 第二层 Shell 状态只认真实 Shizuku 服务授权，不再用普通应用进程可以执行 sh 误判为“已启用”。
- UI 自动化 Shell 执行接入 Shizuku UserService，并保留 Root 作为独立降级通道。
- 截屏通过 base64 在 Shizuku 文本通道传输，避免 PNG 二进制经过文本流时损坏。
- 修复 UI 自动化权限等级依赖 enum ordinal 的脆弱判断，明确 NONE、ACCESSIBILITY、SHELL、ROOT 的能力等级。
- 从 Shizuku 或 Root 设置页返回 Muse 后重新探测权限状态，避免页面显示旧状态。
- 补充权限等级和 Shell 执行相关回归测试。

## 底部菜单、弹层与返回行为

- 修复长按会话菜单和文件夹菜单嵌套 verticalScroll 导致的无限高度测量崩溃。
- 重写共享底部菜单组件，改用 Material3 ModalBottomSheet 统一负责窗口、底部锚定、返回键、外部点击、拖拽和系统栏避让。
- 加号菜单、长按会话菜单和长按文件夹菜单统一使用新的共享实现。
- 高度限制只应用于 Sheet 内容，不再限制 ModalBottomSheet 根节点的 Window 测量，修复菜单整体飞到屏幕上方的问题。
- 内容超出最大高度时仅在菜单内部滚动，避免滚动容器嵌套和窗口坐标系错位。
- 移除主导航中与 Navigation Compose 重复竞争的全局返回拦截，恢复页面栈默认返回处理。
- 知识库索引、Skill 导入、本地备份和云备份的进度弹窗支持关闭展示层，后台任务继续执行，避免返回键被空回调吞掉。

## 聊天界面与交互稳定性

- 修复 ChatScreen 使用错误的 derivedStateOf 捕获旧快照，导致输入内容和发送动作不同步的问题。
- 调整消息地图的位置与安全区处理，使其可以覆盖输入栏区域并避让系统导航区。
- 调整首页问候语，使每日总结和近期记忆提醒可以同时展示，不再互相覆盖。
- 修复聊天列表长按菜单和文件夹菜单的内容边界与操作布局。
- 保持加号菜单的媒体选择、相机预览、知识库、Skill、委派和绘图等原有入口行为。

## 工程质量与验证

- 增加每日总结四时点调度、占位去重、日期归属和失败重试相关测试。
- 增加任务计划历史恢复、会话隔离和工具执行缓存恢复相关测试。
- 增加 UI 自动化权限等级和 Shell 路由相关测试。
- 完成 Debug 编译、单元测试、Release 编译、Release lint 和 CJK 基线检查。
- 完成 release 签名、versionCode、versionName、ABI 和 APK Signature Scheme v2 校验。

## 构建信息

- versionName：1.0.84
- versionCode：184
- 架构：arm64-v8a、armeabi-v7a、universal
- 构建类型：Release
- 签名：release keystore
- 构建命令：`./gradlew.bat :app:compileDebugKotlin :app:testDebugUnitTest :ai:testDebugUnitTest :memory:testDebugUnitTest :app:assembleRelease :app:lintVitalRelease -PversionName=1.0.84 -PversionCode=184 --no-daemon`

## 发布说明

- 本次版本使用本地 release keystore 签名。
- 当前项目 ABI 配置生成 arm64-v8a、armeabi-v7a 和 universal，没有单独的 armeabi APK。
- 本版本不创建 GitHub Release，仅推送合并后的 main 分支和 v1.0.84 tag。
