# DSH 任务书：Muse 审查修复计划推进

> 本文件是 dsh 执行器的完整任务书。读完严格按此执行，遇报错自行诊断继续，不停下等人。
> 每完成一步把进度追加到本目录 `DSH_PROGRESS.md`。

## 角色

你是 Muse Android 项目的修复执行器，推进 `.audit/REPORT_MASTER.md` 里的审查修复计划。
先读 REPORT_MASTER.md 和相关 `.audit/REPORT_*.md` 分报告，再按下面清单执行。

## 已修——不要重做（git log 已核对）

1. 数据 F1/F2 知识库文件夹备份（commit `764823a2`）
2. A2A 隔离 headless（`92c2d1fa`，含协议语义）
3. Tasker 开关+限频（`54c8224f`）
4. 官网凭据（`3d2cdedc`）
5. CI F2 locale（values-en 已有 `chat_token_estimated_cost`）
6. CI F1 cjk 闸门（`df740a6d`，多行 Logger 字符串 + OAuth 硬编码转资源，闸门已 PASS）
7. AI F1 supersede 游标（`787ca9cf`，含回归测试）

## 当前进度：从 X1（vdserver）开始

## 后续批次（按序，跳过已修）

- **AI F1**: supersede 续传时序（ChatToolLoopRunner.kt:869-871 只重置 lastUiUpdateChars，lastPersistChars/lastNotifChars 滞留，新内容比旧短时节流失效）
- **X1**: vdserver waitFor() 无超时（ServerService.java:361/377）+ 全锁
- **UI F2/F3**: ChatToolResumeCoordinator:194/235 丢更新改 CAS；refreshDisplayedSessionFromDatabase:4601 接 SessionSwitchGuard
- **RAG F1**: BuiltInKnowledgeDocSeeder.ensureIndexed 补 embeddingModel 指纹比对（108-113）
- **RAG F3**: guess 维度污染 HNSW 指纹
- **渠道 F2**: 五接收器永久死亡无自愈（TelegramReceiver.kt:58、ChannelStore.load:27-29，加重试/WorkManager 兜底）
- **数据**: B-34 对账只数行数不验入库（BackupService.kt:1007-1013）
- **CI F3/F5/F7**: tag↔Gradle 版本同步、detekt cap 台账、.gitattributes 锁 Kotlin 行尾
- **NOTICE** 补 v2.5.x 新依赖

## 纪律（铁律）

1. `.audit/` 里只允许写 `DSH_PROGRESS.md`，其余只读；`releases/` 不动
2. 批次结束跑全量门禁 `.\gradlew.bat :app:ktlintKotlinSourceCheck :app:detekt :app:testDebugUnitTest`（pwsh 7，重定向取 `$LASTEXITCODE`）
3. baseline 行号敏感：按需重建+diff 确认无新增豁免；新违规优先修代码，固有结构 `@Suppress` 按点豁免
4. 每修一项独立 git commit（`fix(scope)` + AUD 编号），精确 `git add`，禁止 `add -A`
5. 修 bug 不重构；UI 文案同步 7 语言 strings*.xml（values/values-en/es/ja/ko/pt-rBR/ru）
6. 不打包、不发布、不推远端、不打 tag
7. git/gradle 需要越权时直接申请，宿主自动放行

## 完成标准

全部批次完成后：跑全量门禁+单测确认绿，输出总结（修了什么/验证结果/遗留清单）。
