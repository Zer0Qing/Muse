# 构建 / CI 门禁 / 发布工程 审查报告（2026-10-10）

## 摘要

审查范围：`ci/`（run_ci_checks.ps1、29 个 script/*.py、13 个 test/*.py、baseline/、detekt_debt_cap.json）、
根与 8 个模块的 build.gradle.kts、settings.gradle.kts、gradle.properties、detekt.yml、
发布链（resolve_release_version / validate_release_signing / validate_release_apks /
generate_release_manifest / validate_release_manifest / publish_release）、
`.github/workflows/ci.yml` 与 `web-ci.yml`、`.gitattributes` / `.editorconfig`、
以及 v2.5.8 发布材料与 tools/market-signer。

方法：通读全部脚本与构建文件；对每条候选发现用实际运行验证（本机 Python 3.13 + 仓库工作区，
只读操作，未改动任何仓库文件；仅临时探针脚本写入系统 Temp 后清理）。

总体结论：**门禁体系当前在 HEAD 上是红的** —— lanes lane 三个"无条件运行"的快速检查之一
（hardcoded_cjk）与 strict locale 检查均实测 FAIL；tag↔Gradle 默认版本号同步机制在
v2.5.6/2.5.7/2.5.8 三个发行版上已被本地构建流程实质性绕过；detekt 债务上限未按自家
"同步下调"政策跟随 10-08/10-09 的基线重生成；行尾策略仍只锁 `*.sh`，624 个入库文件
工作区为 CRLF（含 6 个 CI 基线与 2 个 JSON 基线）。DEDUPE 基线中"五条 lane 全绿记录在案
（以最近批次为准）"的说法已过时。另核实多项历史疑点为**无问题**（见"已知项核对"）。

发现统计：**12 条 —— P0 = 0，P1 = 3，P2 = 9**。

---

## 发现清单

### F1. hardcoded_cjk 门禁在 HEAD 上实测 FAIL（P1，可信度：高）

- 位置：
  - `ci/baseline/hardcoded_cjk_baseline.txt:19`（`app/src/main/java/io/zer0/muse/ui/SettingsScreen.kt: 68`，基线最后更新于 8b587cd4 / 2026-09-29）
  - `ci/run_ci_checks.ps1:89-95`（该脚本属 lanes lane **无条件运行**的三项快速检查之一）
  - 违规源码：`app/src/main/java/io/zer0/muse/ui/chat/ChatToolLoopRunner.kt:399,576,931-932,975-976`（6 处，新文件，基线外）；`app/src/main/java/io/zer0/muse/ui/chat/ChatToolResumeCoordinator.kt:77,82`（2 处，新文件）；`app/src/main/java/io/zer0/muse/ui/SettingsScreen.kt`（69 处 > 基线 68，d60aed71 新增诊断导出入口 `listOf("诊断", "导出", "日志", ...)` 搜索串）
- 证据（实测运行 `python ci/script/check_hardcoded_cjk.py`，exit=1）：

  ```
  [hardcoded-cjk] 扫描 ui/ 目录: 508 处硬编码中文字面量,分布 57 个文件
  [hardcoded-cjk] FAILED — 新增硬编码中文字面量:
    app/.../ui/SettingsScreen.kt: 69 处 (baseline 68,新增 1)
    app/.../ui/chat/ChatToolLoopRunner.kt: 6 处 (baseline 0,新增 6)
    app/.../ui/chat/ChatToolResumeCoordinator.kt: 2 处 (baseline 0,新增 2)
  ```

  三个违规文件 `git status` 干净（即 HEAD 提交态即红）。S1-S5 拆分提交（dfe3bbe3、15341441）
  各自声明"ktlint+detekt 全绿、全量单测 exit=0"，但未跑/未声明脚本门禁；d60aed71 同理。
- 影响：lanes lane 的 "Lane checks" 步骤（`ci.yml:97-100`，无 `run_full` 条件、无条件执行）
  在当前 HEAD 上对**任何** PR / 手动运行都会失败；DEDUPE 基线"五条 lane 全绿"记录失效。
  10-09~10-10 的三批提交把基线甩在了身后，门禁从"拦截新债"退化为"常红噪音"。
- 建议：清理 9 处新增字面量（ChatToolLoopRunner 的错误文案走 stringResource；SettingsScreen
  搜索提示串若为存量豁免需补基线并说明），随后 `--update-baseline` 收紧（MemorySpaceSwitcher
  等 4 文件已改善可一并落账）；并把"脚本门禁全绿"恢复为发版前置验证项。

### F2. strict locale 门禁在 HEAD 上实测 FAIL：values-en 缺 1 键（P1，可信度：高）

- 位置：
  - `app/src/main/res/values/strings_chat_core.xml:377`（`<string name="chat_token_estimated_cost">历史成本约 $%1$s（按当前模型定价粗估）</string>`）
  - `ci/script/check_localizations.py:140-142`（strict locale 缺失计入 total_missing → exit 1）
  - `ci/run_ci_checks.ps1:96`（scope 路由：localization 变更时必跑）
- 证据（实测运行，exit=1）：

  ```
  values-en: 5901 keys [MISSING 1 keys (FAIL)]
    MISSING: chat_token_estimated_cost
  values-es/ja/ko/pt-rBR/ru: 5784 keys [MISSING 118 keys (WARN)]
  ```

  引入提交：2bd5bebb（2026-10-08"优化计划五阶段落地"）只在 `values/` 追加该键，
  未同步 `values-en/`（`git show 2bd5bebb --name-only` 中 res 变更仅此一个文件）。
  `app/lint-baseline.xml` 无 MissingTranslation 条目可兜底（grep 无命中），`error.add("MissingTranslation")`
  的 lint 门禁理论上也会在下次 lintDebug 红。
- 影响：任何触及 localization 作用域的 PR / 全量本地运行都会失败；en（默认严格语言）的
  "翻译完整"承诺被打破；后续翻译管线（translate_locales 以 values-en 为源）会把这条
  中文占位符扩散到全部语言。
- 建议：补 `values-en/strings_chat_core.xml` 的 `chat_token_estimated_cost` 英文条目
  （顺带核对 es/ja/ko/pt-rBR/ru 缺口从台账 101 → 118 的增量是否需要补录）。

### F3. tag↔Gradle 默认版本号同步机制被绕过：v2.5.6/v2.5.8 tag 无法通过自家发布预检（P1，可信度：高）

- 位置：
  - `app/build.gradle.kts:140-148`（默认值自 f7dc0dd2 / v2.5.1 后冻结在 `?: 251` / `?: "2.5.1"`）
  - `ci/script/resolve_release_version.py:57-61`（`tag versionName does not match Gradle default` 即 FAIL）
  - `.github/workflows/ci.yml:255-257`（release job 第一步即跑该预检）
  - `app/build.gradle.kts:466`（守卫注释"当前默认线为 242/2.4.2"——本身已过期两代）
- 证据（对 tag 逐一回放 resolve_release_version，实测）：

  ```
  v2.5.1: PASS -> 2.5.1/251
  v2.5.6: FAIL -> tag versionName 2.5.6 does not match Gradle default 2.5.1
  v2.5.8: FAIL -> tag versionName 2.5.8 does not match Gradle default 2.5.1
  ```

  且 v2.5.6/v2.5.8 tag 树内的 `releases/*/manifest.json`（versionCode 256/258）与 tag 内
  Gradle 默认 251/2.5.1 不一致，证明发布走的是本地构建
  （`releases/v2.5.8/release_verification.txt:4`：`gradlew :app:assembleRelease -PversionName=2.5.8 -PversionCode=258`）。
- 影响：设计上 tag 是版本唯一真源、预检防"tag 与 Gradle 默认漂移"，但连续 3 个发行版
  （2.5.6/2.5.7/2.5.8）未同步默认值、未走 tag 管线。任何人对现有 tag 手动 dispatch release
  job 都会在第一步 FAIL；反之若有人为过预检去"补默认值"，该门禁就沦为事后补录的摆设。
  版本号真源实际已从 Gradle 默认值漂移到 release_verification.txt 手工记录。
- 建议：恢复流程——发版提交必须同步 `versionCode/versionName` 默认值（可加一个 CI 脚本
  断言 `releases/vX/manifest.json.versionCode == Gradle 默认`）；至少先修正 ：466 的过期注释。

### F4. check_ktlint_report 的模块清单漏掉 accessibility-provider（P2，可信度：高）

- 位置：`ci/script/check_ktlint_report.py:22`（`MODULES = ["app", "common", "memory", "ai", "material3", "accessibility"]`）
- 证据：`accessibility-provider/build.gradle.kts:6` 应用了 `alias(libs.plugins.ktlint)`，
  根 `build.gradle.kts:68-118` 的 subprojects 块会为它注册 `ktlintKotlinSourceCheck`，
  且 `accessibility-provider/ktlint-baseline.xml` 已入库（glob 验证存在）——它是
  **第 7 个真实跑该门禁的模块**，但报告断言脚本不覆盖它。
- 影响：该模块的 ktlint 报告若退化为空跑/缺失（P4-1 修的"假绿"问题复发），P4-1 断言
  不会报警；static lane 的"报告非空且记录真实文件数"承诺对该模块是盲区。
- 建议：MODULES 追加 `accessibility-provider`（virtual-display-* 未接 ktlint 插件，无需纳入）。

### F5. detekt 债务上限未按"同步下调"政策跟随 10-08/10-09 基线重生成（P2，可信度：高）

- 位置：`ci/detekt_debt_cap.json:2-9`（total 3497 / app 3085；note 最后一条为 2026-10-07 批次）
- 证据：
  - 实测 `python ci/script/check_detekt_debt.py`：`detekt 债务实测: 3465 条 (app 3053/ai 217/memory 174/common 12/accessibility 9)` —— 门禁 PASS；
  - `git log -1 -- ci/detekt_debt_cap.json` = 4d032c50（10-07），而 `app/detekt-baseline.xml`
    在 daf627b1（10-08，3057 条）与 776fec47（10-09，3053 条）两次重生成，均未动 cap。
- 影响：cap 文件自己的政策写明"按批次清理后手动下调本文件(DOWN)"。现状留下
  `3497 - 3465 = 32` 条无账目净增空间——债务可以悄悄回升 32 条而门禁全绿，
  与"禁止只上调不清理"的对称约束（下调后不跟随实测下调）不符。
- 建议：把 cap 同步到当前实测（total 3465 / app 3053），并在 note 里补一条 10-10 台账。

### F6. check_horizontal_inset 是唯一"扫描目标缺失即 PASS"的门禁（P2，可信度：高）

- 位置：`ci/script/check_horizontal_inset.py:59-62`

  ```python
  root = Path(args.root)
  if not root.is_dir():
      print(f"[horizontal-inset] 目录不存在: {root}")
      return 0
  ```

- 证据（实测）：在仓库父目录运行 `python ci/script/check_horizontal_inset.py` →
  `目录不存在: app\src\main\java\io\zer0\muse\ui` → **exit=0**；再传任意不存在目录 → 同样 exit=0。
  对照组：check_localizations 在 res 目录缺失时 `sys.exit(2)`；其余门禁经 `baseline_utils.require_baseline`
  fail-closed（test_baseline_fail_closed.py 专门守护）。
- 影响：默认 `--root` 是 CWD 相对路径（`ci/run_ci_checks.ps1:13` 的 `Set-Location $root` 保证了
  lane 内安全），但任何脱离该前提的调用（手动、别的 runner、future 重构挪动 cwd）都会得到
  静默 PASS——门禁输入缺失时应当失败而不是通过，这是与本仓库 fail-closed 约定相悖的孤例。
- 建议：目录缺失时 return 2（或改用 `PROJECT_ROOT = Path(__file__).parents[2]` 的绝对根，与其余脚本一致）。

### F7. 行尾策略只锁 `*.sh`：624 个入库文件工作区 CRLF、19 个 mixed，CI 基线在列（P2，可信度：高）

- 位置：`.gitattributes:1-3`（仅 `*.sh text eol=lf`）；`.editorconfig:12`（`end_of_line = lf`，只约束编辑器/ktlint，不约束 git）
- 证据（`git ls-files --eol`，core.autocrlf=false）：
  - 2669 个入库文件中 **624 个工作区 w/crlf**（app 494 / material3 48 / memory 31 / ai 25 / ci 10 ...），其中含 `app/src/main/java/io/zer0/muse/ui/ChatViewModel.kt` 等索引内本就 i/crlf 的文件；
  - **19 个 w/mixed**，含门禁脚本 `ci/script/check_localizations.py`（实测 180 行 CRLF + 21 行 bare LF）；
  - CI 基线文件 6 个 `ci/baseline/*.txt` 与 `ci/component_convergence_baseline.json`、`ci/font_scale_clipping_baseline.json`、`ci/translation_residue_baseline.json` 均为 i/lf + w/crlf；
  - 机制复现：`write_baseline`（check_hardcoded_cjk.py:157、check_row_text_width.py:257 等）用
    `path.write_text(...)`，Windows 上默认 newline 翻译把 `\n` 写成 CRLF → Windows 侧
    `--update-baseline` 产出的基线与索引内 LF 版本整文件 diff；`git ls-files --eol` 下
    detekt-baseline.xml 已出现 `Missing newline` 类警告（check_repo_hygiene 实测 1128 条 warning）。
    另实测：对工作区 CRLF 的 check_repo_hygiene.py 做一次 touch 即产生 `167/167` 行的幻影 diff。
- 影响：这就是"历史上 CRLF 噪音反复"的根因未除——基线/脚本在 Windows 上重生成即全文件
  翻转行尾，污染 diff 审查、放大 PR 体积；mixed 文件会随不同工具的保存行为继续漂移。
- 建议：`.gitattributes` 增加 `* text=auto eol=lf`（或至少 `*.py`/`*.kts`/`*.kt`/`*.json`/
  `ci/baseline/*` 显式 `text eol=lf`），配合一次性 `git add --renormalize .` 收敛存量；
  Python 写基线处统一 `open(..., newline="\n")`（generate_release_manifest.py:52 已是正确示范）。

### F8. ci.yml 的 android-test 注释引用不存在的 nightly cron（P2，可信度：高）

- 位置：`.github/workflows/ci.yml:155-157`（"P4-3: ……现在 schedule(与上方 nightly cron 同频)也触发 emulator smoke"，job 条件 `if: github.event_name == 'schedule' || ...`）对照 `ci.yml:13-16`（`on:` 仅 `workflow_dispatch`，v2.1.0 起自动触发全关）
- 证据：`on:` 无 `schedule`，`github.event_name == 'schedule'` 永假；`reactivecircus/android-emulator-runner`
  的 instrumented 测试实际只剩手动 dispatch 一个入口，注释里的"nightly"从未在当前触发器下发生。
- 影响：文档漂移会让维护者误以为 emulator smoke 有夜间回归覆盖（HANDOFF 已知"缺设备验收"
  之外的又一层误解）；新增的渠道/无障碍 instrumentation 若指望 nightly 兜底则落空。
- 建议：改注释为"仅手动 dispatch"，或真加回 `schedule`（与 v2.1.0 决策冲突则维持注释修正即可）。

### F9. ci-scripts lane 单测覆盖差集：horizontal_inset / repo_hygiene / release_body_bilingual 无任何测试（P2，可信度：高）

- 位置：`ci/run_ci_checks.ps1:43-58`（13 个测试文件清单）对照 `git ls-files ci/script/*.py`（29 个脚本）
- 证据：有测试的脚本 13 个（pr_check / engineering_discipline / hardcoded_cjk / release 四件套
  经 test_release_preflight / ktlint_report / detekt_debt / baseline_fail_closed(5 个 UI 门禁) /
  localizations / migration_coverage / component_convergence / translation_residue /
  font_scale_clipping / row_text_width）。**零测试**的门禁级脚本：
  `check_horizontal_inset.py`（见 F6，恰恰是最需要回归保护的 fail-open 孤例）、
  `check_repo_hygiene.py`、`check_release_body_bilingual.py`（v2.3.0 起的发布规范校验，
  v2.5.8 材料 ad18f74c 声称"双语校验 PASS"但该脚本无回归测试）；
  工具类 dedup_strings / sync_missing_keys / translate_locales / show_detekt 亦无（可接受）。
- 影响：三个无测试门禁的口径回归（正则改动、路径改动）不会有任何测试报警；F6 的问题
  若将来被"顺手修复"也无从验证。
- 建议：为 horizontal_inset（含目录缺失必须失败的新口径）与 release_body_bilingual 补最小单测，
  纳入 ci-scripts lane 清单。

### F10. 两个本地化脚本的 locale 目录判定口径不一致，`vi` 类新语言会被 check_localizations 静默跳过（P2，可信度：高[机制] / 低[当前影响]）

- 位置：`ci/script/check_localizations.py:86`（`if suffix.startswith("v") or suffix in (...)`）对照 `ci/script/check_translation_residue.py:53`（`LOCALE_DIR_RE = re.compile(r"^values-([a-z]{2,3}(?:-r[A-Z]{2})?)$")`）
- 证据（实测模拟）：`values-vi` / `values-vi-rVN` 的 suffix `vi` 命中 `startswith("v")` 被
  check_localizations 排除，而 translation_residue 的正则会匹配并扫描它；同一目录两把尺子。
  顺带：排除清单里的 `"hdpiv"`、`"wdpiv"` 是无效限定符拼错的死条目。当前仓库
  （values/en/es/ja/ko/pt-rBR/ru/v31，`values-v31` 仅有 themes.xml 无 strings）不受影响。
- 影响：未来新增越南语等 v 开头 locale 时，strict 完整性检查对其永久失明，而 CJK 残留检查
  却会扫它——新增语言包的缺键永远不会被 CI 拦截。
- 建议：find_locale_dirs 改用与 translation_residue 相同的正则（`v\d+` 显式排除），
  顺手删掉 hdpiv/wdpiv 死条目。

### F11. CI 上传的单测报告目录缺 common/accessibility/material3（P2，可信度：高）

- 位置：`.github/workflows/ci.yml:113-118`（upload-artifact path 仅 `app|memory|ai` 的 `build/reports/tests`）对照 `ci/run_ci_checks.ps1:135-143`（unit lane 实跑 6 个模块，含 `:common:/:accessibility:/:material3:testDebugUnitTest`，P4-4 特意补齐了 material3）
- 证据：静态对照即可；material3 于 P4-4 纳入单测 lane 时未同步 artifact 清单。
- 影响：三个模块的测试失败在 CI 上无法下载报告定位（本地复现才行），对 95% 覆盖率门禁的
  material3 尤其不便。
- 建议：path 列表补 `common/build/reports/tests`、`accessibility/build/reports/tests`、`material3/build/reports/tests`。

### F12. sign.py↔App 签名算法"权威判据"测试在 CI 中永远跳过（P2，可信度：高）

- 位置：`app/src/test/java/io/zer0/muse/data/plugin/market/MarketSigningFixtureTest.kt:46`（`assumeTrue("未找到签名产物，跳过（需先运行 tools/market-signer/sign.py）", keysFile.isFile && catalogFile.isFile)`）；fixture 根 `app/build/market-signing/out`（build/ 被 .gitignore:5 忽略，CI 永不产出）
- 证据：静态对照；v2.5.8 签名白名单改造（PluginSecurityGate.SIGNED_TOP_LEVEL_FIELDS，
  `PluginSecurityGate.kt:403-408`）的跨端一致性只有三条防线：SignedManifestFieldCoverageTest
  （App 内字段集自洽，会真跑）、MarketSamplePackageTest（入库样本，会真跑）、
  MarketSigningFixtureTest（脚本产物验收，**CI 中 assumeTrue 跳过**）。
  本次审查实测 `sign.py` MANIFEST_ORDER 字段集与 SIGNED_TOP_LEVEL_FIELDS **完全一致**
  （20/20，双向差集为空），当前无漂移——但该一致性在 CI 上无人持续守护。
- 影响：未来 sign.py 与 App 序列化口径再漂移（v2.0.0 市场事故同型）时，CI 不会红，
  只能靠发布前人工跑 sign.py 才能发现。
- 建议：在 CI 增加（或 release job 内加）一步用 sign.py 打一个固定样本包再跑该测试，
  把 assumeTrue 的跳过条件变成 CI 产物存在。

---

## 已知项核对（本轮不重复上报）

1. **detekt 1.23.8 签名偏移崩溃绕法 / Kotlin 增量假报错 retryFailedStep** —— 工具链已知项，未作为发现（F5 只报 cap 台账不同步，不涉及崩溃绕法）。
2. **detekt 结构性债务（LargeClass/CyclomaticComplexMethod/LongParameterList/NestedBlockDepth，台账 3497）** —— 不报"债务存在"；实测 3465 条在限内（见 F5）。
3. **lint baseline 1 条未命中旧记录、lint 0 error 22 warning** —— 存量已知，未复现新证据。
4. **C3 两个 UI 扫描器探测能力不足** —— F1 是基线漂移（新文件超出基线被正确拦截），非扫描器探测能力问题，二者不重复。
5. **五个非英语语言包缺翻译（台账 101 个）** —— 不作为新发现；仅顺带记录新证据：es/ja/ko/pt-rBR/ru 实测各缺 118 键（较台账 +17，属同一已知项演进）；F2 报的是 strict 语言 values-en 缺键，是另一问题。
6. **远端 CI 自动触发已关（workflow_dispatch only）是决策** —— F3 不质疑该决策，报的是该决策下 tag 预检与实际发版流程的脱节；F8 只报注释与触发器不一致。
7. **分发 GitHub Releases 直链、无 CDN** —— 未涉及。
8. **P1 #40-42/46/47（keystore 双离线备份、证书到期探针、一条命令发布）** —— 未重复上报；F3 是版本号同步问题，与该组不重叠。
9. **迁移覆盖门禁历史误报（version = CONST 写法）已修** —— 复核确认：实测 `check_migration_coverage.py` PASS，三个库均正确解析（MuseDb v110 字面量 `MuseDb.kt:174`；FactDb v15 经 `FACT_DB_VERSION` 常量回溯 `FactDb.kt:53,64`；MemoryDb v5 经 `MEMORY_DB_VERSION` 常量回溯 `MemoryDb.kt:8,31`，注册解析含 MemoryModule.kt:43-48）。
10. **门禁缺失/无效基线时 fail-closed（B1/C2 已修）** —— 复核 test_baseline_fail_closed 覆盖 5 个 UI 门禁全部 PASS；F6 是该约定下的唯一漏网孤例（horizontal_inset 无 baseline 概念，属路径缺失而非基线缺失，故不算推翻"已修"）。
11. **v2.5.8 签名白名单适配** —— 复核为**已完成、无漂移**：sign.py 与 App 白名单字段集双向一致；generate/validate_release_manifest 的 apkArtifacts 校验、validate_release_apks 证书指纹钉扎、publish_release 幂等续发与远端摘要核对均与 v2.5.8 材料兼容（test_release_preflight 全 PASS 实测）。

## 实测通过项快照（本轮运行记录）

- PASS：check_migration_coverage（三库）、check_detekt_debt（3465/3497）、check_component_convergence（14/14）、
  check_translation_residue（全 0）、check_font_scale_clipping（0/0）、check_row_text_width（0）、
  check_touch_target（0）、check_hardcoded_font_size（0）、check_icon_content_description（0）、
  check_design_tokens（0 裸色）、check_repo_hygiene（0 error/1129 warning）、
  check_engineering_discipline（0 error/441 warning）、check_release_body_bilingual（v2.5.8 PASS）。
- FAIL：check_hardcoded_cjk（F1）、check_localizations（F2）。
- ci-scripts lane 13 个测试文件全部 PASS 实测。
