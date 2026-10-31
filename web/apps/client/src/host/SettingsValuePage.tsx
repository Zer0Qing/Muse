import { useState } from "react";
import type { HostSettingOption, HostSettingValue, HostSettingsSnapshot } from "../api.js";
import { CheckIcon } from "../components/icons.js";
import { CardGroup, ErrorStateBox, ListRow, ListSkeleton, Screen, Switch, TopBar } from "../components/primitives.js";
import type { HostCapabilities } from "./capabilities.js";
import {
  isReadOnlyKey,
  readBoolean,
  readNumber,
  readText,
  resolveValueTitle,
  stepNumber,
  valuesForPage,
  type SettingsPageSpec,
} from "./settingsPages.js";
import { useI18n } from "../i18n/I18nProvider.js";
import { useDelayedFlag } from "./useDelayedFlag.js";

export interface SettingsValuePageProps {
  readonly page: SettingsPageSpec;
  /** 页面标题：被点开的目录条目的 Host 本地化标题。 */
  readonly title: string;
  readonly snapshot: HostSettingsSnapshot | undefined;
  readonly loading: boolean;
  readonly error: string | undefined;
  readonly capabilities: HostCapabilities;
  readonly onBack: () => void;
  readonly onRetry: () => void;
  readonly onWrite: (key: string, value: string | number | boolean) => void;
  /** 从搜索结果直达时要高亮的那一行（值的 key）。 */
  readonly focusKey?: string | undefined;
}

/** toggleKey → 目录条目，用来拿 Host 已本地化的标题 / 说明。 */
function titledEntries(snapshot: HostSettingsSnapshot): Map<string, { title: string; desc?: string | undefined }> {
  const map = new Map<string, { title: string; desc?: string | undefined }>();
  for (const group of snapshot.groups) {
    for (const entry of group.entries) {
      if (entry.toggleKey === undefined) continue;
      map.set(entry.toggleKey, { title: entry.title, desc: entry.desc });
    }
  }
  return map;
}

/** 行标题：目录标题 → Host 若下发 label → 键派生（与搜索索引同一份逻辑）。 */
function rowInfo(
  value: HostSettingValue,
  entries: Map<string, { title: string; desc?: string | undefined }>,
  titles: ReadonlyMap<string, string>,
): { title: string; subtitle?: string | undefined } {
  const fromEntry = entries.get(value.key);
  const title = resolveValueTitle(value, titles);
  return fromEntry === undefined ? { title } : { title, subtitle: fromEntry.desc };
}

/**
 * 设置子页。
 *
 * 行 = Host `values[]` 里归到本页的那些（顺序照 Host 给，不重排）：
 * boolean → Switch、number → −/＋、enum → 可展开单选、text → 只读行。
 * 所有写入都是「先本地改、再下发」，失败回滚与原因提示在 useHostConnection 里统一处理。
 */
export function SettingsValuePage({
  page,
  title,
  snapshot,
  loading,
  error,
  capabilities,
  onBack,
  onRetry,
  onWrite,
  focusKey,
}: SettingsValuePageProps) {
  const { t } = useI18n();
  const topBar = <TopBar title={title} largeTitle onBack={onBack} />;
  const skeletonDue = useDelayedFlag(loading && snapshot === undefined);

  if (snapshot === undefined) {
    return (
      <Screen fill topBar={topBar}>
        {error !== undefined ? (
          <ErrorStateBox message={error} onRetry={onRetry} />
        ) : skeletonDue ? (
          <ListSkeleton label={t("settings.loading")} />
        ) : (
          <div className="host-loading-quiet" role="status" aria-label={t("settings.loading")} />
        )}
      </Screen>
    );
  }

  // 明确没有 settings_values 能力位，或这份 Host 压根不下发 values：说清楚，不给空列表。
  if (!capabilities.settingsValues || snapshot.values === undefined) {
    return (
      <Screen fill topBar={topBar}>
        <div className="muse-stack">
          <CardGroup>
            <ListRow
              title={t("settings.unsupported")}
              subtitle={t("settings.unsupportedBody")}
              divider={false}
            />
          </CardGroup>
        </div>
      </Screen>
    );
  }

  const entries = titledEntries(snapshot);
  const rows = valuesForPage(page, snapshot.values);

  return (
    <Screen fill topBar={topBar}>
      <div className="muse-stack">
        {rows.length === 0 ? (
          <CardGroup>
            <ListRow title={t("settings.emptyPage")} subtitle={t("settings.emptyPageBody")} divider={false} />
          </CardGroup>
        ) : (
          <SettingsValueCard page={page} snapshot={snapshot} onWrite={onWrite} focusKey={focusKey} />
        )}
      </div>
    </Screen>
  );
}

/**
 * 一页的可调项卡片：独立的组件，供设置子页和「记忆页」这类混合页复用。
 * 没有匹配到任何值时返回 null，由调用方决定空态怎么画。
 */
export function SettingsValueCard({
  page,
  snapshot,
  onWrite,
  focusKey,
}: {
  readonly page: SettingsPageSpec;
  readonly snapshot: HostSettingsSnapshot;
  readonly onWrite: (key: string, value: string | number | boolean) => void;
  readonly focusKey?: string | undefined;
}) {
  const entries = titledEntries(snapshot);
  const titles = new Map(Array.from(entries, ([key, info]) => [key, info.title] as const));
  const rows = valuesForPage(page, snapshot.values);
  if (rows.length === 0) return null;
  return (
    <CardGroup>
      {rows.map((value, index) => {
        const row = (
          <ValueRow
            key={value.key}
            value={value}
            last={index === rows.length - 1}
            info={rowInfo(value, entries, titles)}
            onWrite={onWrite}
          />
        );
        // 从搜索直达：把目标行标出来，否则用户进来还得自己找。
        return focusKey !== undefined && value.key === focusKey ? (
          <div className="host-setting-highlight" key={value.key}>
            {row}
          </div>
        ) : (
          row
        );
      })}
    </CardGroup>
  );
}

interface ValueRowProps {
  readonly value: HostSettingValue;
  readonly last: boolean;
  readonly info: { readonly title: string; readonly subtitle?: string | undefined };
  readonly onWrite: (key: string, value: string | number | boolean) => void;
}

function ValueRow({ value, last, info, onWrite }: ValueRowProps) {
  const { t } = useI18n();
  const text = {
    title: info.title,
    ...(info.subtitle === undefined || info.subtitle === "" ? {} : { subtitle: info.subtitle }),
  };

  // 只读键：回显状态，不给任何写入口。
  if (isReadOnlyKey(value.key)) {
    if (value.type === "boolean") {
      return <ListRow {...text} divider={!last} trailing={<Switch checked={readBoolean(value.value)} disabled label={info.title} onChange={() => undefined} />} />;
    }
    return (
      <ListRow
        {...text}
        divider={!last}
        trailing={<span className="host-setting-value">{valueText(value)}</span>}
      />
    );
  }

  if (value.type === "boolean") {
    return (
      <ListRow
        {...text}
        divider={!last}
        trailing={
          <Switch
            checked={readBoolean(value.value)}
            label={info.title}
            onChange={(next) => onWrite(value.key, next)}
          />
        }
      />
    );
  }

  if (value.type === "number") {
    const current = readNumber(value.value);
    return (
      <ListRow
        {...text}
        divider={!last}
        trailing={
          <span className="host-stepper">
            <button
              className="quiet host-stepper-button"
              type="button"
              aria-label={t("settings.decrease", { title: info.title })}
              onClick={() => onWrite(value.key, stepNumber(current, -1))}
            >
              −
            </button>
            <span className="host-stepper-value">{String(current)}</span>
            <button
              className="quiet host-stepper-button"
              type="button"
              aria-label={t("settings.increase", { title: info.title })}
              onClick={() => onWrite(value.key, stepNumber(current, 1))}
            >
              ＋
            </button>
          </span>
        }
      />
    );
  }

  if (value.type === "enum") {
    return <EnumRow value={value} last={last} info={info} onWrite={onWrite} />;
  }

  return (
    <ListRow {...text} divider={!last} trailing={<span className="host-setting-value">{valueText(value)}</span>} />
  );
}

/** 枚举行：点行展开选项，选中项打勾；标签一律用 Host 下发的 option.label。 */
function EnumRow({ value, last, info, onWrite }: ValueRowProps) {
  const [open, setOpen] = useState(false);
  const options: readonly HostSettingOption[] = value.options ?? [];
  const current = readText(value.value);
  const selected = options.find((option) => option.value === current);
  const canChoose = options.length > 0;

  const text = {
    title: info.title,
    ...(info.subtitle === undefined || info.subtitle === "" ? {} : { subtitle: info.subtitle }),
  };

  return (
    <>
      <ListRow
        {...text}
        divider={canChoose || !last}
        chevron={canChoose}
        trailing={<span className="host-setting-value">{selected?.label ?? (current.length > 0 ? current : "—")}</span>}
        {...(canChoose ? { onClick: () => setOpen((previous) => !previous) } : {})}
      />
      {open &&
        options.map((option, index) => (
          <ListRow
            key={option.value}
            title={option.label}
            divider={index < options.length - 1 || !last}
            trailing={
              option.value === current ? (
                <span className="host-setting-check">
                  <CheckIcon size={18} />
                </span>
              ) : undefined
            }
            onClick={() => {
              setOpen(false);
              if (option.value !== current) onWrite(value.key, option.value);
            }}
          />
        ))}
    </>
  );
}

function valueText(value: HostSettingValue): string {
  const raw = readText(value.value);
  const option = value.options?.find((candidate) => candidate.value === raw);
  if (option !== undefined) return option.label;
  return raw.length > 0 ? raw : "—";
}
