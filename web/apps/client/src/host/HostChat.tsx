import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from "react";
import { hostLogout, type HostMessage } from "../api.js";
import { ArrowDownIcon, CloseIcon, MenuIcon, SendIcon, StopIcon } from "../components/icons.js";
import { ApprovalCards } from "./ApprovalCards.js";
import { AssistantPicker } from "./AssistantPicker.js";
import { BlockBoundary } from "./BlockBoundary.js";
import { readCapabilities } from "./capabilities.js";
import { buildStreamItems, toolStatusOf } from "./chatStream.js";
import { copyTextToClipboard } from "./clipboard.js";
import { composerDisabledHint, connectionBanner } from "./connectionStatus.js";
import { HostAvatar } from "./HostAvatar.js";
import { HostSettings } from "./HostSettings.js";
import { ImageLightbox } from "./ImageLightbox.js";
import { deleteConfirmKey, quoteExcerpt, selectionCopyText, type MessageActionId } from "./messageActions.js";
import { MessageBubble } from "./MessageBubble.js";
import { MessageErrorBoundary } from "./MessageErrorBoundary.js";
import { ScrollMemory } from "./scrollMemory.js";
import { formatPerfLine, perfFlag } from "./perf.js";
import { assistantIdForNewSession, shouldAskAssistant } from "./sessionActions.js";
import { useMessageWindow } from "./useMessageWindow.js";
import { SessionDrawer, SessionSidebar } from "./SessionDrawer.js";
import { useAppearance } from "./useAppearance.js";
import { useDelayedFlag } from "./useDelayedFlag.js";
import { useHostConnection } from "./useHostConnection.js";
import { useOnline } from "./useOnline.js";
import { I18nProvider } from "../i18n/I18nProvider.js";
import { DEFAULT_LOCALE } from "../i18n/locale.js";
import { formatNumber } from "../i18n/format.js";
import { createTranslator, type Translate } from "../i18n/translate.js";
import { useHostLocale } from "./useLocale.js";

interface HostChatProps {
  readonly onLogout: () => void;
}

const STATUS_KEY = { connected: "status.connected", reconnecting: "status.reconnecting", closed: "status.closed" } as const;
const TOAST_MS = 6000;

/** Track the iOS visual viewport so the composer stays glued above the keyboard. */
function useVisualViewport(): void {
  useEffect(() => {
    const viewport = window.visualViewport;
    const root = document.documentElement;
    const apply = () => {
      if (!viewport) return;
      root.style.setProperty("--host-vvh", `${Math.round(viewport.height)}px`);
      root.style.setProperty("--host-vv-top", `${Math.round(viewport.offsetTop)}px`);
    };
    apply();
    viewport?.addEventListener("resize", apply);
    viewport?.addEventListener("scroll", apply);
    return () => {
      viewport?.removeEventListener("resize", apply);
      viewport?.removeEventListener("scroll", apply);
      root.style.removeProperty("--host-vvh");
      root.style.removeProperty("--host-vv-top");
    };
  }, []);
}

export function HostChat({ onLogout }: HostChatProps) {
  const [toast, setToast] = useState<string | undefined>(undefined);
  const toastTimer = useRef<number | undefined>(undefined);
  const pushToast = useCallback((message: string) => {
    setToast(message);
    if (toastTimer.current !== undefined) window.clearTimeout(toastTimer.current);
    toastTimer.current = window.setTimeout(() => setToast(undefined), TOAST_MS);
  }, []);

  // 翻译器先按默认语言建一个稳定的转发（hook 的选项要在还没拿到设置时就有 t），
  // 真正的语言解析出来后只换实现，转发函数本身的引用不变。
  const translatorRef = useRef(createTranslator(DEFAULT_LOCALE));
  const translate: Translate = useCallback((key, params) => translatorRef.current(key, params), []);
  const { status, reconnectAttempt, ready, snapshot, previews, assistants, providers, assistantDetails, assistantDetailUnsupported, memory, knowledge, knowledgeDocuments, tasks, system, settings, send, sendOptimistic, requestPreviews, requestAssistants, requestProviders, requestAssistantDetail, requestMemory, requestKnowledge, requestKnowledgeDocuments, requestTasks, requestSystemStatus, requestSettings, updateSetting, updateSettingValue, retry } = useHostConnection({
    onError: pushToast,
    // perf 计时：快照落地时刻（只在 ?perf=1 时用到）。
    onSnapshot: () => {
      snapshotAtRef.current = performance.now();
    },
    translate,
  });
  const { sessions, messages, sessionId, isStreaming, approvals } = snapshot;
  const [everConnected, setEverConnected] = useState(false);
  /** 浏览器自身的联网状态：离线时不显示「正在重连」。 */
  const online = useOnline();
  /** 界面语言：设置里的 language（system 时按浏览器语言）；值一变，整棵树立刻换语言。 */
  const locale = useHostLocale(settings);
  const t = useMemo(() => createTranslator(locale), [locale]);
  translatorRef.current = t;
  /** 首屏骨架延迟 300ms，避免一闪而过。 */
  const chatSkeletonDue = useDelayedFlag(!ready);

  const [draft, setDraft] = useState("");
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [showJump, setShowJump] = useState(false);
  /** 设置主页是这个壳里的一层页面："chat" 是聊天，"settings" 是设置主页。 */
  const [view, setView] = useState<"chat" | "settings">("chat");
  const scrollRef = useRef<HTMLDivElement | null>(null);
  const inputRef = useRef<HTMLTextAreaElement | null>(null);
  const stickRef = useRef(true);
  /** 同一时刻只有一条消息亮着复制入口；存的是渲染项的 key。 */
  const [activeKey, setActiveKey] = useState<string | undefined>(undefined);
  /** 正在全屏查看的图片地址。 */
  const [previewImage, setPreviewImage] = useState<string | undefined>(undefined);
  /** 桌面档左栏是否收起（手机 / 平板档不读它）。 */
  const [sidebarCollapsed, setSidebarCollapsed] = useState(false);
  /** 多选模式：顶栖换成操作条，每条消息左侧出勾选。 */
  const [selectionMode, setSelectionMode] = useState(false);
  const [selectedIds, setSelectedIds] = useState<readonly string[]>([]);
  /** 乐观删除：先在本地藏起来，Host 拒绝就放回去。 */
  const [removedIds, setRemovedIds] = useState<readonly string[]>([]);
  /** 乐观收藏：Host 不下发 favorite 字段时靠它把菜单文案翻成「取消收藏」。 */
  const [favoriteOverrides, setFavoriteOverrides] = useState<Readonly<Record<string, boolean>>>({});
  /** 引用草稿：输入区上方那条。 */
  const [quoteDraft, setQuoteDraft] = useState<{ readonly messageId: string; readonly excerpt: string } | undefined>(undefined);
  /** 归档会话默认隐藏，抽屉 / 左栏共用一个开关。 */
  const [showArchived, setShowArchived] = useState(false);
  /** 新建会话选助手：助手多于一个时先弹这个。 */
  const [pickerOpen, setPickerOpen] = useState(false);
  /** 正在切换到的会话：切换期间的轻量加载提示。 */
  const [switchingTo, setSwitchingTo] = useState<string | undefined>(undefined);
  /** 每个会话上次的滚动位置（仅内存）。 */
  const scrollMemoryRef = useRef(new ScrollMemory());
  /** ?perf=1 调试开关与「快照到达时刻」，平时都不打开。 */
  const perfOn = useRef(typeof window === "undefined" ? false : perfFlag(window.location.search));
  const snapshotAtRef = useRef(0);
  /** 稳定回调要读的最新数据（memo 化后闭包会停在旧渲染，不能直接捕获状态）。 */
  const messagesRef = useRef<readonly HostMessage[]>([]);
  const sessionIdRef = useRef<string | undefined>(undefined);
  const sessionsRef = useRef(sessions);
  const favoriteOverridesRef = useRef<Readonly<Record<string, boolean>>>({});
  /** 切会话后等内容换完再落一次位置。 */
  const pendingRestoreRef = useRef<{ readonly sessionId: string; readonly top: number } | undefined>(undefined);

  useVisualViewport();
  useEffect(() => () => {
    if (toastTimer.current !== undefined) window.clearTimeout(toastTimer.current);
  }, []);
  useEffect(() => {
    if (status === "connected") setEverConnected(true);
  }, [status]);
  // 助手列表每次连上都取一次：新建会话要选助手，顶栖要显示名字与头像。
  useEffect(() => {
    if (status === "connected") requestAssistants();
  }, [status, requestAssistants]);
  // 外观（主题 / 字号）也要靠设置快照：连上就拉一次，不等用户打开设置页。
  useEffect(() => {
    if (status === "connected") requestSettings();
  }, [status, requestSettings]);
  // 把 data-theme / data-font-scale 写到根元素；值变（含回滚）就重写。
  useAppearance(settings);
  // 会话切换完成（快照里的 sessionId 已跟上）：收起切换提示。
  useEffect(() => {
    if (switchingTo !== undefined && switchingTo === sessionId) setSwitchingTo(undefined);
  }, [sessionId, switchingTo]);
  // 切换失败（例如焦点被别的设备抢走）时 sessionId 不会变：给个超时，别让提示挂着。
  useEffect(() => {
    if (switchingTo === undefined) return;
    const timer = window.setTimeout(() => setSwitchingTo(undefined), 4000);
    return () => window.clearTimeout(timer);
  }, [switchingTo]);
  // 设置主页只在打开时请一次 settings.snapshot；重连回来也会重取一次。
  useEffect(() => {
    if (view !== "settings" || status !== "connected") return;
    requestSettings();
  }, [view, status, requestSettings]);
  useEffect(() => {
    document.body.classList.add("host-locked");
    return () => document.body.classList.remove("host-locked");
  }, []);

  const currentSession = sessions.find((session) => session.id === sessionId);
  /** 当前会话的助手：顶栖显示名字与头像；没有会话时给一句引导语。 */
  const currentAssistant =
    currentSession === undefined ? undefined : assistants.find((assistant) => assistant.id === currentSession.assistantId);

  /** 本地已乐观删除的消息不再进入列表，其余顺序完全按 Host 给的。 */
  const visibleMessages = useMemo(
    () => (removedIds.length === 0 ? messages : messages.filter((message) => !removedIds.includes(message.id))),
    [messages, removedIds],
  );
  /** 消息流清单：按天插入时间分隔，分隔条参与列表渲染但不参与「贴底」判断。 */
  const items = useMemo(() => buildStreamItems(visibleMessages, Date.now(), locale), [visibleMessages, locale]);
  /** 长列表窗口化：超过阈值只渲染可视区 ± 20，两端等高占位。 */
  const windowPlan = useMessageWindow({
    scrollRef,
    itemCount: items.length,
    pinnedToEnd: stickRef.current,
    resetKey: sessionId ?? "",
  });
  /** 待审批的 toolCallId 集合，用来把工具块标成「待确认」。 */
  const approvalIds = useMemo(() => new Set(approvals.map((approval) => approval.toolCallId)), [approvals]);
  /** Host 声明的能力位：缺 media 不渲染图片/语音，缺 message_status 不显示状态与重试。 */
  const capabilities = useMemo(() => readCapabilities(snapshot.capabilities), [snapshot.capabilities]);

  function scrollToBottom(smooth = false) {
    const element = scrollRef.current;
    if (!element) return;
    stickRef.current = true;
    setShowJump(false);
    element.scrollTo({ top: element.scrollHeight, behavior: smooth ? "smooth" : "auto" });
  }

  // 换会话：取出这一条上次的滚动位置；没记录过就贴底。
  // 旧位置由 handleScroll 持续记录，这里不再补记——快照换完内容后 scrollTop 可能已被
  // 新的内容高度截断，在这时读会记错值。
  useLayoutEffect(() => {
    const element = scrollRef.current;
    if (sessionId === undefined) return;
    const saved = scrollMemoryRef.current.recall(sessionId);
    if (saved === undefined) {
      stickRef.current = true;
      setShowJump(false);
      return;
    }
    // 这时列表里可能还是上一个会话的内容，高度对不上：先落一次，等内容换完再落一次。
    pendingRestoreRef.current = { sessionId, top: saved };
    stickRef.current = false;
    setShowJump(saved > 0);
    if (element !== null) element.scrollTop = saved;
  }, [sessionId]);

  // 内容变化后：有待恢复的位置就落位，否则维持原来的「贴底才跟随」。
  // 依赖 items 而不是 messages：新插入时间分隔也会改变列表高度，同样要贴底。
  useLayoutEffect(() => {
    const element = scrollRef.current;
    if (element === null) return;
    const pending = pendingRestoreRef.current;
    if (pending !== undefined && pending.sessionId === sessionId) {
      element.scrollTop = pending.top;
      pendingRestoreRef.current = undefined;
      return;
    }
    if (!stickRef.current) return;
    element.scrollTop = element.scrollHeight;
  }, [items, sessionId]);

  // ?perf=1：把「收到快照 → 渲染提交」的耗时与条数打到控制台（未带参数时零开销）。
  useLayoutEffect(() => {
    if (!perfOn.current || snapshotAtRef.current === 0) return;
    const elapsed = performance.now() - snapshotAtRef.current;
    snapshotAtRef.current = 0;
    console.info(formatPerfLine(messages.length, elapsed));
  }, [messages]);

  // Keyboard opening/closing changes the scroll container height.
  useEffect(() => {
    const viewport = window.visualViewport;
    const onResize = () => {
      if (stickRef.current) scrollToBottom();
    };
    viewport?.addEventListener("resize", onResize);
    return () => viewport?.removeEventListener("resize", onResize);
  }, []);

  useEffect(() => {
    const element = inputRef.current;
    if (!element) return;
    element.style.height = "auto";
    element.style.height = `${Math.min(element.scrollHeight, 132)}px`;
  }, [draft]);

  function handleScroll() {
    const element = scrollRef.current;
    if (!element) return;
    const distance = element.scrollHeight - element.scrollTop - element.clientHeight;
    const atBottom = distance < 80;
    stickRef.current = atBottom;
    setShowJump((current) => (current === !atBottom ? current : !atBottom));
    // 每个会话记住自己的位置，切回去时不再从头开始。
    if (sessionId !== undefined) scrollMemoryRef.current.remember(sessionId, element.scrollTop);
  }

  function submit() {
    const text = draft.trim();
    if (!text || isStreaming || status !== "connected") return;
    setDraft("");
    setShowJump(false);
    const quoted = quoteDraft;
    setQuoteDraft(undefined);
    stickRef.current = true;
    send({
      type: "chat.send",
      ...(sessionId === undefined ? {} : { sessionId }),
      text,
      // 引用随 chat.send 带被引用消息的 id：Host 给这个字段起的名字是 quotedMessageId。
      ...(quoted === undefined ? {} : { quotedMessageId: quoted.messageId }),
    });
    window.requestAnimationFrame(() => scrollToBottom(true));
  }

  const writeCommand = send;

  function selectSession(nextId: string) {
    setDrawerOpen(false);
    setSelectionMode(false);
    setSelectedIds([]);
    if (nextId === sessionId) return;
    // 切换期间给一条轻量提示（旧内容还在，不白屏），快照跟上后自动消失。
    setSwitchingTo(nextId);
    writeCommand({ type: "session.select", sessionId: nextId });
  }

  function newSession() {
    setDrawerOpen(false);
    stickRef.current = true;
    // 助手多于一个时先让用户选；一个直接带上它，零个交给 Host 的默认助手。
    if (shouldAskAssistant(assistants)) {
      setPickerOpen(true);
      return;
    }
    const assistantId = assistantIdForNewSession(assistants);
    writeCommand({ type: "session.new", ...(assistantId === undefined ? {} : { assistantId }) });
  }

  function createSessionWith(assistantId: string) {
    setPickerOpen(false);
    setDrawerOpen(false);
    stickRef.current = true;
    writeCommand({ type: "session.new", assistantId });
  }

  /** 重命名直接用行内输入给出的标题（空名在会话面板里已经被拦住）。 */
  function renameSession(targetId: string, title: string) {
    const trimmed = title.trim();
    if (trimmed.length === 0) return;
    writeCommand({ type: "session.rename", sessionId: targetId, title: trimmed });
  }

  function archiveSession(targetId: string) {
    writeCommand({ type: "session.archive", sessionId: targetId, archived: true });
  }

  function deleteSession(targetId: string) {
    const title = sessions.find((session) => session.id === targetId)?.title ?? "";
    // 文案要写清「会话 + 全部消息」，不能只说「不可撤销」。
    if (window.confirm(t("sessions.deleteConfirm", { name: title.trim() || t("sessions.untitled") }))) writeCommand({ type: "session.delete", sessionId: targetId });
  }

  function resolveApproval(toolCallId: string, decision: "approved" | "denied") {
    writeCommand({ type: "tool.approval.resolve", generationId: toolCallId, text: decision });
  }

  function stop() {
    writeCommand({ type: "chat.stop", ...(sessionId === undefined ? {} : { sessionId }) });
  }

  function logout() {
    setDrawerOpen(false);
    void hostLogout().catch(() => undefined);
    onLogout();
  }

  const canCompose = status === "connected" && online;
  const lastMessageIndex = visibleMessages.length - 1;
  const lastAssistantIndex = (() => {
    for (let index = visibleMessages.length - 1; index >= 0; index -= 1) {
      if (visibleMessages[index]?.role === "assistant") return index;
    }
    return -1;
  })();
  const isFavorited = (messageId: string, fallback: boolean | undefined): boolean => favoriteOverrides[messageId] ?? fallback === true;

  // 每次渲染把最新数据放进 ref：下面的回调都是用 useCallback 固定引用的，靠 ref 拿新值。
  messagesRef.current = visibleMessages;
  sessionIdRef.current = sessionId;
  sessionsRef.current = sessions;
  favoriteOverridesRef.current = favoriteOverrides;

  /** 复制正文：网页不在 secure context 时 Clipboard API 不存在，只好看能不能退到 execCommand。 */
  const copyMessage = useCallback(
    async (text: string) => {
      const copied = await copyTextToClipboard(text);
      pushToast(copied ? t("chat.copied") : t("chat.copyFailed"));
    },
    [pushToast],
  );

  /** 引用：本地先拼摘要（不等往返），发送时随 chat.send 带 quotedMessageId。 */
  const quoteMessageById = useCallback((messageId: string) => {
    const message = messagesRef.current.find((item) => item.id === messageId);
    if (message === undefined) return;
    setActiveKey(undefined);
    setQuoteDraft({ messageId, excerpt: quoteExcerpt(message.content) });
    inputRef.current?.focus();
  }, []);

  /** 收藏 / 取消收藏：先翻本地状态，Host 拒绝时撤回去并说明原因。 */
  const toggleFavoriteById = useCallback(
    (messageId: string) => {
      const message = messagesRef.current.find((item) => item.id === messageId);
      if (message === undefined) return;
      const next = !(favoriteOverridesRef.current[messageId] ?? message.favorite === true);
      setActiveKey(undefined);
      setFavoriteOverrides((current) => ({ ...current, [messageId]: next }));
      sendOptimistic({ type: "message.favorite", messageId, favored: next }, () => {
        setFavoriteOverrides((current) => {
          const restored = { ...current };
          delete restored[messageId];
          return restored;
        });
      });
    },
    [sendOptimistic],
  );

  /** 删除（单条或多选）：先二次确认，再从列表里乐观移除；失败就放回去。 */
  const deleteMessages = useCallback(
    (ids: readonly string[]) => {
      if (ids.length === 0) return;
      if (!window.confirm(t(deleteConfirmKey(ids.length), { count: ids.length }))) return;
      setActiveKey(undefined);
      setRemovedIds((current) => [...current, ...ids.filter((id) => !current.includes(id))]);
      setSelectedIds([]);
      setSelectionMode(false);
      sendOptimistic(
        { type: "message.delete", messageIds: [...ids] },
        () => {
          // Host 拒绝：把被乐观藏起来的消息放回去。
          setRemovedIds((current) => current.filter((id) => !ids.includes(id)));
        },
        (event) => {
          // 回执里点名跳过的（比如正在生成的那条）：同样恢复显示，并说一句原因。
          const skipped = event.skippedMessageIds ?? [];
          if (skipped.length === 0) return;
          setRemovedIds((current) => current.filter((id) => !skipped.includes(id)));
          pushToast(t("chat.deleteSkipped", { count: skipped.length }));
        },
      );
    },
    [sendOptimistic, pushToast],
  );

  /** 菜单分发：只带 messageId，回调本身保持稳定引用（memo 才有效）。 */
  const handleMessageAction = useCallback(
    (messageId: string, action: MessageActionId) => {
      switch (action) {
        case "quote":
          quoteMessageById(messageId);
          return;
        case "favorite":
        case "unfavorite":
          toggleFavoriteById(messageId);
          return;
        case "delete":
          deleteMessages([messageId]);
          return;
        case "select":
          setActiveKey(undefined);
          setSelectionMode(true);
          setSelectedIds([messageId]);
          return;
        default:
          return;
      }
    },
    [quoteMessageById, toggleFavoriteById, deleteMessages],
  );

  const toggleActions = useCallback((messageId: string) => {
    setActiveKey((current) => (current === messageId ? undefined : messageId));
  }, []);

  const toggleSelected = useCallback((messageId: string) => {
    setSelectedIds((current) =>
      current.includes(messageId) ? current.filter((id) => id !== messageId) : [...current, messageId],
    );
  }, []);

  const retryMessage = useCallback(
    (messageId: string) => {
      send({ type: "chat.retry", messageId });
    },
    [send],
  );

  const regenerate = useCallback(() => {
    const id = sessionIdRef.current;
    send({ type: "chat.regenerate", ...(id === undefined ? {} : { sessionId: id }) });
  }, [send]);

  const continueGeneration = useCallback(() => {
    const id = sessionIdRef.current;
    send({ type: "chat.continue", ...(id === undefined ? {} : { sessionId: id }) });
  }, [send]);

  const openImage = useCallback((src: string) => setPreviewImage(src), []);

  /** 从助手详情直接开一个会话：回到聊天页，带上该助手。 */
  const startSessionWith = useCallback(
    (assistantId: string) => {
      setView("chat");
      stickRef.current = true;
      send({ type: "session.new", assistantId });
    },
    [send],
  );

  function exitSelection() {
    setSelectionMode(false);
    setSelectedIds([]);
  }

  function selectAllMessages() {
    setSelectedIds(visibleMessages.map((message) => message.id));
  }

  function copySelected() {
    const picked = visibleMessages.filter((message) => selectedIds.includes(message.id));
    void copyMessage(selectionCopyText(picked));
  }

  function favoriteSelected() {
    const picked = visibleMessages.filter((message) => selectedIds.includes(message.id));
    for (const message of picked) {
      if (!isFavorited(message.id, message.favorite)) toggleFavoriteById(message.id);
    }
    exitSelection();
  }

  // 换会话不该带着上一条的消息操作条、多选态和全屏图过去。
  useEffect(() => {
    setActiveKey(undefined);
    setPreviewImage(undefined);
    setSelectionMode(false);
    setSelectedIds([]);
    setQuoteDraft(undefined);
  }, [sessionId]);

  // Esc：先收起消息菜单，其次退出多选。
  useEffect(() => {
    if (activeKey === undefined && !selectionMode) return;
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key !== "Escape") return;
      setActiveKey(undefined);
      if (selectionMode) {
        setSelectionMode(false);
        setSelectedIds([]);
      }
    };
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [activeKey, selectionMode]);

  const bannerModel = connectionBanner({ online, status, attempt: reconnectAttempt, everConnected });
  const composerHintKey = composerDisabledHint({ online, status });
  const composerHint = composerHintKey === undefined ? undefined : t(composerHintKey);
  const banner = bannerModel.visible && (
    <div className={`host-banner is-${bannerModel.tone}`} role="status" aria-live="polite">
      <span>{t(bannerModel.textKey, bannerModel.params)}</span>
      {bannerModel.retry && (
        <button className="quiet host-banner-action" type="button" onClick={retry}>
          {t("connection.manualRetry")}
        </button>
      )}
    </div>
  );

  const toastNode = toast !== undefined && (
    <div className="host-toast" role="alert" onClick={() => setToast(undefined)}>
      {toast}
    </div>
  );

  // 设置主页同样要看到连接提示条：断线时不许用户面对一个永远转圈的设置页。
  if (view === "settings") {
    return (
      <I18nProvider locale={locale}>
      <div className="host-root host-scope is-settings">
        {banner}
        <BlockBoundary name={t("block.settings")}>
          <HostSettings
            settings={settings}
            capabilities={capabilities}
            assistants={assistants}
            providers={providers}
            assistantDetails={assistantDetails}
            assistantDetailUnsupported={assistantDetailUnsupported}
            memory={memory}
            knowledge={knowledge}
            knowledgeDocuments={knowledgeDocuments}
            tasks={tasks}
            system={system}
            capabilityFlags={snapshot.capabilities}
            onRequestAssistantDetail={requestAssistantDetail}
            onRequestProviders={requestProviders}
            onRequestMemory={requestMemory}
            onRequestKnowledge={requestKnowledge}
            onRequestKnowledgeDocuments={requestKnowledgeDocuments}
            onRequestTasks={requestTasks}
            onRequestSystemStatus={requestSystemStatus}
            onStartSession={startSessionWith}
            onBack={() => setView("chat")}
            onRetry={requestSettings}
            onToggle={updateSetting}
            onWrite={updateSettingValue}
          />
        </BlockBoundary>
        {toastNode}
      </div>
      </I18nProvider>
    );
  }

  function openSettings() {
    setDrawerOpen(false);
    setView("settings");
  }

  return (
    <I18nProvider locale={locale}>
    <div className={sidebarCollapsed ? "host-root host-scope is-sidebar-collapsed" : "host-root host-scope"}>
      {/* 桌面档（≥1024）常驻左栏，可收起；手机 / 平板仍用抽屉，两者由 CSS 互斥。 */}
      <BlockBoundary name={t("block.sessions")}>
        <SessionSidebar
          sessions={sessions}
          ready={ready}
          showArchived={showArchived}
          onToggleArchived={() => setShowArchived((current) => !current)}
          currentId={sessionId}
          previews={previews}
          buildId={__MUSE_BUILD_ID__}
          onSelect={selectSession}
          onNew={newSession}
          onRequestPreviews={requestPreviews}
          onRename={renameSession}
          onArchive={archiveSession}
          onDelete={deleteSession}
          onOpenSettings={openSettings}
          onLogout={logout}
          collapsed={sidebarCollapsed}
          onToggleSidebar={() => setSidebarCollapsed((current) => !current)}
        />
      </BlockBoundary>

      <div className="host-main">
        {selectionMode ? (
          <header className="host-topbar host-selection-topbar">
            <button className="quiet host-icon-button" type="button" aria-label={t("chat.exitSelection")} onClick={exitSelection}>
              <CloseIcon />
            </button>
            <div className="host-topbar-title">
              <h1>{t("chat.selectedCount", { count: formatNumber(selectedIds.length, locale) })}</h1>
            </div>
            <button
              className="quiet host-selection-all"
              type="button"
              onClick={selectAllMessages}
              disabled={visibleMessages.length === 0}
            >
              {t("chat.selectAll")}
            </button>
          </header>
        ) : (
        <header className="host-topbar">
          {/* 桌面档收起左栏后用它展开；手机 / 平板档由 CSS 隐藏，☰ 仍是抽屉入口 */}
          <button
            className="quiet host-icon-button host-sidebar-toggle"
            type="button"
            aria-label={t("chat.menuExpand")}
            onClick={() => setSidebarCollapsed(false)}
          >
            <MenuIcon />
          </button>
          <button
            className="quiet host-icon-button host-menu-button"
            type="button"
            aria-label={t("chat.menu")}
            onClick={() => setDrawerOpen(true)}
          >
            <MenuIcon />
          </button>
          <div className="host-topbar-title">
            <span className="host-topbar-head">
              {currentAssistant !== undefined && (
                <HostAvatar name={currentAssistant.name} uri={currentAssistant.avatarUri} />
              )}
              <h1>{currentSession?.title ?? t("chat.pickAssistant")}</h1>
            </span>
            <p>
              {currentAssistant !== undefined ? `${currentAssistant.name} · ` : ""}
              {online ? t(STATUS_KEY[status]) : t("status.offline")}
              {isStreaming ? ` · ${t("status.generating")}` : ""}
            </p>
          </div>
          <span
            className={`host-status-dot ${status}`}
            role="status"
            aria-label={t("connection.stateAria", { status: t(STATUS_KEY[status]) })}
            title={t(STATUS_KEY[status])}
          />
        </header>
        )}

        {banner}

        {switchingTo !== undefined && switchingTo !== sessionId && (
          <div className="host-switch-loading" role="status" aria-live="polite">
            {t("chat.switching")}
          </div>
        )}

        <div className="host-scroll" ref={scrollRef} onScroll={handleScroll}>
          <BlockBoundary name={t("block.messages")}>
          <div className="host-thread" role="log" aria-live="polite" aria-label={t("chat.threadAria")}>
            {!ready && chatSkeletonDue && (
              <div className="host-chat-skeleton" role="status" aria-label={t("chat.loadingThread")}>
                {[0, 1, 2, 3].map((index) => (
                  <div className={index % 2 === 1 ? "host-chat-skeleton-row is-user" : "host-chat-skeleton-row"} key={index}>
                    <span className="muse-skeleton" />
                    <span className="muse-skeleton is-thin" />
                  </div>
                ))}
              </div>
            )}
            {ready && sessions.length === 0 && (
              <div className="host-empty">
                <h2>{t("chat.emptyNoSessionsTitle")}</h2>
                <p>{t("chat.emptyNoSessionsBody")}</p>
                <button type="button" onClick={newSession}>
                  {t("chat.newSession")}
                </button>
              </div>
            )}
            {ready && sessions.length > 0 && messages.length === 0 && (
              <div className="host-empty">
                <h2>{t("chat.emptyNoMessagesTitle")}</h2>
                <p>{t("chat.emptyNoMessagesBody")}</p>
              </div>
            )}
            {windowPlan.topPad > 0 && <div className="host-window-pad" style={{ height: windowPlan.topPad }} aria-hidden="true" />}
            {items.slice(windowPlan.start, windowPlan.end).map((item) =>
              item.kind === "divider" ? (
                <div className="host-day-sep" key={item.key} role="separator" data-host-row>
                  <span className="host-day-sep-line" aria-hidden="true" />
                  <span className="host-day-sep-label">{item.label}</span>
                  <span className="host-day-sep-line" aria-hidden="true" />
                </div>
              ) : (
                <div
                  key={item.key}
                  data-host-row
                  data-new={item.index === lastMessageIndex ? "true" : undefined}
                  className={`host-msg-row${item.message.role === "user" ? " is-user" : ""}${selectionMode ? " is-selecting" : ""}`}
                >
                  {selectionMode && (
                    <button
                      className={selectedIds.includes(item.message.id) ? "host-select is-on" : "host-select"}
                      type="button"
                      role="checkbox"
                      aria-checked={selectedIds.includes(item.message.id)}
                      aria-label={selectedIds.includes(item.message.id) ? t("chat.deselectMessage") : t("chat.selectMessage")}
                      onClick={() => toggleSelected(item.message.id)}
                    >
                      <span className="host-select-mark" aria-hidden="true" />
                    </button>
                  )}
                  <MessageErrorBoundary resetKey={`${item.key}:${item.message.content.length}:${item.message.reasoning?.length ?? 0}`}>
                  <MessageBubble
                    message={item.message}
                    streaming={isStreaming && item.index === lastMessageIndex && item.message.role === "assistant"}
                    showActions={item.index === lastMessageIndex && item.index === lastAssistantIndex && !isStreaming}
                    capabilities={capabilities}
                    favorited={isFavorited(item.message.id, item.message.favorite)}
                    toolStatus={
                      item.message.toolName === undefined && item.message.role !== "tool"
                        ? undefined
                        : toolStatusOf(item.message, {
                            isStreaming,
                            isLast: item.index === lastMessageIndex,
                            approvalToolCallIds: approvalIds,
                          })
                    }
                    actionsOpen={activeKey === item.message.id}
                    onToggleActions={toggleActions}
                    selectionMode={selectionMode}
                    onToggleSelect={toggleSelected}
                    onAction={handleMessageAction}
                    onRetry={retryMessage}
                    onOpenImage={openImage}
                    onCopy={copyMessage}
                    onRegenerate={regenerate}
                    onContinue={continueGeneration}
                  />
                  </MessageErrorBoundary>
                </div>
              ),
            )}
            {windowPlan.bottomPad > 0 && <div className="host-window-pad" style={{ height: windowPlan.bottomPad }} aria-hidden="true" />}
          </div>
          </BlockBoundary>
          {showJump && (
            <div className="host-jump-row">
              <button className="host-jump" type="button" onClick={() => scrollToBottom(true)}>
                <ArrowDownIcon />
                {t("chat.jumpLatest")}
              </button>
            </div>
          )}
        </div>

        {selectionMode ? (
          <footer className="host-selection-bar" role="toolbar" aria-label={t("chat.selectionTopAria")}>
            {capabilities.messageWrite && (
              <button className="quiet host-selection-danger" type="button" disabled={selectedIds.length === 0} onClick={() => deleteMessages(selectedIds)}>
                {t("common.delete")}
              </button>
            )}
            {capabilities.messageWrite && (
              <button className="quiet" type="button" disabled={selectedIds.length === 0} onClick={favoriteSelected}>
                {t("common.favorite")}
              </button>
            )}
            <button className="quiet" type="button" disabled={selectedIds.length === 0} onClick={copySelected}>
              {t("common.copy")}
            </button>
          </footer>
        ) : (
        <footer className="host-composer">
          <ApprovalCards approvals={approvals} onResolve={resolveApproval} />
          {quoteDraft !== undefined && (
            <div className="host-quote-draft">
              <span className="host-quote-draft-mark" aria-hidden="true">
                {t("message.quoteMark")}
              </span>
              <span className="host-quote-draft-text">{quoteDraft.excerpt}</span>
              <button
                className="quiet host-quote-draft-close"
                type="button"
                aria-label={t("chat.cancelQuote")}
                onClick={() => setQuoteDraft(undefined)}
              >
                <CloseIcon size={18} />
              </button>
            </div>
          )}
          {composerHint !== undefined && (
            <p className="host-composer-hint" id="host-composer-hint">
              {composerHint}
            </p>
          )}
          <div className="host-input-shell">
            <textarea
              ref={inputRef}
              className="host-input"
              aria-label={t("composer.ariaMessage")}
              placeholder={composerHint ?? (canCompose ? t("composer.placeholder") : t("composer.placeholderConnecting"))}
              rows={1}
              value={draft}
              enterKeyHint="send"
              autoComplete="off"
              disabled={!canCompose}
              aria-describedby={composerHint === undefined ? undefined : "host-composer-hint"}
              onChange={(event) => setDraft(event.target.value)}
              onKeyDown={(event) => {
                if (event.key !== "Enter") return;
                if (event.nativeEvent.isComposing) return;
                if (event.shiftKey) return;
                event.preventDefault();
                submit();
              }}
            />
            {isStreaming ? (
              <button className="host-send is-stop" type="button" aria-label={t("composer.stop")} onClick={stop}>
                <StopIcon />
              </button>
            ) : (
              <button
                className="host-send"
                type="button"
                aria-label={t("composer.send")}
                disabled={!canCompose || !draft.trim()}
                onClick={submit}
              >
                <SendIcon />
              </button>
            )}
          </div>
        </footer>
        )}
      </div>

      <BlockBoundary name={t("block.sessions")}>
        <SessionDrawer
          open={drawerOpen}
          sessions={sessions}
          ready={ready}
          showArchived={showArchived}
          onToggleArchived={() => setShowArchived((current) => !current)}
          currentId={sessionId}
          previews={previews}
          buildId={__MUSE_BUILD_ID__}
          onClose={() => setDrawerOpen(false)}
          onSelect={selectSession}
          onNew={newSession}
          onRequestPreviews={requestPreviews}
          onRename={renameSession}
          onArchive={archiveSession}
          onDelete={deleteSession}
          onOpenSettings={openSettings}
          onLogout={logout}
        />
      </BlockBoundary>

      {toastNode}

      {pickerOpen && (
        <AssistantPicker
          assistants={assistants}
          onPick={(assistant) => createSessionWith(assistant.id)}
          onClose={() => setPickerOpen(false)}
        />
      )}

      {previewImage !== undefined && <ImageLightbox src={previewImage} onClose={() => setPreviewImage(undefined)} />}
    </div>
    </I18nProvider>
  );
}
