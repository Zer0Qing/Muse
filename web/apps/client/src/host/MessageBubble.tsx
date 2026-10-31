import { memo, useEffect, useRef } from "react";
import type { KeyboardEvent as ReactKeyboardEvent, MouseEvent, PointerEvent as ReactPointerEvent } from "react";
import { MessageContent } from "../components/MessageContent.js";
import { CopyIcon, SparkIcon, ToolIcon } from "../components/icons.js";
import type { HostMessage } from "../api.js";
import type { HostCapabilities } from "./capabilities.js";
import { CollapsibleBlock } from "./CollapsibleBlock.js";
import { TOOL_STATUS_KEY, type ToolStatus } from "./chatStream.js";
import { actionsForMessage, type MessageActionId, type MessageActionItem } from "./messageActions.js";
import { areBubblePropsEqual } from "./messageEqual.js";
import { useI18n } from "../i18n/I18nProvider.js";
import { MessageImages } from "./MessageImages.js";
import { resolveMessageImages, resolveVoiceUrl } from "./messageMedia.js";
import { ToolCallBlocks } from "./ToolCallBlocks.js";
import { VoiceBar } from "./VoiceBar.js";

/** 长按判定：按住超过这个时长、手指没有明显移动，才算长按。 */
const LONG_PRESS_MS = 450;
const LONG_PRESS_SLOP = 10;

interface MessageBubbleProps {
  readonly message: HostMessage;
  /** Last assistant turn still growing: render the caret. */
  readonly streaming: boolean;
  readonly showActions: boolean;
  readonly onRegenerate: () => void;
  readonly onContinue: () => void;
  /** 工具调用块的状态；老 Host 没有 toolCalls[] 时用它推断。 */
  readonly toolStatus?: ToolStatus | undefined;
  /** Host 声明的能力位，决定图片/语音/状态/菜单项要不要渲染。 */
  readonly capabilities: HostCapabilities;
  /** 这条消息当前是否已收藏（Host 未下发时由上层按本地乐观状态给）。 */
  readonly favorited?: boolean | undefined;
  /** 复制这条消息的正文；成功与否由上层负责说出来。 */
  readonly onCopy?: ((text: string) => void) | undefined;
  /** 引用 / 收藏 / 取消收藏 / 删除 / 多选；带上消息 id，上层就能用稳定回调。 */
  readonly onAction?: ((messageId: string, action: MessageActionId) => void) | undefined;
  /** 操作条是否展开：同一时刻只让一条消息亮着菜单。 */
  readonly actionsOpen?: boolean | undefined;
  /** 点击或长按气泡时通知上层切换这条的菜单。 */
  readonly onToggleActions?: ((messageId: string) => void) | undefined;
  /** 发送失败时的重试（Host 命令 chat.retry）。 */
  readonly onRetry?: ((messageId: string) => void) | undefined;
  /** 点开图片全屏查看。 */
  readonly onOpenImage?: ((src: string) => void) | undefined;
  /** 多选模式：点气泡是选中/取消，而不是开菜单。 */
  readonly selectionMode?: boolean | undefined;
  readonly onToggleSelect?: ((messageId: string) => void) | undefined;
}

function toolTone(status: ToolStatus): "neutral" | "accent" | "warn" {
  if (status === "awaiting") return "warn";
  if (status === "running") return "accent";
  return "neutral";
}

/**
 * 长按或点击气泡的手势。
 *
 * 长按触发后置位 fired，紧随其后的 click 会被吞掉，不会把刚打开的菜单又关掉；
 * 长按成功时顺手挡掉浏览器原生的长按选择菜单，避免两层菜单叠在一起。
 */
function usePressActions(onActivate: () => void) {
  const timer = useRef<number | undefined>(undefined);
  const origin = useRef<{ readonly x: number; readonly y: number } | undefined>(undefined);
  const fired = useRef(false);
  const activate = useRef(onActivate);
  activate.current = onActivate;

  const stop = () => {
    if (timer.current !== undefined) window.clearTimeout(timer.current);
    timer.current = undefined;
    origin.current = undefined;
  };
  useEffect(() => stop, []);

  return {
    fired,
    handlers: {
      onPointerDown: (event: ReactPointerEvent<HTMLElement>) => {
        fired.current = false;
        if (event.pointerType === "mouse") return;
        stop();
        origin.current = { x: event.clientX, y: event.clientY };
        timer.current = window.setTimeout(() => {
          timer.current = undefined;
          origin.current = undefined;
          fired.current = true;
          activate.current();
        }, LONG_PRESS_MS);
      },
      onPointerMove: (event: ReactPointerEvent<HTMLElement>) => {
        const from = origin.current;
        if (timer.current === undefined || from === undefined) return;
        if (Math.abs(event.clientX - from.x) > LONG_PRESS_SLOP || Math.abs(event.clientY - from.y) > LONG_PRESS_SLOP) stop();
      },
      onPointerUp: () => {
        if (!fired.current) stop();
      },
      onPointerCancel: stop,
      onPointerLeave: stop,
      onContextMenu: (event: MouseEvent<HTMLElement>) => {
        if (fired.current) event.preventDefault();
      },
    },
  };
}

/** 消息菜单：横向可换行的小胶囊，点开后出现在消息内部下方，不会顶出屏幕。 */
function MessageActionMenu({
  actions,
  onPick,
}: {
  readonly actions: readonly MessageActionItem[];
  readonly onPick: (action: MessageActionItem) => void;
}) {
  const { t } = useI18n();
  return (
    <div className="host-bubble-tools">
      {actions.map((action) => (
        <button
          key={action.id}
          className={action.danger === true ? "quiet host-tool-button is-danger" : "quiet host-tool-button"}
          type="button"
          aria-label={action.id === "select" ? t(action.labelKey) : t("message.actionAria", { action: t(action.labelKey) })}
          onClick={(event) => {
            event.stopPropagation();
            onPick(action);
          }}
        >
          {action.id === "copy" && <CopyIcon />}
          <span>{t(action.labelKey)}</span>
        </button>
      ))}
    </div>
  );
}

function MessageBubbleView({  message,
  streaming,
  showActions,
  onRegenerate,
  onContinue,
  toolStatus,
  capabilities,
  favorited = false,
  onCopy,
  onAction,
  actionsOpen = false,
  onToggleActions,
  onRetry,
  onOpenImage,
  selectionMode = false,
  onToggleSelect,
}: MessageBubbleProps) {
  const { t } = useI18n();
  const press = usePressActions(() => onToggleActions?.(message.id));

  const actions = actionsForMessage({
    role: message.role,
    hasContent: message.content.trim().length > 0,
    favorited,
    capabilities,
  });
  const menuOpen = actionsOpen && actions.length > 0;

  const pick = (action: MessageActionItem) => {
    if (action.id === "copy") onCopy?.(message.content);
    else onAction?.(message.id, action.id);
  };

  const interactive = {
    tabIndex: actions.length > 0 ? 0 : undefined,
    onClick: (event: MouseEvent<HTMLElement>) => {
      if (press.fired.current) {
        press.fired.current = false;
        return;
      }
      // 多选模式下点消息 = 勾选，不再弹菜单。
      if (selectionMode) {
        onToggleSelect?.(message.id);
        return;
      }
      // 点按钮 / 链接 / 代码块 / 语音控件是原本的手势，不抢来当「打开菜单」。
      if ((event.target as HTMLElement).closest("button,a,pre,summary,audio") !== null) return;
      // 正在选文字（双击选词、拖选手势的收尾 click）也不抢。
      if (window.getSelection()?.toString().length) return;
      onToggleActions?.(message.id);
    },
    onKeyDown: (event: ReactKeyboardEvent<HTMLElement>) => {
      // 键盘等价于长按：Enter / 空格开合菜单（触控与鼠标之外的那条路径）。
      // 只在消息本身聚焦时生效：内部的折叠头、按钮、链接不能被我抢走按键。
      if (event.target !== event.currentTarget) return;
      if (event.key !== "Enter" && event.key !== " ") return;
      if (selectionMode) return;
      event.preventDefault();
      onToggleActions?.(message.id);
    },
    ...press.handlers,
  };

  // Host 未来可能下发新的 role：不认识的按一行中性展示处理，绝不抛异常。
  const role: string = message.role;

  if (role === "tool") {
    const status = toolStatus ?? "done";
    return (
      <div className={menuOpen ? "host-tool is-open" : "host-tool"} {...interactive}>
        <CollapsibleBlock
          icon={<ToolIcon />}
          label={`${t("message.toolResult")}${message.toolName ? ` · ${message.toolName}` : ""}`}
          status={t(TOOL_STATUS_KEY[status])}
          tone={toolTone(status)}
        >
          {message.content || t("message.emptyResult")}
        </CollapsibleBlock>
        {menuOpen && <MessageActionMenu actions={actions} onPick={pick} />}
      </div>
    );
  }

  if (role === "system") {
    return (
      <div className={menuOpen ? "host-system is-open" : "host-system"} {...interactive}>
        {message.content}
        {menuOpen && <MessageActionMenu actions={actions} onPick={pick} />}
      </div>
    );
  }

  if (role !== "user" && role !== "assistant") {
    return (
      <div className={menuOpen ? "host-msg-unknown is-open" : "host-msg-unknown"} {...interactive}>
        <span className="host-msg-unknown-label">{t("message.unknownRole")}</span>
        {message.content.length > 0 && <span className="host-msg-unknown-text">{message.content}</span>}
        {menuOpen && <MessageActionMenu actions={actions} onPick={pick} />}
      </div>
    );
  }

  const isUser = role === "user";
  // 缺 media 能力位就整块不渲染：宁可少一块，也不给一片永远加载不出的图。
  const images = capabilities.media ? resolveMessageImages(message.imageUrls) : [];
  const voiceSrc = capabilities.media ? resolveVoiceUrl(message.voiceUrl) : undefined;
  const hasVoice = capabilities.media && (message.voiceUrl !== undefined || (message.voiceDurationMs ?? 0) > 0);
  // 老 Host 没有 toolCalls[]，退回消息级 toolName + 状态推断。
  const calls = capabilities.toolCallDetails ? message.toolCalls ?? [] : [];
  const status = capabilities.messageStatus ? message.status : undefined;
  const quoted = message.quotedContent !== undefined && message.quotedContent.trim().length > 0 ? message.quotedContent : undefined;
  return (
    <article
      className={`${isUser ? "host-bubble is-user" : "host-bubble is-assistant"}${menuOpen ? " is-open" : ""}${selectionMode ? " is-selecting" : ""}`}
      {...interactive}
    >
      {quoted !== undefined && (
        <blockquote className="host-quote">
          <span className="host-quote-mark" aria-hidden="true">
            {t("message.quoteMark")}
          </span>
          <span className="host-quote-text">{quoted}</span>
        </blockquote>
      )}
      <MessageContent content={message.content || (streaming ? "" : "…")} />
      <MessageImages images={images} onOpen={onOpenImage} />
      {hasVoice && <VoiceBar src={voiceSrc} durationMs={message.voiceDurationMs} />}
      {message.reasoning && (
        <CollapsibleBlock icon={<SparkIcon />} label={t("message.reasoning")}>
          {message.reasoning}
        </CollapsibleBlock>
      )}
      {calls.length > 0 ? (
        <ToolCallBlocks calls={calls} />
      ) : (
        message.toolName !== undefined && (
          <CollapsibleBlock
            icon={<ToolIcon />}
            label={`${t("message.toolCall")} · ${message.toolName}`}
            status={toolStatus === undefined ? undefined : t(TOOL_STATUS_KEY[toolStatus])}
            tone={toolStatus === undefined ? "neutral" : toolTone(toolStatus)}
          >
            {message.content || t("message.noParams")}
          </CollapsibleBlock>
        )
      )}
      {status === "sending" && (
        <p className="host-msg-status" role="status">
          <span className="host-msg-status-dot" aria-hidden="true" />
          {t("message.sending")}
        </p>
      )}
      {status === "failed" && (
        <div className="host-msg-failed" role="alert">
          <span className="host-msg-failed-text">{message.errorText !== undefined && message.errorText.length > 0 ? message.errorText : t("message.sendFailed")}</span>
          {onRetry !== undefined && (
            <button
              className="quiet host-retry"
              type="button"
              onClick={(event) => {
                event.stopPropagation();
                onRetry?.(message.id);
              }}
            >
              {t("common.retry")}
            </button>
          )}
        </div>
      )}
      {streaming && <span className="host-caret" aria-hidden="true" />}
      {showActions && !streaming && (
        <div className="host-bubble-actions">
          <button className="quiet" type="button" onClick={onRegenerate}>
            {t("message.regenerate")}
          </button>
          <button className="quiet" type="button" onClick={onContinue}>
            {t("message.continue")}
          </button>
        </div>
      )}
      {menuOpen && <MessageActionMenu actions={actions} onPick={pick} />}
    </article>
  );
}

/**
 * 消息组件外包一层 memo：比较函数只看真正影响渲染的字段（见 areBubblePropsEqual），
 * 长会话里一次快照只有流式那一条会重渲染。回调全部由上层用稳定函数传入（带 messageId），
 * 所以不在比较范围里——否则每次渲染新闭包会把 memo 全打穿。
 */
export const MessageBubble = memo(MessageBubbleView, areBubblePropsEqual);
