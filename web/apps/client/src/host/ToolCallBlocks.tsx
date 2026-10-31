import type { HostToolCall } from "../api.js";
import { ToolIcon } from "../components/icons.js";
import { useI18n } from "../i18n/I18nProvider.js";
import type { Translate } from "../i18n/translate.js";
import { CollapsibleBlock } from "./CollapsibleBlock.js";
import { formatToolDuration } from "./format.js";

function describe(call: HostToolCall, t: Translate): { readonly label: string; readonly tone: "neutral" | "accent" | "warn" } {
  const duration = formatToolDuration(call.durationMs);
  const withDuration = (text: string) => (duration === undefined ? text : `${text} · ${duration}`);
  if (call.success === true) return { label: withDuration(t("tool.success")), tone: "neutral" };
  if (call.success === false) return { label: withDuration(t("tool.failed")), tone: "warn" };
  return { label: withDuration(t("tool.started")), tone: "neutral" };
}

/**
 * 一条消息上的工具调用明细（可能是多个）。
 *
 * 每个调用一块折叠：标题行是工具名 + 成功与否 + 耗时，展开是参数预览和结果预览。
 * Host 显式给了 `success` / `durationMs` 就用它，不再靠消息级状态去猜。
 */
export function ToolCallBlocks({ calls }: { readonly calls: readonly HostToolCall[] }) {
  const { t } = useI18n();
  return (
    <>
      {calls.map((call, index) => {
        const status = describe(call, t);
        const hasArguments = (call.argumentsPreview ?? "").length > 0;
        const hasResult = (call.resultPreview ?? "").length > 0;
        return (
          <CollapsibleBlock
            key={`${call.callId.length > 0 ? call.callId : "call"}-${index}`}
            icon={<ToolIcon />}
            label={call.name.length > 0 ? call.name : t("message.toolCall")}
            status={status.label}
            tone={status.tone}
          >
            <div className="host-tool-fields">
              {hasArguments && (
                <div className="host-tool-field">
                  <span className="host-tool-field-label">{t("tool.args")}</span>
                  <p className="host-tool-field-body">{call.argumentsPreview}</p>
                </div>
              )}
              {hasResult && (
                <div className="host-tool-field">
                  <span className="host-tool-field-label">{t("tool.result")}</span>
                  <p className="host-tool-field-body">{call.resultPreview}</p>
                </div>
              )}
              {!hasArguments && !hasResult && <p className="host-tool-field-body">{t("tool.noPreview")}</p>}
            </div>
          </CollapsibleBlock>
        );
      })}
    </>
  );
}
