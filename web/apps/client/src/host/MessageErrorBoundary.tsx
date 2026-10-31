import { Component, type ErrorInfo, type ReactNode } from "react";
import { AlertIcon } from "../components/icons.js";
import { useI18n } from "../i18n/I18nProvider.js";

interface MessageGuardProps {
  /** 变化即重置错误态：Host 重发快照后同一条消息修好了，就该重新尝试渲染。 */
  readonly resetKey: string;
  readonly children: ReactNode;
}

interface MessageGuardState {
  readonly failed: boolean;
}

/** 错误态用函数组件写，才能拿到当前语言。 */
function MessageFallback() {
  const { t } = useI18n();
  return (
    <div className="host-msg-error" role="status">
      <AlertIcon />
      <span>{t("message.renderFailed")}</span>
    </div>
  );
}

/**
 * 单条消息的渲染隔离。
 *
 * 一条消息的内容把渲染路径打崩（异常字符、意外结构）时，只把这一条换成提示行，
 * 整个列表继续可用；否则一条坏消息会让聊天页整屏白掉，用户连别的记录都看不到。
 */
export class MessageErrorBoundary extends Component<MessageGuardProps, MessageGuardState> {
  override state: MessageGuardState = { failed: false };

  static getDerivedStateFromError(): MessageGuardState {
    return { failed: true };
  }

  override componentDidCatch(error: Error, info: ErrorInfo): void {
    // 不静默：这条消息为什么画不出来要留在控制台里。
    console.warn("message render failed", error, info.componentStack);
  }

  override componentDidUpdate(prev: MessageGuardProps): void {
    if (this.state.failed && prev.resetKey !== this.props.resetKey) this.setState({ failed: false });
  }

  override render(): ReactNode {
    if (this.state.failed) return <MessageFallback />;
    return this.props.children;
  }
}
