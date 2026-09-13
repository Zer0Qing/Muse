# Muse Web / PWA SaaS 实施计划

## 已确认决策

- 工程位置：当前仓库 `web/`
- 技术：TypeScript + React + Node.js
- 部署：云服务器；暂不绑定生产域名
- 货币：人民币
- 支付：支付宝；先做后台调价、额度和模拟支付，再接真实商户凭据
- 模型：BYOK 优先，可选平台托管模型
- BYOK：优先浏览器直连；需要记忆、工具、安全策略时允许服务端代理
- 团队：首发不做团队工作区
- Provider：首批覆盖现有 Android 端可迁移的全部 Provider 类型
- Android 专属能力：无障碍、Shizuku、Root、跨应用控制、系统通知、悬浮窗等不进入 Web

## 阶段顺序

1. 工程骨架、PWA、契约、开发基础设施
2. 认证、个人租户、权限和审计
3. Provider Gateway、SSE、BYOK、托管模型
4. 会话、消息、Assistant 和响应式聊天 UI
5. 四层记忆、RAG、导入导出
6. ToolRegistry、Skill、MCP 和审批编排
7. Agent、群聊和异步任务
8. 文件、文档、OCR、图片/视频、TTS/ASR
9. 额度账本、人民币套餐、调价后台、模拟支付、支付宝适配层
10. 安全、合规、浏览器兼容、PWA 推送
11. CI、集成/E2E、Docker、staging 和回滚

每个阶段都必须有：代码、测试、迁移、文档、验收记录；没有真实云凭据时只完成部署模板和 staging 可运行验证。
