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
import { dependencyMet, splitAdvanced, widgetOf } from "./settingsMetadata.js";
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
  const { t } = useI18n();
  const entries = titledEntries(snapshot);
  const titles = new Map(Array.from(entries, ([key, info]) => [key, info.title] as const));
  const rows = valuesForPage(page, snapshot.values);
  if (rows.length === 0) return null;

  // S1：依赖判断要读其它项的当前值，而其它项可能不属于本页 —— 所以在整份 values 里找。
  const allValues = snapshot.values ?? [];
  const valueByKey = new Map(allValues.map((item) => [item.key, item] as const));
  const valueOf = (key: string): string | undefined => {
    const found = valueByKey.get(key);
    return found === undefined ? undefined : String(found.value);
  };
  // 标题先用本页已渲染的行（Host 本地化过），再退回整份 values 派生，最后才是裸键名。
  const rowTitles = new Map(rows.map((row) => [row.key, resolveValueTitle(row, titles)] as const));
  const blockedByOf = (value: HostSettingValue): string | undefined => {
    const dependsOn = value.dependsOn;
    if (dependsOn === undefined || dependencyMet(value, valueOf)) return undefined;
    const known = rowTitles.get(dependsOn.key);
    if (known !== undefined && known.length > 0) return known;
    const elsewhere = valueByKey.get(dependsOn.key);
    const resolved = elsewhere === undefined ? "" : resolveValueTitle(elsewhere, titles);
    return resolved.length > 0 ? resolved : dependsOn.key;
  };

  const { plain, advanced } = splitAdvanced(rows);
  // 搜索直达命中高级项时必须自动展开，否则「搜到 → 点进去 → 找不到」等于没搜到。
  const [showAdvanced, setShowAdvanced] = useState(advanced.some((value) => value.key === focusKey));

  const renderRow = (value: HostSettingValue, last: boolean) => {
    const row = (
      <ValueRow
        key={value.key}
        value={value}
        last={last}
        info={rowInfo(value, entries, titles)}
        blockedBy={blockedByOf(value)}
        onWrite={onWrite}
      />
    );
    return focusKey !== undefined && value.key === focusKey ? (
      <div className="host-setting-highlight" key={value.key}>
        {row}
      </div>
    ) : (
      row
    );
  };

  return (
    <CardGroup>
      {plain.map((value, index) => renderRow(value, advanced.length === 0 && index === plain.length - 1))}
      {advanced.length > 0 && (
        <>
          <ListRow
            title={t("settings.advanced", { count: advanced.length })}
            subtitle={showAdvanced ? t("settings.advancedOpen") : t("settings.advancedClosed")}
            divider={showAdvanced}
            trailing={
              <button
                type="button"
                className="host-advanced-toggle"
                aria-expanded={showAdvanced}
                onClick={() => setShowAdvanced((current) => !current)}
              >
                {showAdvanced ? t("settings.advancedHide") : t("settings.advancedShow")}
              </button>
            }
          />
          {showAdvanced &&
            advanced.map((value, index) => renderRow(value, index === advanced.length - 1))}
        </>
      )}
    </CardGroup>
  );
}

interface ValueRowProps {
  readonly value: HostSettingValue;
  readonly last: boolean;
  readonly info: { readonly title: string; readonly subtitle?: string | undefined };
  /**
   * S1：依赖还没满足时，这里是被依赖项的标题。
   *
   * 处理方式是**照常显示这一行、只回显值不给写入口**，而不是把行藏掉：
   * 藏起来的后果是用户以为这个功能不存在，而他搜得到它、别人也在讨论它。
   */
  readonly blockedBy?: string | undefined;
  readonly onWrite: (key: string, value: string | number | boolean) => void;
}

function ValueRow({ value, last, info, blockedBy, onWrite }: ValueRowProps) {
  const { t } = useI18n();
  const text = {
    title: info.title,
    ...(info.subtitle === undefined || info.subtitle === "" ? {} : { subtitle: info.subtitle }),
  };

  // S1：依赖未满足。说清「要先打开哪一项」，并且不给写入口 ——
  // 留一个能点但点了没反应的开关，比不给开关更坏。
  if (blockedBy !== undefined) {
    return (
      <ListRow
        title={text.title}
        subtitle={t("settings.dependencyHint", { title: blockedBy })}
        divider={!last}
        trailing={<span className="host-setting-value">{valueText(value)}</span>}
      />
    );
  }

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
    // S1：有完整范围与步进的数字项按元数据画滑杆；其余仍是 −/＋。
    // 控件由元数据决定，所以 Host 新加一项时不必再改一次前端。
    if (widgetOf(value) === "slider" && value.min !== undefined && value.max !== undefined) {
      const bounded = Number.isFinite(current) ? Math.min(Math.max(current, value.min), value.max) : value.min;
      return (
        <ListRow
          {...text}
          divider={!last}
          trailing={
            <span className="host-slider-wrap">
              <input
                className="host-slider"
                type="range"
                min={value.min}
                max={value.max}
                step={value.step ?? 1}
                value={bounded}
                aria-label={info.title}
                onChange={(event) => onWrite(value.key, Number(event.target.value))}
              />
              <span className="host-slider-value">{String(bounded)}</span>
            </span>
          }
        />
      );
    }
    const clamp = (next: number): number => {
      // 步进按钮以前只管下限 0，元数据里的 min/max 根本不参与 —— 于是可以一路加出范围，
      // 提交后被 Host 拒绝，看起来像「这个设置坏了」。
      const withMin = value.min === undefined ? next : Math.max(value.min, next);
      return value.max === undefined ? withMin : Math.min(value.max, withMin);
    };
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
              onClick={() => onWrite(value.key, clamp(stepNumber(current, -1)))}
            >
              −
            </button>
            <span className="host-stepper-value">{String(current)}</span>
            <button
              className="quiet host-stepper-button"
              type="button"
              aria-label={t("settings.increase", { title: info.title })}
              onClick={() => onWrite(value.key, clamp(stepNumber(current, 1)))}
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
