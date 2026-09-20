# Muse v1.0.88 发布完成

## ✅ 发布状态

| 项目 | 状态 |
|------|------|
| **Git Tag** | ✅ v1.0.88 → 00bdc20 |
| **GitHub Release** | ✅ https://github.com/Zer0Qing/Muse/releases/tag/v1.0.88 |
| **APK上传** | ✅ 3个APK全部上传 |
| **Manifest** | ✅ 已上传 |
| **Verification** | ✅ 已上传 |

---

## 📦 发布资产

| 文件 | 大小 | SHA-256 |
|------|------|---------|
| Muse_v1.0.88_arm64-v8a.apk | 64.4 MB | `0d37fa65...` |
| Muse_v1.0.88_armeabi-v7a.apk | 53.1 MB | `ae844ad0...` |
| Muse_v1.0.88_universal.apk | 169.8 MB | `01301453...` |
| manifest.json | 946 B | `886f6b46...` |
| release_verification.txt | 1.5 KB | `864601f4...` |

---

## 📋 版本信息

- **versionName**: 1.0.88
- **versionCode**: 188
- **DB Version**: 97
- **minSdk**: 26
- **targetSdk**: 35

---

## 🔗 相关链接

- **GitHub Release**: https://github.com/Zer0Qing/Muse/releases/tag/v1.0.88
- **官网下载**: https://museai.ltd/download/

---

## 📝 主要更新

### 安全加固
- PiiGuard 统一为 common PiiEngine
- SSRF 防护加固（DNS rebinding复核）
- RootExecutor 参数白名单
- JsSandbox 校验加强
- BackupCrypto 加固
- ToolRouteExecutionGuard

### 功能完善
- 记忆系统UI置顶并入system prompt注入
- 跨会话消息转发
- 长按加号菜单收展优化
- /pin /reset命令支持
- 多API Key管理UI
- skillpkg模板和JS桥接
- 子代理任务取消
- 每日总结时段自定义
- Moment功能入口

### 数据库
- DB v97: assistants表新增enabled列

---

*发布于: 2026-09-14*
