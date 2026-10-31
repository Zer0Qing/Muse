# X 系列横切发现（主审查 Agent 自审，2026-10-10）

范围：两个无链路代理覆盖的模块 —— `accessibility`/`accessibility-provider`（双 APK 无障碍桥）与
`virtual-display-server`（vdserver 虚拟屏服务端）。

---

## X1. vdserver 全部 Binder 调用串在一把锁上，且 shell 调用无超时（P1，可信度：高）

- 位置：`virtual-display-server/src/main/java/io/zer0/muse/vdserver/ServerService.java:356-367`（`execShellCapture`）、
  `:372-397`（`resolveLauncherComponent`）、`:49/:68/:124/:158/:171`（全部 `synchronized` 入口）
- 证据：
  - `ensureDisplay/createDisplay/launchApp/destroyDisplay/requestScreenshot` 五个 Binder 入口全部 `synchronized`
    （同一 monitor，Binder 线程池被串行化）。
  - `launchApp` → `execShellCapture` → `Runtime.exec("sh -c am start ...")` → `process.waitFor()` **无超时**；
    `resolveLauncherComponent` 的 `cmd package resolve-activity` 同样无超时 `waitFor()`（:361/:377）。
  - `requestScreenshot` 在锁内 `Thread.sleep(20ms)` 自旋最长 1s（:182-187）。
- 影响：任一 `am start`/`cmd package resolve-activity` 挂起（厂商 ROM 卡顿、系统繁忙）→
  全部虚拟屏操作（含其他 display 的截图/销毁）排队阻塞；恢复手段只有 15s 空闲看门狗 `System.exit(0)`
  ——而空闲判定也被挂起的调用 `touch()` 反复刷新，可能永不退出。
- 建议：`waitFor(timeout)` + 进程 destroy 兜底；或把 shell 调用移出锁、按 display 分锁。

## X2. ui_screenshot 默认路径与 Provider 跨 UID 写入冲突（P1，可信度：高）

- 位置：`app/src/main/java/io/zer0/muse/tools/defaultTool/UIToolsSkill.kt:206-220`（默认 `context.cacheDir`）、
  `app/src/main/java/io/zer0/muse/tools/system/AccessibilityClient.kt:218-219/:234-253`（AIDL 双路路由）、
  `accessibility/src/main/java/io/zer0/muse/accessibility/MuseAccessibilityService.kt:367-390`（`FileOutputStream(path)`）。
- 证据：
  - `ui_screenshot` 不传 path 时用**主应用** `cacheDir` 生成路径；`AccessibilityClient.withProvider` 优先走
    独立 Provider 的 AIDL `takeScreenshot(path)`。
  - Provider APK `io.zer0.muse.a11y` 是独立 UID（无 `sharedUserId`），对主应用 `cacheDir` 无写权限
    → `saveScreenshot` 的 `FileOutputStream` 抛异常 → 返回 false。
  - 工具失败提示只有"截图能力不可用/需 Android 14+ 或服务异常"，**不包含跨 UID 权限语义**，
    用户/模型无法从提示定位真因。
  - `path` 参数是模型可控的任意绝对路径（ToolDef :127 "可选,保存路径(绝对路径)"），Provider 进程将直接
    `FileOutputStream(path)` 写入——独立 Provider 侧没有任何路径白名单/校验（对比 vdserver 的
    `packageName.matches(...)` 防注入惯例）。HIGH 风险审批缓解外部滥用，但通过审批后的写入范围不受限。
- 影响：装了 Provider APK 的用户，ui_screenshot 走 Provider 优先路由 → 默认路径必失败；
  失败提示误导排障方向。
- 建议：路径由主应用写入到双方都可写的目录（或经 AIDL 传回字节而非路径）；
  Provider 侧对 path 做目录白名单（如仅允许各自 cacheDir）；失败提示区分"权限失败"。

## X3. AccessibilityExecutor 截图能力未接入 Provider 双路（P2，可信度：高）

- 位置：`app/src/main/java/io/zer0/muse/automation/executors/AccessibilityExecutor.kt:40-64`。
- 证据：`screenshot()` 只读 `MuseAccessibilityService.instance`（应用内静态实例）；
  而 `DeviceCapabilityProfile` 的 `accessibilityScreenshot` 能力标记与三层通道截图均基于此。
  装了独立 Provider 且只有该授权的用户：`isAvailable()`（同只查 instance）与 `screenshot()` 都判不可用，
  能力档案会把"实际上有无障碍+截图"报成不可用。
- 注：`AccessibilityClient`（tools 层 ui_* 工具）有双路，但 automation 三层通道没有 ——
  同一设备上两套客户端能力口径不一致。
- 建议：`AccessibilityExecutor` 复用 `AccessibilityClient` 的双路路由，或能力档案探测补 Provider 通道。

## 其他核对（非发现）

- Provider APK 分发链完整：`app/build.gradle.kts:38-71` CopyProviderApkTask 把
  `accessibility-provider` 构建产物嵌入主应用 `assets/a11y/`，`AccessibilityProviderInstaller`
  负责安装 —— 无"用户拿不到 Provider APK"问题。
- 桥接权限链正确：signature 级 `io.zer0.muse.permission.A11Y_BRIDGE`，主应用 uses-permission 声明一致。
- `A11yBridgeService` 空值降级与基类语义一致；`mCanTakeScreenshots` 反射失败有标记与降级路径。
