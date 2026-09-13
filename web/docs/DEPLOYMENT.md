# Web 部署说明

当前只提供开发和 staging 模板，不执行真实云服务器部署。

## 本地

```bash
npm install
npm run typecheck
npm run test
npm run build
# 依赖服务
docker compose -f infra/docker-compose.dev.yml up -d
```

## 云服务器 staging 目标

- Linux + Docker Compose
- HTTPS 由 Caddy/Nginx 终止
- client、api、admin、worker 分离容器
- PostgreSQL 和 Valkey 私网访问
- S3 兼容对象存储，API 只签发短时 PUT/GET URL，文件元数据写入 PostgreSQL
- `REDIS_URL` 必须同时提供给 API 和 Worker，确保它们共享同一任务队列
- 独立 staging 环境变量、Provider Key、`SECRETS_MASTER_KEY` 和 VAPID Key
- 数据库迁移先 dry-run，再执行；API/Worker 启动时也会以 advisory lock 安全补齐迁移
- 每日备份和恢复演练
- 不绑定生产域名，不使用生产支付凭据

## 生产前禁止事项

未配置正式域名、邮件、VAPID、KMS/等价密钥托管、S3 生命周期、支付宝商户密钥、备份恢复和监控前，不得宣称生产可用，不得打开真实收款。

## 容器构建注意

API 镜像需要构建 `@muse/storage` 与 `@muse/jobs`；Worker 镜像需要带上 `@muse/database`、`@muse/jobs` 及其 workspace 产物。当前环境若没有 Docker 命令，只能完成静态构建检查，不能把镜像或 staging 启动说成已验证。
