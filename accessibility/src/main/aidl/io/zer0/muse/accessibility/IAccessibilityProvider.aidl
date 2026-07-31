// P3-3: IAccessibilityProvider — UI 自动化 AIDL 接口。
//
// 服务端: io.zer0.muse.accessibility.MuseAccessibilityService (运行于无障碍服务进程)
// 客户端: io.zer0.muse.tools.system.AccessibilityClient (运行于主进程,通过 bindService 绑定)
//
// 设计原则:
//  - 所有方法同步返回(无 oneway),保证调用方拿到确定结果
//  - String 返回值约定: 成功返回 JSON/可读文本; 失败返回 "[error] ..." 形式
//  - 坐标参数均为屏幕绝对像素坐标
//  - nodeId 为 getUiHierarchy/findFocusedNodeId 返回的路径标识(如 "0.1.2")
package io.zer0.muse.accessibility;

interface IAccessibilityProvider {

    // 获取当前活动窗口的 UI 层级(紧凑文本格式,每行一个节点)。
    // 包含每个节点的: path / class / text / content-desc / bounds / clickable / focusable。
    String getUiHierarchy();

    // 在指定坐标执行点击手势。
    boolean performClick(int x, int y);

    // 在指定坐标执行长按手势(按住 500ms)。
    boolean performLongPress(int x, int y);

    // 执行全局无障碍动作(如 GLOBAL_ACTION_BACK / HOME / RECENTS / NOTIFICATIONS)。
    boolean performGlobalAction(int actionId);

    // 执行滑动手势,从 (startX,startY) 到 (endX,endY),duration 毫秒。
    boolean performSwipe(int startX, int startY, int endX, int endY, long duration);

    // 返回当前输入焦点节点的路径标识(供 setTextOnNode 使用);无焦点返回空串。
    String findFocusedNodeId();

    // 在指定节点上设置文本(通过 ACTION_SET_TEXT)。
    // nodeId 为 getUiHierarchy/findFocusedNodeId 返回的路径。
    boolean setTextOnNode(String nodeId, String text);

    // 截图并保存到指定路径(需 API 34+,低版本返回 false)。
    // format: "PNG" 或 "JPEG"。
    boolean takeScreenshot(String path, String format);

    // 无障碍服务是否已启用并连接(服务端已 onServiceConnected 即返回 true)。
    boolean isAccessibilityServiceEnabled();

    // 获取当前前台 Activity 的组件名(如 "com.android.settings/.Settings");未知返回空串。
    String getCurrentActivityName();
}
