import { Component, Fragment, type ErrorInfo, type ReactNode } from "react";
import { AlertIcon } from "../components/icons.js";
import { useI18n } from "../i18n/I18nProvider.js";

interface BlockBoundaryProps {
  /** 这块是什么（提示文案里点名）；由调用方按当前语言给好。 */
  readonly name: string;
  readonly children: ReactNode;
}

interface BlockBoundaryState {
  readonly failed: boolean;
  /** 递增即重挂载子树：这就是「重新加载这一块」的实现。 */
  readonly nonce: number;
}

/** 错误态用函数组件写，才能拿到当前语言（class 里不能用 hook）。 */
function BlockFallback({ name, onRetry }: { readonly name: string; readonly onRetry: () => void }) {
  const { t } = useI18n();
  return (
    <div className="host-block-error" role="alert">
      <AlertIcon />
      <p className="host-block-error-text">{t("block.failed", { name })}</p>
      <button className="quiet host-block-retry" type="button" onClick={onRetry}>
        {t("block.retry")}
      </button>
    </div>
  );
}

/**
 * 页面级错误边界：某一块渲染炸了，只把那一块换成一行提示 + 重新加载，
 * 其余部分（侧栏、输入区、其他卡片）继续可用。
 *
 * 和 MessageErrorBoundary 的分工：那个是逐条消息的隔离，这个是更大的区块
 * （消息列表、会话列表、设置页）的隔离。
 */
export class BlockBoundary extends Component<BlockBoundaryProps, BlockBoundaryState> {
  override state: BlockBoundaryState = { failed: false, nonce: 0 };

  static getDerivedStateFromError(): Partial<BlockBoundaryState> {
    return { failed: true };
  }

  override componentDidCatch(error: Error, info: ErrorInfo): void {
    console.warn(`${this.props.name} render failed`, error, info.componentStack);
  }

  private readonly retry = () => {
    this.setState((current) => ({ failed: false, nonce: current.nonce + 1 }));
  };

  override render(): ReactNode {
    if (this.state.failed) return <BlockFallback name={this.props.name} onRetry={this.retry} />;
    return <Fragment key={this.state.nonce}>{this.props.children}</Fragment>;
  }
}
