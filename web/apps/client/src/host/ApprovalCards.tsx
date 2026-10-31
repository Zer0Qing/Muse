import { ShieldIcon } from "../components/icons.js";
import { useI18n } from "../i18n/I18nProvider.js";
import type { HostApproval } from "./useHostConnection.js";

interface ApprovalCardsProps {
  readonly approvals: readonly HostApproval[];
  readonly onResolve: (toolCallId: string, decision: "approved" | "denied") => void;
}

export function ApprovalCards({ approvals, onResolve }: ApprovalCardsProps) {
  const { t } = useI18n();
  if (approvals.length === 0) return null;
  return (
    <div className="host-approvals" role="group" aria-label={t("approval.groupAria")}>
      {approvals.map((approval) => (
        <section className="host-approval" key={approval.toolCallId}>
          <div className="host-approval-head">
            <ShieldIcon />
            <span>{t("approval.needsConfirm")}</span>
            <code>{approval.toolName}</code>
          </div>
          <pre>{approval.argumentsPreview || t("message.noParams")}</pre>
          <div className="host-approval-actions">
            <button className="quiet host-deny" type="button" onClick={() => onResolve(approval.toolCallId, "denied")}>
              {t("approval.deny")}
            </button>
            <button type="button" className="host-approve" onClick={() => onResolve(approval.toolCallId, "approved")}>
              {t("approval.approve")}
            </button>
          </div>
        </section>
      ))}
    </div>
  );
}
