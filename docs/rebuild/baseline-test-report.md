# Muse 重构基线测试报告

- 日期：2026-08-20
- 初始 HEAD：`6cd909d`
- 基线提交：`a9c0c0b`
- 分支：`codex/overnight-rebuild-2026-08-20`
- 源码版本：`1.0.78 / 178`
- 数据库：MuseDb `92`，FactDb `13`
- 工作树中已确认的 3 个记忆图文案改动已单独提交，未覆盖用户修改。

## 基线命令

```powershell
.\gradlew.bat :app:compileDebugKotlin --no-daemon
.\gradlew.bat :app:testDebugUnitTest :memory:testDebugUnitTest :ai:testDebugUnitTest --no-daemon
```

## 结果

- `:app:compileDebugKotlin`：通过
- `:app:testDebugUnitTest`：790，通过
- `:memory:testDebugUnitTest`：144，通过
- `:ai:testDebugUnitTest`：215，通过
- 核心合计：1149，通过
- 扩展基线（common 30、accessibility 3）：总计 1182，通过

本报告不包含任何 keystore、密码、API key 或签名配置内容。
