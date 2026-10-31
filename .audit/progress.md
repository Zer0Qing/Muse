# SDD ledger — plan: E:\1Project\Muse\1muse\.audit\REPORT_MASTER.md

Plan source: REPORT_MASTER.md「建议的行动批次」1-5（用户指示全量推进）。
Execution: inline（executing-plans），工作在 1muse main 直接进行（用户在总报告后明确要求
"让我们全量推进这份计划"，视为对 main 工作的明确同意；每任务一提交，可回滚）。
Baseline: HEAD 24bf247f（v2.5.8 + platform_selfcheck），1muse 工作树干净。

Pre-flight: 批次内任务接口无共享冲突——
- 止血 A/B（CI 门禁）与 C/D（BackupService）互不相交；
- 安全 A（ChatToolApprovalCoordinator/ChatUiState）不触碰 BackupService/Tasker；
- 安全 B（TaskerTriggerReceiver + manifest）独立文件；
- A2A 专项（批次3）依赖安全 A 的判定位置，顺序执行天然满足。
Ruling（工作区）: 不用 git worktree —— 主仓库工作树干净、用户此前接手会话均直接在 main 推进，
且本计划任务彼此独立、单线执行；成本：出问题可 git revert 单提交。

Task 1: 止血 A — values-en 补 chat_token_estimated_cost
Task 2: 止血 B — hardcoded_cjk 基线刷新（只降不升门禁的基线重建流程）
Task 1: complete (commit fe691617, tests: python ci/script/check_localizations.py → exit 1→0, strict en 0 缺失)
Task 2: complete (commit fe691617, tests: python ci/script/check_hardcoded_cjk.py → exit 1→0 PASS 508 处与基线一致;
  Ruling: 基线重建是门禁脚本自己的 --update-baseline 流程（script 输出明确指引），非"抬基线做绿"——
  新增两文件是 S1-S5 拆分产物、SettingsScreen +1 是诊断导出批次，均为已推送提交的存量，清零属后续批次;
  成本：508 处存量债继续记账，真实清理后须再降)
Task 3: complete (commit 764823a2, tests: BackupNdJsonFolderRestoreTest 3/3 → backup 包 54 用例 0 失败;
  RED 两断言均先失败[分发缺分支/缺 flush]，GREEN 后全绿)
Task 4: complete (同 commit 764823a2; RED buildBackup 断言失败→GREEN 传参 knowledgeFolders = snap.knowledgeFolders;
  Ruling: 测试用"源码契约测试"模式(仓库既有 BackupNdJsonRestoreContractTest/BackupEntityCoverageTest 同型),
  断言源码含传参——因 buildBackup 是 private suspend 且依赖 Room 全套,单元内不可直接调用;成本:源码重排会误报,
  届时改定位锚点即可)
Task 5: complete (commit a35df745, tests: RemoteApprovalModeTest 5/5 + ToolPermissionResolverTest 17/17
  + ToolApprovalCardTest 2/2 全绿; :app:compileDebugKotlin 通过。
  Ruling: 判定抽成纯函数 ApprovalModeResolver 单独文件(而非内联在协调器)——协调器方法为 suspend
  且依赖 bridge,不可直接单测;成本:多一个 internal 文件,可接受。
  Ruling: persistedModeProvider 用 suspend lambda —— 编译期发现非挂起 lambda 无法调用
  sessionPermissionStore.getMode()(它内部 first() 是挂起),已改签名。
  Ruling: 只收窄放行位作用域,不改 A2A 端到端架构 —— 会话劫持/并发串台根治属批次 3(A2A 专项),
  本提交只消除"无人值守静默执行 HIGH 设备工具"这一最高危后果)
Task 6: complete (commit 51ac9006, tests: TaskerTriggerPolicyTest 7/7 + RemoteApprovalModeTest 5/5;
  :app:compileDebugKotlin 通过; check_hardcoded_cjk 仍 PASS。
  Ruling: 不改 exported 语义(Tasker 跨应用广播的必要前提),只补成本防线;
  UI 开关入口(设置页)属后续收尾 —— 成本:当前用户无法在 UI 打开开关,功能默认关闭。
  Ruling: 限频状态放 companion 而非持久化 —— 进程重启即重置,但配合默认关闭的开关不构成绕过;
  成本:极端场景跨进程重启可多触发一次)
Task 7: complete (museai.ltd commit 305a03b, tests: git grep 凭据 0 命中 / git check-ignore 命中 /
  report.py ast.parse OK + --help 可运行 / 参数化片段 dry-run 通过。
  Ruling: 只做"移出跟踪"，不在本提交重写 git 历史 —— 历史里仍有凭据，彻底消除需 filter-repo 或
  按已泄露处理，属不可逆且需用户决策的操作（executing-plans 的四个停止条件之一）；
  成本：历史提交仍含服务器地址与密钥路径，若仓库将来加远端需先处理。
  Ruling: 保留 DEPLOY.md 可执行性 —— 用 $DeployUser/$DeployHost/$DeployKey/$DeployRoot 变量 + 本地
  .deploy.local.ps1（gitignored）；成本：部署前多一步 dot-source，已在文档开头写明。)
Task 8: A2A 专项 core complete (commit 92c2d1fa; A2A headless generator + Agent-only context sessions + bounded LRU context cleanup + task registry/job cancellation + JSON-RPC params.id/INVALID_REQUEST/PARSE_ERROR semantics + sendMutex + prompt cap 16000;
  tests: A2aHeadlessGenerator 2/2, A2aHandlerIsolation 5/5, A2aContextRegistry 3/3,
  A2aTaskRegistry 6/6, A2aJsonRpcSemantics 9/9, RemoteApprovalMode 5/5; compileDebugKotlin PASS.
  Ruling: A2A is intentionally text-only/headless in this batch (tools=null, no ChatViewModel). This is the safe
  interpretation of REPORT_MASTER “参照 Tasker 单轮模型”; reopening remote device tools requires a new design/approval
  and must not reconnect A2A to UI ChatViewModel. Existing v2.5.8 historical release notes still describe the old
  shared tool path and need a documentation correction in the docs/ledger batch.
  Ruling: JSON-RPC compatibility keeps envelope-id fallback for legacy GetTask/CancelTask callers while preferring params.id.
  Ruling: unknown CancelTask returns TASK_NOT_FOUND and is not inserted into the task registry (prevents task-book poisoning).

Task 9: Tasker 设置页 UI 开关完成 (commit 54c8224f; user-approved bounded design):
  - AutomationSettingsPage 增加默认关闭的 Tasker/外部自动化 Switch；NavGraph 注入 SettingsRepository；
  - 复用 taskerTriggerEnabledFlow/saveTaskerTriggerEnabled；文案明确“外部文本补全可能消耗额度、不会授予工具权限”；
  - 7 locales: zh/en/es/ja/ko/pt-BR/ru isolated resource files；AutomationSettingsPageTest 3/3；Tasker policy suite PASS；
  - localization gate PASS；ktlint repository gate仍有既存 SettingsModels/SettingsRepository/ChatSettings/ChatScreen/MessageBubble 等存量红项，
    Tasker/A2A 文件本身无 ktlint findings。

暂停点（用户要求发新包）：本轮修复范围已完成，暂停推进批次 4-5，不继续改可靠性/CAS/vdserver/RAG/台账，等待用户发布补丁包。
待恢复：批次 4（channel self-healing、AI supersede cursor、vdserver timeout、CAS/session guard）；批次 5（detekt/license/devdocs/CRLF/RAG）；
历史凭据清理/SSH rotation仍需用户显式批准，不在本轮执行。

Task 10: 发布前本地预检完成 (candidate v2.5.9 / versionCode 259):
  - clean serial full Gradle preflight first exposed three existing regression contracts / one environment-sensitive test;
    MessageBubbleActionMenuWiringTest and MessageBubbleModelMetaWiringTest were stale relative to already committed quick-action
    behavior (5fbc40ad/aac53073) and were updated in commit 5137215f; OpenAIProviderAbortTest passed when isolated.
  - :app:testDebugUnitTest --no-parallel PASS (2238 tests, 4 skipped); :ai:testDebugUnitTest abort regression PASS;
    test + :app:testDebugUnitTest + :app:assembleDebug serial PASS; :app:assembleRelease -PversionName=2.5.9 -PversionCode=259 PASS.
  - Candidate artifacts materialized locally under releases/v2.5.9 (APK files remain gitignored by policy): universal/arm64/armeabi-v7a/x86_64;
    aapt package identity 2.5.9/259; apksigner v2 verification PASS with one signer; SHA-256 and sizes recorded in manifest.json.
  - Release body bilingual gate, localization gate, CJK gate PASS. Full repository ktlint still has known unrelated baseline debt.
  - No push/tag/upload/publication/credential rotation performed. Release notes/manifest/verification files are local candidate deliverables;
    user must perform publication and real-device acceptance before final release.

Task 11: v2.6.18 published (2026-11-01) — scope is everything since tag v2.6.10:
  - Functional work sat uncommitted (722 paths); the 11 commits between v2.6.10 and the release were docs-only.
    Landed as three commits: 797df89f (features, fixes, tests), 36f46304 (release materials + web host client sources),
    040ab3fa (stale doc cleanup + market signer tweak).
  - Gates before packaging: :app 3572 / :memory 335 / :ai 485 / :miniphone 309 = 4701 tests, 0 failures; :app:detekt 0 issues;
    release lint no errors/warnings; release body bilingual gate PASS.
  - Artifacts: versionName 2.6.18 / versionCode 281, four ABIs (universal, arm64-v8a, armeabi-v7a, x86_64);
    apksigner verify PASS; sizes and SHA-256 recorded in releases/v2.6.18/manifest.json and release_verification.txt.
  - Publication was performed by the agent, per explicit user instruction. This supersedes the Task 10 note that publication
    is the user's step, for this release only.
  - Network: github.com TLS was blocked on this host (schannel and openssl backends both failed; api.github.com and
    uploads.github.com were reachable). An API-based push script was written and abandoned at the rate limit.
    After the user switched proxy node, plain push worked:
    `git -c http.proxy=http://127.0.0.1:7897 push origin main` -> d2c73195..040ab3fa; tag v2.6.18 = 78a4c6e0.
  - Release created with ci/script/publish_release.py (HTTPS_PROXY set): four assets uploaded on the first attempt and
    remote names/sizes/SHA-256 verified. URL: https://github.com/Zer0Qing/Muse/releases/tag/v2.6.18
  - Release body deliberately omits the mini phone and the web UI surfaces: both are hidden in this build
    (settings entry `browseVisible = false`, home entry gated by `FeatureFlags.MINI_PHONE_ENTRY`), still searchable and
    still functional. `manifest.json` drops the `webShell` field for the same reason.
  - Open items: four stale `.zip` files under releases/ remain untracked; the APK carries ~59 unused hashed web assets from
    repeated builds, so cleaning the build dir before the next release is worth doing;
    queued product work: proactive watchdog criteria, thread/event model, acceptance surface.

