# Muse v1.0.86

本版本聚焦 Android 生成生命周期与长期记忆链路的稳定性修复，保持现有功能和数据格式兼容。

## 重点更新

### 生成生命周期

- 修复生成输出已经完成后，活跃生成记录未清理，界面仍显示“生成中”的问题。
- 活跃生成任务改用稳定的 generationId 识别，心跳和通知标题更新不会破坏完成收口。
- 修复快速重生成或连续发送时，旧一代生成任务覆盖新一代 UI 状态的问题。
- 继续保留 Provider 正常完成后的连接收尾回调隔离，避免把正常结束误判为失败。
- 增加生成管理器回归测试，覆盖心跳更新、同会话替换和多会话并行状态。

### 长期记忆与事实库

- 统一默认助手记忆作用域为 `main`，避免 `default` 与 `main` 两套数据口径造成检索遗漏。
- `search_memory` 和系统提示中的相关记忆检索按 Assistant、scope、space 三层隔离。
- 修复相关记忆检索先取全库候选再过滤的问题，避免其他 Assistant 或 Space 的结果占满 limit。
- 缓存键加入 Assistant 记忆开关、作用域和记忆空间，切换 Assistant 或 Space 后不会复用错误的 system prompt。
- `useGlobalMemory` 关闭时不再注入用户画像、近期会话、置顶记忆、全局长期摘要和经验库。
- 保留 Assistant 专属事实检索能力，不影响当前助手自己的记忆使用。
- 缓存每个 Assistant 的 FactStore，减少重复初始化和 FTS 一致性检查。

### 自动保存

- 兼容 LLM 在 `mainProblem`、实体、关系等字段中显式返回 `null` 的情况。
- 过滤空事实、空更新、空关系和空合并，避免无效模型输出污染事实库。
- 增加 JSON null 容错回归测试。

## 构建信息

- versionName：1.0.86
- versionCode：186
- 构建类型：Release
- 架构：arm64-v8a、armeabi-v7a、universal
- 数据库版本：沿用现有数据库版本，无新增迁移
- 正式构建：`./gradlew.bat :app:assembleRelease -PversionName=1.0.86 -PversionCode=186`

## 验证信息

- `:app:testDebugUnitTest`：通过
- `:memory:testDebugUnitTest`：通过
- `:ai:testDebugUnitTest`：通过
- `:common:testDebugUnitTest`：通过
- `:app:assembleDebug`：通过
- 重点验证了生成生命周期、会话焦点恢复、事实作用域检索和自动保存 JSON 解析。

## 已知限制

- 尚未完成 Android 真机或模拟器验收，前后台切换、真实 Provider 长流和工具审批流程仍需设备验证。
- RAG Cloud embedding 的 HTTP 404 取决于用户配置的 Provider、endpoint 和模型，本版本未猜测性修改用户配置。
- 正式 Release 构建需要项目根目录的 `keystore.properties`，禁止回退到 debug 签名。
