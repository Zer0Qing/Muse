# 链路 6：Agent 设备控制 / 自动化 / 运行时 — 去重基线

## 已确认修复/设计决策（不得再报）
- 三层通道 AutomationManager 分层降级；execTiered 是设备命令唯一入口（Shizuku 优先 Root 兜底）；Shizuku 运行时失败后 Root 兜底已修。
- DeviceCapabilityProfile 唯一事实来源 + DeviceCapabilityHook 注入档案+规程（priority 80）；子代理不注入；探测失败退化不抛异常。
- 应用控制面 AppControlCore：包名白名单防注入、系统应用禁清数据/卸载（强停允许）、禁对 Muse 自身破坏、名称四级匹配；应用层不需要授权。
- 虚拟屏：服务重启自愈（兼容 display 记录尺寸失败重建新 ID）；virtual_close 对已消失 display 幂等成功；最多 3 并发 display；语义读屏 uiautomator dump --display-id 失败不回退主屏。
- virtual_screen_input / virtual_tap_text：DeviceCommandPolicy 构造校验、最多 5 次滚动查找、点击后验证、失败置未知副作用阻断重放、读屏不持久化。
- 无障碍截图：Android 14+ takeScreenshot 接入 AccessibilityExecutor.screenshot；13 及以下降级口径。
- screen_tap_text 结构化 ToolOutcome（attempts/matched/verified/matchedLabel）；tap_text/tap_id 语义匹配+评分+坐标兜底+结果未知禁重放。
- automation_workflow：node_script 步骤（HIGH 风险审批）、结果 Keystore 加密断点、同 run_id 追加、未知副作用阻断。
- 内置 Node 运行时：jniLibs 分发 + nativeLibraryDir exec（绕 W^X）；RUNTIME_DATA_VERSION 控制重解压；exec 垫片 LD_PRELOAD；npm assets 解压；strip 会破坏 patchelf 段布局（keepDebugSymbols 已列）。
- MCP stdio：命令仅允许 node/npm/npx 与运行时内绝对路径。
- platform_selfcheck 通道级自检（HEAD 最新提交，只读安全 STANDARD）。

## 已知未解决/边界（可引用补充新证据）
- 无 Shizuku 且无 Root 时不能调 Shell/Root 专属系统 API（能力边界，设计如此）。
- auto 策略是"有目标包名优先专属虚拟屏，失败降前台"的简单确定性策略，未按任务类型/隐私细分（已知产品边界）。
- workflow 逐次模型调用间续接，不支持单脚本任意条件分支（已知设计）。
- Node 脚本是 app UID 全能力执行器（HIGH 风险审批保护，设计如此）；不受 DeviceCommandPolicy 约束（设计如此，专用动作替代）。
- 真机矩阵验收缺失（已知）。

## 本链路重点探查方向
1. **A2A/Tasker 新入口与设备工具的交集**：A2A 调用是否也走工具审批？远程触发设备控制（tap/清数据）的审批链路是否完整——远程无人在屏幕前时审批卡如何处理？
2. automation_workflow node_script 步骤的输入校验：脚本内容长度/资源限制/超时；连续 node 步骤的中间结果累积是否无界。
3. virtual display lease 管理：并发 Agent + 手动 virtual_screen 工具 + A2A 触发的并发场景，display 计数/泄漏。
4. MuseRuntime 环境变量注入（HOME/TMPDIR/PATH/LD_LIBRARY_PATH/LD_PRELOAD/MUSE_*）在多进程并发 exec 时的竞态/泄漏；npm 解压中断后的状态机。
5. DeviceCapabilityProfile 探测的时效性：权限被用户运行中撤销（如关闭无障碍）后，档案刷新延迟窗口内模型是否还按旧档案行动。
6. exec 垫片/patchelf 产物完整性：构建后 md5 核对是否有 CI 护栏，还是只有口头约定。
