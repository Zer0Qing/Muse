# Muse Web / PWA SaaS

Web 版本放在 `web/`，与现有 Android 模块隔离。目标是提供可安装 PWA、BYOK 优先的公共 SaaS，以及可选平台托管模型额度。

## 当前状态

当前已完成可构建的首个纵向切片：

- npm workspaces：`apps/client`、`apps/api`、`apps/admin`、`apps/worker`
- 共享契约、核心、Provider、数据库、记忆、RAG、文件、工具、MCP、Agent、任务和对象存储包
- Client 可操作 Provider 密钥库、Assistant 编辑、Memory Space/事实、RAG 文本索引、S3 直传和 Push 注册入口
- API 支持 PostgreSQL 持久化、Redis/Valkey 或 PostgreSQL 任务队列、S3-compatible presign 和文件完成登记
- Provider 支持 OpenAI-compatible、Anthropic、Gemini、Responses 的流式文本、工具调用增量和 usage 归一化
- Docker Compose 开发基础设施、staging 模板和 PWA manifest、Service Worker
- Android 专属能力边界和安全检查

业务功能按 `docs/IMPLEMENTATION_PLAN.md` 的阶段逐步实现。Android 专属的无障碍、Shizuku、Root、跨应用控制和系统通知能力不进入 Web 版本。

## Host Mode（手机主机运算）

Host Mode 让 Android Muse 作为唯一运行时：手机继续负责 Provider、会话、记忆、RAG、工具、审批和数据持久化，浏览器只负责渲染和发送交互命令。Android WebServer 提供经过 PIN/JWT 认证的 `/ws` WebSocket，React 客户端可通过 `?mode=host` 进入 Host 界面；跨设备开发时设置 `VITE_HOST_BASE=http://手机局域网IP:端口`。

Host Mode 当前首个闭环支持：状态快照、会话选择、新建会话、发送消息、流式消息状态展示和停止生成。它仍处于实验阶段，正式替换 Android 内嵌页面前需要真机验证 PIN Cookie、局域网连接、断线重连和后台生命周期。

## 本地开发

要求 Node.js 22+。

```bash
npm install
npm run typecheck
npm run test
```

需要数据库和缓存时：

```bash
docker compose -f infra/docker-compose.dev.yml up -d
```

## 费用模式

- BYOK：优先支持浏览器直连 Provider；需要服务端记忆、工具或安全代理时可选择服务端请求路径。
- Hosted：平台统一 Provider Key，使用服务端额度账本；后台可管理套餐、价格、功能和限额。
- 当前只实现人民币套餐、额度模型和模拟支付接口；生产支付宝凭据与云服务器部署不在本阶段自动执行。
- 文件存储使用 `S3_ENDPOINT`、`S3_BUCKET`、`S3_ACCESS_KEY`、`S3_SECRET_KEY`；凭据为占位值时明确退回本地开发 URL，不代表真实对象存储已联通。
- `REDIS_URL` 配置后 API 与 Worker 使用同一 Redis/Valkey 任务队列；未配置时按环境使用 PostgreSQL 或内存队列。
