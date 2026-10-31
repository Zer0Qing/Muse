# 链路 1：AI Provider / 流式 / 工具循环 — 去重基线

## 本链路已确认修复（不得再报）
- OpenAI stream-guard 回退失败发 Error 而非 Done-only；FirstEventWatchdog AtomicBoolean + 空流/超时竞态只触发一次 fallback；Done/pendingBuilder 收尾竞态（终态内容变更延迟到 pending flush 后）；callback generation guard（OpenAI/Anthropic/Responses）；`OpenAIProviderAbortTest`（503 retry backoff 后 abort 只发 1 次请求）已过。
- 工具循环思考等级：所有有效轮次保留用户配置（ToolRoundPresentationPolicy）；系统提示思考语言跟随界面语言（thinkingInstruction）；不支持 reasoning 的 provider 不伪造内容。
- 断线续传：resumeOverlapToDrop() 只裁剪可证明完整重放（ChatResumeLcpTest）；"首字被吞"未复现，已加 debug 诊断，等真实日志——"未复现"不是发现，但你可以提出新的丢字代码路径。
- 能力目录 toolUse 缺省=未声明按支持保留；catalog image/reasoning 三态；目录输出上限 SUSPICIOUS 标记。
- debugInfo 在 completed/failed/cancelled/context-overflow 统一出口。

## 已知未解决（可引用编号补充新证据，不算新发现）
- Provider 未闭环清单：Gemini usage-only terminal、OpenAI Responses final text/retry 判断、Anthropic/Gemini stream abort listener（**注意：Gemini/Anthropic 的 abort listener 曾列为缺口，若你发现已实现，标注"已闭环"**）、旧 EventSource retry race 待真实网络验证。
- P1 #1/#3-6（预算、压缩计量、SSE、prompt cache、能力真源）、#20-31 中与 Provider 相关项。

## 本链路重点探查方向
1. 2026-10 拆分重构后的新代码：ChatViewModel 提取出的 coordinator/transformer（S1 工具循环体、S2 压缩编排、S4 工具审批、S5 待恢复工具调用）——提取时是否引入状态丢失/双重触发/竞态。
2. A2A 入口（web/a2a/）与 Tasker 入口（intent/）复用生成链路时：并发、取消传播、generation guard 是否覆盖新入口。
3. 插件 onPromptFinalize hook 注入生成链路：fail-open 声明是否在所有异常路径成立；hook 拿到的事件里是否泄漏消息正文之外仍敏感的内容。
4. 诊断 GenerationTrace 新埋点：是否在生成链路热路径上引入分配/锁；trace 数据结构在超长会话下是否无界增长。
5. 子代理 timeout_ms 30s~900s 参数解析与续接语义；超时附进展信息的生成路径。
