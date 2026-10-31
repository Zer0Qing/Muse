import { CapsuleButton, CardGroup, ListRow, Screen, TopBar } from "../components/primitives.js";
import { useI18n } from "../i18n/I18nProvider.js";

interface HostPlaceholderProps {
  /** 页面标题：Host 已本地化的条目标题。 */
  readonly title: string;
  /** 这个功能是做什么的：优先用 Host 下发的条目说明；没有就用本端的一句中性话。 */
  readonly description?: string | undefined;
  readonly onBack: () => void;
}

/**
 * 统一占位页。
 *
 * 两种页面落到这里：还没迁移到网页端的设置子页，以及只能在手机上操作的能力
 * （无障碍、虚拟屏、终端、通知监听、UI 自动化……）。只说三件事——
 * 「这个功能需要在手机上操作」、这个功能是做什么的、怎么返回。
 * 不放任何假按钮：没有实现的入口就不假装能点。
 */
export function HostPlaceholder({ title, description, onBack }: HostPlaceholderProps) {
  const { t } = useI18n();
  const desc = description !== undefined && description.length > 0 ? description : t("settings.placeholderBody");
  return (
    <Screen fill topBar={<TopBar title={title} largeTitle onBack={onBack} />}>
      <div className="muse-stack">
        <CardGroup>
          <ListRow title={t("settings.placeholderTitle")} subtitle={desc} divider={false} />
        </CardGroup>
        <div className="host-placeholder-actions">
          <CapsuleButton variant="secondary" fillWidth={false} onClick={onBack}>
            {t("common.back")}
          </CapsuleButton>
        </div>
      </div>
    </Screen>
  );
}
