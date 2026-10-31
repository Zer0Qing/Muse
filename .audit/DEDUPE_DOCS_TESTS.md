# 链路 10：文档 / 开源健康度 / 测试覆盖 — 去重基线

## 已确认修复/已知（不得再报）
- 交接文档已同步 v2.5.8（本轮 3a00d72）；文档纪律（README 无 emoji、事实对代码）已在案。
- 五个非英语语言包各缺 101 翻译（已知）；7 语言新增流程已在案。
- P1 #43"同名测试缺失不是覆盖率证据"（方法论已明确）；#44 远端 CI 手动触发是策略。
- SECURITY.md / docs 跟踪策略需仓库所有权决策（已知外部条件）。
- 用户手册 19 章、双语 README、release_body 双语规范已建立。
- 旧评审报告误报清单（git 历史 技术评审报告_全维度.md §7）已核对过——里面的教训（勿轻信"MuseSpinner 死代码"类结论）必须遵守。

## 本链路重点探查方向
1. **文档与 v2.5.8 代码的漂移**：README/README_EN/用户手册/软件功能.md 对 A2A/Tasker/插件 Hook/诊断导出/自检工具的覆盖；CONTRIBUTING.md 与实际贡献流程一致性。
2. **用户手册的新功能缺口**：v2.5.0 知识库文件夹、输入栏重设计、v2.5.8 外部协议——用户视角文档是否教得会。
3. **测试覆盖结构性缺口**（不是"缺同名测试"，而是"缺关键行为测试"）：
   - A2A handler 的 JSON-RPC 协议测试（错误码/畸形请求）；
   - TaskerTriggerReceiver 的 intent 解析边界；
   - 插件 Hook onPromptFinalize 的异常矩阵；
   - GenerationTrace/DiagnosticExporter 的内容红线测试（断言不含正文/密钥）；
   - 搜索 API 13 家供应商的解析测试覆盖。
4. 根目录杂物：compile*.log/baseline_compile.log 等日志文件是否该进 .gitignore（已被 git 跟踪吗）。
5. NOTICE/LICENSE 第三方声明完整性（v2.5.x 引入的新依赖——Node 运行时、A2A、搜索 API SDK）。
6. 官网 museai.ltd（`E:\1Project\Muse\museai.ltd`）与 v2.5.8 的同步：下载页 latest.json 链路对 v2.5.6/2.5.8 的展示；文档与新版本功能一致性。
