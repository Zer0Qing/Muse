import { useEffect, useRef, useState } from "react";
import type { HostAssistantDetail, HostAssistantSummary, HostProviderSummary } from "../api.js";
import { CapsuleButton, CardGroup, ListRow, ListSkeleton, Screen, Switch, TopBar } from "../components/primitives.js";
import { formatNumber } from "../i18n/format.js";
import { useI18n } from "../i18n/I18nProvider.js";
import { ASSISTANT_DETAIL_FLAGS, supportsFlag } from "./featureCapabilities.js";
import { HostAvatar } from "./HostAvatar.js";

interface AssistantsPageProps {
  readonly assistants: readonly HostAssistantSummary[];
  readonly providers: readonly HostProviderSummary[];
  readonly details: Readonly<Record<string, HostAssistantDetail>>;
  /** Host 声明的能力位（undefined = 老 Host，先试一次）。 */
  readonly flags: readonly string[] | undefined;
  /** 首屏快照还没到。 */
  readonly loading: boolean;
  /** Host 明确回了「不支持详情」。 */
  readonly detailUnsupported: boolean;
  readonly onRequestDetail: (assistantId: string) => void;
  readonly onRequestProviders: () => void;
  readonly onStartSession: (assistantId: string) => void;
  readonly onBack: () => void;
}

/** 人设摘要超过 6 行折起来，按钮展开——这里只记展开状态。 */
function PersonaSummary({ text }: { readonly text: string }) {
  const { t } = useI18n();
  const [open, setOpen] = useState(false);
  return (
    <>
      <p className={open ? "host-persona is-open" : "host-persona"}>{text}</p>
      <button className="quiet host-persona-toggle" type="button" onClick={() => setOpen((current) => !current)}>
        {open ? t("assistants.collapse") : t("assistants.expand")}
      </button>
    </>
  );
}

function value(value: string | number | undefined, empty: string): string {
  if (value === undefined) return empty;
  if (typeof value === "number") return formatNumber(value, "en", { maximumFractionDigits: 2 });
  return value.length > 0 ? value : empty;
}

/**
 * 助手与角色页：列表 → 详情。
 *
 * 详情里除了人设摘要/参数/提示词长度/工具数，只有一个「用这个助手开始会话」的动作；
 * 启用开关是**只读**的——协议里没有写回命令，做一个点了没反应的开关比不给更糟。
 */
export function AssistantsPage(props: AssistantsPageProps) {
  const { t } = useI18n();
  const assist = props.assistants;
  const providers = props.providers;
  const details = props.details;
  const { loading, detailUnsupported, onRequestDetail, onRequestProviders, onStartSession, onBack } = props;
  const [selectedId, setSelectedId] = useState<string | undefined>(undefined);
  const requested = useRef(new Set<string>());
  const supportsDetail = supportsFlag(props.flags, ASSISTANT_DETAIL_FLAGS) && !detailUnsupported;

  // 列表进来就给每个助手拉一次详情：模型名只有详情里有。重复请求靠 requested 去重。
  useEffect(() => {
    if (!supportsDetail) return;
    onRequestProviders();
    for (const assistant of assist) {
      if (details[assistant.id] !== undefined) continue;
      if (requested.current.has(assistant.id)) continue;
      requested.current.add(assistant.id);
      onRequestDetail(assistant.id);
    }
  }, [supportsDetail, assist, details, onRequestDetail, onRequestProviders]);

  const modelNameOf = (detail: HostAssistantDetail | undefined): string | undefined => {
    if (detail === undefined || detail.modelId === undefined) return undefined;
    const provider = providers.find((item) => item.id === detail.providerId);
    return provider?.models.find((model) => model.id === detail.modelId)?.name ?? detail.modelId;
  };

  const selected = assist.find((assistant) => assistant.id === selectedId);

  if (selected !== undefined) {
    return (
      <AssistantDetailView
        assistant={selected}
        detail={details[selected.id]}
        modelName={modelNameOf(details[selected.id])}
        supported={supportsDetail}
        onStartSession={onStartSession}
        onBack={() => setSelectedId(undefined)}
      />
    );
  }

  return (
    <Screen fill topBar={<TopBar title={t("assistants.pageTitle")} largeTitle onBack={onBack} />}>
      {loading && assist.length === 0 ? (
        <ListSkeleton label={t("assistants.loading")} />
      ) : (
        <div className="muse-stack">
          <CardGroup>
            {assist.length === 0 ? (
              <ListRow title={t("assistants.listEmpty")} divider={false} />
            ) : (
              assist.map((assistant, index) => {
                const modelName = modelNameOf(details[assistant.id]);
                return (
                  <ListRow
                    key={assistant.id}
                    title={assistant.name}
                    {...(modelName === undefined ? {} : { subtitle: modelName })}
                    leading={<HostAvatar name={assistant.name} uri={assistant.avatarUri} />}
                    trailing={assistant.isDefault === true ? <span className="host-picker-badge">{t("assistants.default")}</span> : undefined}
                    chevron
                    divider={index !== assist.length - 1}
                    onClick={() => setSelectedId(assistant.id)}
                  />
                );
              })
            )}
          </CardGroup>
        </div>
      )}
    </Screen>
  );
}

/** 详情页（单独导出：没有交互也能直接渲染断言）。 */
export function AssistantDetailView({
  assistant,
  detail,
  modelName,
  supported,
  onStartSession,
  onBack,
}: {
  readonly assistant: HostAssistantSummary;
  readonly detail: HostAssistantDetail | undefined;
  readonly modelName: string | undefined;
  readonly supported: boolean;
  readonly onStartSession: (assistantId: string) => void;
  readonly onBack: () => void;
}) {
  const { t } = useI18n();
  return (
    <Screen fill topBar={<TopBar title={assistant.name} largeTitle onBack={onBack} />}>
      <div className="muse-stack">
        <div className="host-assistant-head">
          <HostAvatar name={assistant.name} uri={assistant.avatarUri} size="large" />
          <div className="host-assistant-head-text">
            <h2>{assistant.name}</h2>
            {assistant.isDefault === true && <span className="host-picker-badge">{t("assistants.default")}</span>}
            <p>{detail?.description !== undefined && detail.description.length > 0 ? detail.description : t("assistants.noDescription")}</p>
          </div>
        </div>

        {!supported ? (
          <CardGroup>
            <ListRow title={t("assistants.unsupported")} divider={false} />
          </CardGroup>
        ) : detail === undefined ? (
          <ListSkeleton groups={2} rows={2} label={t("assistants.detailLoading")} />
        ) : (
          <>
            {detail.personaSummary !== undefined && detail.personaSummary.length > 0 && (
              <CardGroup title={t("assistants.persona")}>
                <ListRow title={<PersonaSummary text={detail.personaSummary} />} divider={false} />
              </CardGroup>
            )}
            <CardGroup title={t("assistants.params")}>
              <ListRow title={t("assistants.model")} trailing={<span className="host-setting-value">{modelName ?? t("assistants.notSet")}</span>} divider />
              <ListRow
                title={t("assistants.temperature")}
                trailing={<span className="host-setting-value">{value(detail.temperature, t("assistants.notSet"))}</span>}
                divider
              />
              <ListRow title={t("assistants.topP")} trailing={<span className="host-setting-value">{value(detail.topP, t("assistants.notSet"))}</span>} divider />
              <ListRow
                title={t("assistants.maxTokens")}
                trailing={<span className="host-setting-value">{value(detail.maxTokens, t("assistants.notSet"))}</span>}
                divider
              />
              <ListRow
                title={t("assistants.contextSize")}
                trailing={<span className="host-setting-value">{value(detail.contextMessageSize, t("assistants.notSet"))}</span>}
                divider
              />
              <ListRow
                title={t("assistants.reasoning")}
                trailing={<span className="host-setting-value">{value(detail.reasoningLevel, t("assistants.notSet"))}</span>}
                divider={false}
              />
            </CardGroup>
            <CardGroup>
              <ListRow
                title={t("assistants.prompt")}
                trailing={
                  <span className="host-setting-value">
                    {t("assistants.promptConfigured", { count: formatNumber(detail.systemPromptLength ?? 0, "en") })}
                  </span>
                }
                divider
              />
              <ListRow
                title={t("assistants.tools")}
                trailing={<span className="host-setting-value">{t("assistants.toolCount", { count: formatNumber(detail.toolCount ?? 0, "en") })}</span>}
                divider
              />
              <ListRow
                title={t("assistants.enabled")}
                subtitle={t("assistants.enabledReadOnly")}
                trailing={<Switch checked={detail.enabled === true} label={t("assistants.enabled")} disabled onChange={() => undefined} />}
                divider={false}
              />
            </CardGroup>
            <div className="host-assistant-actions">
              <CapsuleButton onClick={() => onStartSession(assistant.id)}>{t("assistants.startSession")}</CapsuleButton>
            </div>
          </>
        )}
      </div>
    </Screen>
  );
}
