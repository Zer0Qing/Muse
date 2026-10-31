# 链路 9：性能与稳定性 — 去重基线

## 已确认修复/已知（不得再报）
- ANR 看门狗误报已修（AnrDetector ticket 计数 + 冻结 overshoot + 强引用 Handler + elapsedRealtime）；真 ANR 排查用系统 traces 交叉验证。
- AssistantAvatar OOM（按显示尺寸解码）已修；超长角色提示词/消息图片缓存等其他 OOM 可能性未被该修复覆盖——这条"未覆盖"可以探查但不算全新发现，找到具体新路径算新证据。
- 文档索引 CPU/磁盘操作移出主线程；44MB zip 导入闪退根因修复（HNSW 落盘节流）。
- 会话切换过期守卫（88405e1c）；压缩预热轮跳过。
- Compose：长列表重组成本提醒在 PROJECT_GUIDE §5；MuseDialog 内容区滚动陷阱已知。
- SQLiteConnection.close warning（全量测试时偶见，未证明与改动相关）——已知观察项。
- DataStore/Robolectric flake：BubbleSkinPluginSourceTest AccessDeniedException（已知待根治）；TransformerPipelineTest 已去 RobolectricTestRunner（根因 fixture 未定，需长期观察）。

## 本链路重点探查方向
1. **启动路径**：MuseApp.onCreate 现状（v2.5.x 新增的 seeder/ensureIndexed/A2A/诊断初始化是否拖慢冷启动）；Koin 模块图大小；首帧前的工作量。
2. **ChatViewModel 拆分后的性能影响**：S1-S5 提取是否引入了额外的状态拷贝/中间集合；工具循环热路径上的分配。
3. 生成链路热路径：GenerationTrace 埋点、shadow 事件、压缩水位线查询在每轮生成的开销叠加。
4. 长会话内存：消息树+任务卡+产物卡+图片的全量驻留；分页加载边界外的内存回收。
5. 主线程风险：新代码（A2A handler、Tasker receiver、诊断导出打包）的线程边界——BroadcastReceiver.onReceive 主线程、ContentResolver/文件 IO 位置。
6. 进程稳定性：全局单例（AutomationManager/ToolRegistry/MuseRuntime）的初始化失败传播；进程被杀时各 manager 的状态恢复。
7. WorkManager/Doze：定时任务、主动消息、每日总结在 Doze 下的行为（代码层面：setExactAndAllowWhileIdle 等 API 用法正确性）。
