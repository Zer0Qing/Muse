Muse v2.5.9 补丁版。

## 重要修复

- **备份恢复更完整**：修复知识文件夹在 NDJSON 备份恢复和恢复点回滚中的遗漏，减少恢复后知识数据缺失。
- **远程对话更安全**：A2A 远程请求改为隔离的文本对话路径，不再切换前台会话、修改输入框或调用本地设备工具；任务取消只影响自己的请求。
- **任务协议更可靠**：修复 A2A 任务查询/取消对 task id 的解析，并限制任务、会话和请求规模，避免异常请求污染任务状态。
- **Tasker 默认关闭**：外部自动化广播默认不响应；设置页新增显式开关，并保留长度、频率和高风险工具隔离保护。

## 体验改进

- 消息快捷操作支持按偏好配置，删除操作移动到快捷操作区并保留二次确认。
- 有背景图片时长文本保留气泡底色并改善正文对比度，提升阅读可见性。
- 补齐本轮新增设置文案的中文、英文、西班牙文、日文、韩文、巴西葡萄牙文和俄文资源。

---

# Muse v2.5.9 (English)

Muse v2.5.9 is a patch release.

## Important Fixes

- **More complete backup recovery**: fixes knowledge folders being omitted from NDJSON backup restore and recovery-point rollback, reducing missing knowledge data after recovery.
- **Safer remote conversations**: A2A requests now use an isolated text-only path and no longer switch the foreground session, mutate the input box, or invoke local device tools; cancellation affects only the caller's own request.
- **More reliable task protocol**: fixes A2A task lookup/cancellation id handling and bounds task, session, and request growth so malformed requests cannot pollute task state.
- **Tasker opt-in by default**: external automation broadcasts are ignored until enabled in Settings; prompt length, frequency, and high-risk tool isolation protections remain in place.

## Experience Improvements

- Message quick actions can be configured; delete moved into the quick-action row while retaining confirmation.
- Long messages keep a readable bubble background over image backgrounds, with improved text contrast.
- Added Chinese, English, Spanish, Japanese, Korean, Brazilian Portuguese, and Russian resources for the new settings.
