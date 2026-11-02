/**
 * Muse 插件宿主 API 类型声明。
 *
 * 把这份文件放进你的插件目录（或在编辑器里引用它）就能获得补全与类型检查：
 *
 * ```ts
 * /// <reference path="../muse-plugin.d.ts" />
 * ```
 *
 * 两条硬规则：
 *  1. **声明 ≠ 授权**：清单里写了能力只是"申请"，用户批准之前每次调用都会被拒绝；
 *  2. 每个方法后面标注的 `action:` 是宿主内部的桥接动作名，用于排查"为什么被拒绝"。
 *     拒绝日志里出现的就是这个名字。
 */

/** 插件清单里可声明的能力。 */
type Capability =
  | "resource.read"
  | "resource.write"
  | "network"
  /** 允许访问私网/本机（局域网服务、127.0.0.1、.local）。默认关。 */
  | "network.local"
  /** 让宿主跳转到指定页面（页面白名单见作者文档）。默认关。 */
  | "app.navigate"
  /** 往会话里写消息（用户会当成助手说的）。默认关。 */
  | "app.messages"
  /** 切换浅色/深色外观。默认关。 */
  | "app.theme"
  /** 往宿主界面槽位里放自己的界面片段（当前只有 chat.top）。默认关。 */
  | "ui.slot"
  | "clipboard.read"
  | "clipboard.write"
  /** 读写合一的简写，等价于同时声明 clipboard.read + clipboard.write。 */
  | "clipboard"
  | "notify"
  | "device.info"
  | "storage"
  | "config.write"
  | "host.tool.invoke"
  | "chat.read"
  | "host.model.call"
  | "ui.interactive"
  | "runtime.python"
  | "event.onMessage"
  | "event.onToolCall"
  | "hook.toolCallBefore";

interface HostApi {
  /** 插件私有 KV（8MB）。别的插件读不到。 */
  storage: {
    /** action: storage_get */
    get(key: string): string | null;
    /** action: storage_set */
    set(key: string, value: string): boolean;
    /** action: storage_delete */
    delete(key: string): boolean;
    /** action: storage_list */
    list(): string[];
  };

  /** 插件自己的沙盒目录。路径不能跳出包目录。 */
  fs: {
    /** action: fs_list */
    list(path: string): Array<{ name: string; dir: boolean; size: number }>;
    /** action: fs_read */
    read(path: string): string;
    /** action: fs_write */
    write(path: string, content: string): boolean;
    /** action: fs_delete */
    delete(path: string): boolean;
  };

  /** 网络请求。只允许 https。 */
  http: {
    /** action: http_get */
    get(url: string): { status: number; body: string };
    /** action: http_post */
    post(url: string, body: string, contentType?: string): { status: number; body: string };
  };

  clipboard: {
    /** action: clipboard_read */
    read(): string;
    /** action: clipboard_write */
    write(text: string): boolean;
  };

  /** action: notify */
  notify(title: string, text?: string): boolean;

  device: {
    /** action: device_info */
    info(): { model: string; sdk: number; appVersion: string };
  };

  config: {
    /** 读用户填的配置（清单 contributes.configuration 里声明的项）。不需要能力。 */
    get(key: string): string | null;
    /** action: config_set */
    set(key: string, value: string): boolean;
  };

  /** 请求宿主执行一个工具（每次都会走审批）。 */
  tool: {
    /** action: tool_invoke */
    invoke(name: string, args?: Record<string, string>): string;
  };

  chat: {
    /** action: chat_read —— 只给最近 30 条，且不含系统提示词。 */
    read(): Array<{ role: "user" | "assistant"; content: string }>;
  };

  /** 改变应用本身（需要 app.* 能力，全部默认关）。 */
  app: {
    /**
     * 让宿主跳到某个页面。需要 `app.navigate`。
     *
     * 目标白名单：home / new / chat / assistants / settings / scheduled-tasks / quick-notes /
     * memory / knowledge / knowledge-bases / cloud-backup / settings-about / settings-agent /
     * settings-model / settings-data；参数化：`session:<id>`、`scheduled-task:<id>`、`quick-note:<id>`。
     *
     * 只在应用处于前台时生效：插件跑在后台，没有界面可跳，所以这是"投递请求"。
     * 没有前台界面时返回 false（不会假装成功）。
     *
     * action: app_navigate
     */
    navigate(target: string): boolean;

    /**
     * 往**当前会话**写一条消息。需要 `app.messages`（默认关，敏感）。
     *
     * 会话由宿主决定，插件无法指定 —— 你在参数里写 sessionId 不会生效。
     * 角色只允许 assistant / user（system 能改写模型行为，不给）。
     * 单条上限 4000 字。
     *
     * action: app_insert_message
     */
    insertMessage(text: string, role?: "assistant" | "user"): string;

    /**
     * 切明暗模式。需要 `app.theme`（默认关）。
     *
     * 只接受 `light` / `dark` / `system`。**具体皮肤不由插件决定** ——
     * 挑哪个主题是用户口味，而且自建主题的 id 空间只有 UI 层知道。
     *
     * action: app_set_theme_mode
     */
    setThemeMode(mode: "light" | "dark" | "system"): string;
  };

  /** 调用模型（计入用户用量）。输入上限 8KB，输出上限 4KB。 */
  model: {
    /** action: model_call */
    call(prompt: string, system?: string): string;
  };
}

declare const host: HostApi;

/** 工具函数：由清单 `tools[].functionName` 指定名字，宿主按名调用。 */
type ToolFunction = (args: Record<string, unknown>) => string | Promise<string>;

/**
 * 激活事件（清单 `activationEvents` 里声明后才会被调用）。
 *
 * 观察型事件：返回值会被忽略，也不要指望它拦下任何东西。
 */
interface ActivationHandlers {
  onStartup?: () => void;
  onMessage?: (event: { event: "onMessage"; text: string }) => void;
  onToolCall?: (event: { event: "onToolCall"; tool: string }) => void;
}

/**
 * 决策型钩子（需要 `hook.toolCallBefore`）。
 *
 * 只能**收窄**：返回 block 能拦下这次调用，返回 allow 不会绕过审批；
 * 报错或返回无法解析时按放行处理。
 */
declare function onToolCallBefore(event: {
  tool: string;
  args: Record<string, string>;
}): { action: "allow" } | { action: "block"; reason: string };
