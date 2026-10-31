# 链路 2：安全与权限 — 去重基线

## 已确认修复/设计决策（不得再报）
- webhook 默认关闭；飞书 Verification Token 缺失 fail-closed；QQ 签名缺失 fail-closed；飞书加密 webhook 明确返回不支持（不是漏洞，是已知限制）。
- TRUSTED 模式下十个 ui_* 工具要求审批；无障碍转储过滤密码节点文本；加密云备份不静默降级明文（老版明文备份可导入属兼容性已知）。
- OAuth 各流程日志脱敏（只记 HTTP 状态与脱敏 error 字段）。
- 渠道工具风险真源/白名单 fail-closed（P1 #17 已修）。
- 插件沙盒放开（fetch/XHR/WebSocket 网络直连、桥接动作全放行、能力名只校验格式）是 v2.5.8 产品决策——放开本身不算发现；页面导航仍禁、file:// 与 content:// 读取仍隔离。放开**实现里**的越权/绕过才算。
- manifest 签名字段显式白名单（新增字段默认不参与签名）是产品决策；但白名单**实现**若可被操纵签名校验结果算发现。
- 诊断导出不记录消息正文与 API key、sessionId 哈希脱敏——验证实现是否达标可以，缺日志不算。
- STRICT 模式 ALWAYS_ALLOW 穿透是声明过的设计取舍（显式用户策略 > 会话模式）。
- 虚拟屏 Binder handoff token、render_data 大数据拒绝/截断、Root 包列表/失败详情截断、未知 Provider 工具链 30 条截断——均已修。

## 本链路重点探查方向
1. **v2.5.8 新攻击面**：A2A HTTP 端点（/.well-known/agent-card.json、/a2a JSON-RPC）——认证/鉴权？SendMessage 是否可触发付费模型调用（成本滥用）？GetTask/CancelTask 能否越权读他人任务？与主机模式（嵌入式 Web 服务器）的端口/绑定关系。
2. Tasker 广播入口：任何应用都可发广播吗？extra prompt 长度/内容校验？能否被用来注入提示词越权（诱导 Agent 泄漏数据到任务结果通知）？
3. 插件 Hook onPromptFinalize：事件对象里有什么？恶意插件能否借此把用户消息/记忆外发（插件网络已放开——hook 是否成为"合法外泄通道"）？桥接动作全放行后，插件可触达哪些桥接动作（读剪贴板/通知/文件？）。
4. 插件市场签名校验白名单化后：存量包兼容逻辑有没有引入"新字段注入且参与信任"的口子。
5. MCP stdio server 配置（node/npm/npx 限制）是否仍成立；mcp_mgmt_configure 是否可被工具循环间接滥用。
6. 诊断导出包内容核对：实际打包了哪些文件？确认无正文/密钥；sessionId 哈希是否用了盐。
7. update 链路：UpdateNotifier/latest.json 解析对恶意 latest.json 的容错（官网被篡改场景）。
