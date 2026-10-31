import { CardGroup, ListRow, Screen, TopBar } from "../components/primitives.js";
import { useI18n } from "../i18n/I18nProvider.js";
import type { PlatformAvailability } from "./featureCapabilities.js";

interface PlatformPageProps {
  /** Host 已本地化的条目标题。 */
  readonly title: string;
  /** 这个功能是做什么的：优先用 Host 下发的条目说明。 */
  readonly description?: string | undefined;
  readonly availability: PlatformAvailability;
  readonly onBack: () => void;
}

/**
 * 平台专有页（无障碍 / 虚拟屏 / 终端 / 通知监听 / UI 自动化）。
 *
 * 只展示三件事：这个功能是做什么的、当前可用性（类型来自 system.status.capabilities）、
 * 以及「只能在手机上开启」。不可用时也只说明情况——不给「去设置」按钮，
 * 因为那是手机的设置，网页点不了。
 */
export function PlatformPage({ title, description, availability, onBack }: PlatformPageProps) {
  const { t } = useI18n();
  const label =
    availability === "available"
      ? t("platform.available")
      : availability === "notEnabled"
        ? t("platform.notEnabled")
        : t("platform.unavailable");
  const desc = description !== undefined && description.length > 0 ? description : t("platform.readOnly");

  return (
    <Screen fill topBar={<TopBar title={title} largeTitle onBack={onBack} />}>
      <div className="muse-stack">
        <CardGroup>
          <ListRow title={desc} divider={false} />
        </CardGroup>
        <CardGroup title={t("platform.status")}>
          <ListRow
            title={label}
            subtitle={availability === "available" ? undefined : t("platform.enableOnPhone")}
            trailing={<span className={`host-platform-state is-${availability}`} aria-hidden="true" />}
            divider
          />
          <ListRow title={t("platform.readOnly")} divider={false} />
        </CardGroup>
      </div>
    </Screen>
  );
}
