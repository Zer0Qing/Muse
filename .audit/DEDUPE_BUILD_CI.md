# 链路 8：构建 / CI 门禁 / 发布工程 — 去重基线

## 已确认修复/决策（不得再报）
- 五条 lane（ci-scripts/lanes/static/unit/debug）本地全绿记录在案（以最近批次为准）。
- 中文控制台 UnicodeEncodeError 已修（PYTHONIOENCODING=utf-8 + 脚本 stdout 重配置）。
- 根级 assembleRelease 的 vdserver lintVitalRelease 已修（模块级豁免两条规则）。
- 远端 CI 自动触发已关（workflow_dispatch only）是决策。
- 11 项脚本门禁 2026-09-29 全部清零（真实修复）；门禁缺失/无效基线时 fail-closed（B1/C2 已修）。
- detekt 1.23.8 签名偏移崩溃的绕法已记录（先删字段→detektBaseline→再删类型）。
- Kotlin 增量编译假报错 retryFailedStep（重跑恢复，CI 复发先 clean）——已知工具链问题。
- lint baseline 1 条未命中旧记录（已知存量）；lint 0 error 22 warning 在基线内。
- strict locale 门禁正常拦截新缺口（曾拦下 values-en 缺 10 键）。
- 分发：GitHub Releases 直链（v2.4.2 起），无 CDN；dl.museai.ltd 停用不需续期。

## 已知未解决（可引用编号补充新证据）
- C3：两个 UI 扫描器探测能力不足，修正后会显露大量存量——不能抬基线，需人工复核（已知卡点）。
- P1 #40-42/46/47 发布自动化与灾备（keystore 双离线备份、证书到期探针、一条命令发布）——核实哪些已做。
- P1 #43-45、#47-48 部分待修。

## 本链路重点探查方向
1. **ci/script/*.py 与当前代码库的漂移**：基线文件（ktlint/detekt/lint/component_convergence/row_text_width）与实际源码是否还匹配；脚本里硬编码的路径/模块名/阈值是否随 v2.5.x 演进失效（如新增 accessibility-provider/virtual-display-* 是否纳入检查范围）。
2. check_migration_coverage 对 FactDb v15 / MemoryDb v5 的解析正确性（曾因"version = CONST 写法"误报——核实三种 DB 都能解析）。
3. 发布脚本链（validate_release_apks/generate_release_manifest/publish_release）对 v2.5.8 材料（manifest 白名单签名变化）的适配。
4. CI 脚本自身测试（ci-scripts lane 的 test/）覆盖了哪些脚本、漏了哪些新增脚本。
5. Gradle 配置漂移：build.gradle.kts 注释里的版本史是否漏记 v2.5.x；版本号注入校验对 VERSION_CODE 环境变量路径的测试。
6. .gitattributes/.editorconfig 行尾策略与仓库实际文件状态的一致性（历史上 CRLF 噪音反复出现——查 .gitattributes 是否真的锁定 LF、有没有漏掉的文件类型）。
