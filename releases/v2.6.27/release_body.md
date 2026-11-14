# Muse 2.6.27

## 本次改动

### 记忆系统重构

- 摘要写入改为 revision fence（CAS），只覆盖会话生成代一致的最新摘要，避免并发/恢复覆盖丢内容
- Facts 改成 staged projection，确认后再发布；同 scope 下旧投影可回收，减少重复处理和孤儿
- 新增 memory runtime 路由（scope key、source fingerprint、revision token），提示词按当前会话/空间选择确定性投影
- 新增加载 dream 状态机、经验存储、记忆写入门；备份恢复导入期间阻止 deep memory 写
- 记忆页置顶/删除/固定操作统一走 use case，行为更可测

### 联网搜索

- 查询整串传给供应商，保留多词、中文词序和 `site:` 过滤，不再只取首个词
- 增加 `python android`、`android python`、`天气 成都`、`site:github.com python` 请求级回归
- 供应商设置改为「列表 → 详情」：Key 池、掩码删除、连接测试、保存和默认端点集中在详情页
- 普通供应商使用内置端点；只有 SearXNG 与自定义 API 要求填写覆盖地址，并兼容旧的 `自定义 API` 配置键

### 选择文本：改成整屏选择页

点「更多 → 选择文本」以前只是把气泡切到可选状态并弹一句「长按文字即可划选复制」，
用户还得在狭窄气泡里再长按一次、再拖选择手柄，长文本根本选不准。

现在直接打开一整屏纯文本：

- 正文铺满，长按即出系统菜单（复制 / 全选 / 搜索）
- 划选与菜单交给平台，不自绘，不会和手势打架
- 左上角 X 关闭

### 小手机：入口全部收起

- 首页「更多」菜单、设置分类页、设置搜索、导航路由、后台唤醒全部停
- 网页版设置页也一并摘除该分类（之前是列出来但点不动）
- 代码未删，后续改回开关即可恢复

### 文件夹会话计数：不再出现 -1

往文件夹里加一个会话再删掉，计数会变成 -1。原因是这个计数是冗余字段，
只在「移动会话」时维护，删除会话的路径没人递减。

- 删除会话现在会在同一个事务里递减
- 启动时按真实行数校准一次存量脏数据（幂等，正常情况零写入）

### 记忆：时间轴不再把旧内容堆到最近

**症状**：已经处理过的记忆不在原来的时间位置，而是反复出现在最近两天；
同时感觉系统在不停重新处理所有记忆。

**原因**：判断「这条摘要属于哪一天」用的是 `updated_at`（这一行最后被写入的时间）。
但滚动摘要是覆盖式重写——每聊一句都会把它刷成当下。于是几个月前的一段对话，
只要今天又被重新摘要过一次，就会被算成「今天的记忆」。

**修复**：改用摘要里记录的**真实对话时间跨度**（`source_time_range`）。
`updated_at` 只作为老数据的回退。

另外修了收尾摘要被跳过的问题：进程重启后轮次计数归零，退出会话时不会跑收尾，
下次靠阈值触发就把整段对话重新摘要一遍。现在改为按「历史里是否有用户消息」判定。

## 安装

- `Muse_v2.6.27_arm64-v8a.apk` — 绝大多数手机（推荐）
- `Muse_v2.6.27_armeabi-v7a.apk` — 老旧 32 位设备
- `Muse_v2.6.27_universal.apk` — 不确定架构时用这个（体积最大）

## 需要重点验证

0. **升级覆盖安装**：从最近正式版覆盖安装，确认应用可启动、数据库迁移无报错
1. **记忆功能回归**：新建会话聊几轮，确认记忆页能稳定显示、删除/置顶不丢内容
2. **备份恢复**：导出备份再导入，确认记忆写入门没有在恢复期间产生脏数据
1. **文件夹计数**：新建文件夹 → 加一个会话 → 删掉该会话，看计数是否还是 -1
2. **记忆时间轴**：看旧记忆是否还在最近两天的位置
   - 老摘要没有真实时间范围，仍会走 `updated_at` 回退，这部分需要重算才能归位
3. **选择文本**：长按拖选是否顺手，系统菜单弹出位置是否正常
5. **小手机**：首页更多菜单、设置页、搜索里应完全看不到
6. **联网搜索**：分别测试四条多词查询，确认结果不再只按首词召回；点进任一供应商详情页，保存 Key 后执行连接测试

## 已知限制

- 记忆时间轴的历史数据归位需要一次重算（会触发较多模型调用），本次未执行
- 模拟器与真机存在差异，性能与手势手感以真机为准

---

# Muse v2.6.27 (English)

## Changes

### Memory system refactor

- Summary writes now use a revision fence (CAS), so a stale session generation cannot overwrite a newer summary during concurrent work or recovery.
- Facts use staged projections and publish only after validation; old projections in the same scope can be reclaimed to reduce duplicate processing and orphaned rows.
- Added a memory runtime route with scope keys, source fingerprints, and revision tokens; prompts select a deterministic projection for the current session and space.
- Added dream-state, experience storage, and a memory write gate; deep-memory writes are blocked while backup restore is importing data.
- Pinned, delete, and memory-page actions now go through use cases so their behavior is directly testable.

### Web search

- The full query string now reaches providers, preserving multiple terms, Chinese term order, and `site:` filters instead of keeping only the first term.
- Added request-level regressions for two reordered English queries, a reordered Chinese weather query, and `site:github.com python`.
- Provider settings now use a list-to-detail flow with key pools, masked deletion, connection tests, saving, and managed default endpoints.
- Built-in providers use their managed endpoints; only SearXNG and Custom API accept an endpoint override, with the legacy localized custom-API key normalized to `Custom API`.

### Full-screen text selection

「More → Select text」 now opens a full-screen plain-text page. Long-press uses the platform selection menu for copy, select all, and search, and the top-left close action returns to the conversation.

### Mini phone entries hidden

Mini phone entries are hidden from the home menu, settings categories, settings search, navigation routes, background wakeups, and the web settings mirror. The implementation remains in place and can be restored by changing the feature flag.

### Folder conversation counts

Deleting a conversation now decrements the folder count in the same transaction. Startup also reconciles existing counts against the real row count, so the value no longer drifts to `-1`.

### Memory timeline stability

The timeline now uses the summary's actual conversation time range instead of the last write time, with the latter retained only as a fallback for legacy summaries. Session-close summaries are also decided from whether the history contains a user message, so a process restart cannot silently skip the final summary.

## Installation

- `Muse_v2.6.27_arm64-v8a.apk` — recommended for most phones
- `Muse_v2.6.27_armeabi-v7a.apk` — older 32-bit devices
- `Muse_v2.6.27_universal.apk` — use when the device ABI is unknown

## Verify after installing

1. Upgrade over the latest release and confirm that the app starts without migration errors.
2. Create a conversation, exercise memory, and check that list, delete, and pin actions retain their content.
3. Export and import a backup, checking that the memory write gate stays closed during restore.
4. Create a folder, add one conversation, delete it, and confirm the count remains zero.
5. Check that older memories keep their original timeline position.
6. Long-press and drag text selection to confirm the platform menu and handles remain usable.
7. Confirm that mini phone entries are absent from the home menu, settings, and search.
8. Run the four web-search queries above and verify that the provider detail page saves and tests a key without asking for a managed provider URL.

## Known limitations

- Legacy summaries without a real time range need a later recomputation to be fully relocated on the timeline.
- Emulator behavior does not replace physical-device acceptance for migration, restore interruption, performance, or gesture feel.
