import type { HostSettingsSnapshot } from "../api.js";
import { humanizeSettingKey, pageForValueKey, resolveValueTitle, type SettingsPageSpec } from "./settingsPages.js";

/**
 * 设置搜索：只用已经拿到的 `settings.snapshot` 本地过滤，不发任何请求。
 *
 * 参与匹配的字段：条目名、分类名、条目说明、值行标题、值所属页标题、原始设置键。
 * 大小写不敏感，中文按子串匹配（不做分词）。拼音首字母没做——那需要一张几千字的
 * 汉字→拼音表，本端不许新增依赖，也不该把这种会过时的数据表塞进界面代码。
 */
export type SettingsSearchTarget =
  | { readonly kind: "entry"; readonly entryId: string }
  | { readonly kind: "value"; readonly valueKey: string; readonly pageId: SettingsPageSpec["id"] };

export interface SettingsSearchHit {
  readonly id: string;
  readonly title: string;
  /** 两级面包屑：分类 › 条目 / 子页 › 设置项。 */
  readonly breadcrumb: readonly string[];
  readonly target: SettingsSearchTarget;
}

export interface SettingsSearchRow {
  readonly id: string;
  readonly title: string;
  readonly breadcrumb: readonly string[];
  readonly target: SettingsSearchTarget;
  /** 参与匹配的字段（已小写）。 */
  readonly fields: readonly string[];
}

/** 值所属子页的标题：目录里有同名条目的就用它的标题，没有再从页 id 派生。 */
export function settingsPageTitle(snapshot: HostSettingsSnapshot, page: SettingsPageSpec): string {
  for (const group of snapshot.groups) {
    for (const entry of group.entries) {
      if (page.entryIds.includes(entry.id)) return entry.title;
    }
  }
  return humanizeSettingKey(page.id);
}

/** toggleKey → 目录标题，用于把值行标题与子页里显示的保持一致。 */
function titledSettingKeys(snapshot: HostSettingsSnapshot): Map<string, string> {
  const map = new Map<string, string>();
  for (const group of snapshot.groups) {
    for (const entry of group.entries) {
      if (entry.toggleKey !== undefined) map.set(entry.toggleKey, entry.title);
    }
  }
  return map;
}

/**
 * 建索引：可进入的目录条目（link / action，且未禁用）+ 全部值行。
 * 开关条目（kind=switch）不进索引——它们本来就住在分类页上，点开也没有去处。
 */
export function buildSettingsSearchRows(snapshot: HostSettingsSnapshot): readonly SettingsSearchRow[] {
  const rows: SettingsSearchRow[] = [];
  for (const group of snapshot.groups) {
    for (const entry of group.entries) {
      const navigable = (entry.kind === "link" || entry.kind === "action") && entry.enabled !== false;
      if (!navigable) continue;
      rows.push({
        id: `entry:${group.key}:${entry.id}`,
        title: entry.title,
        breadcrumb: [group.groupTitle, entry.title],
        target: { kind: "entry", entryId: entry.id },
        fields: [entry.title, group.groupTitle, entry.desc ?? ""].map((field) => field.toLowerCase()),
      });
    }
  }

  const titles = titledSettingKeys(snapshot);
  for (const value of snapshot.values ?? []) {
    const page = pageForValueKey(value.key);
    if (page === undefined) continue; // 归不到任何子页的值没有去处，不塞进结果
    const pageTitle = settingsPageTitle(snapshot, page);
    const title = resolveValueTitle(value, titles);
    rows.push({
      id: `value:${value.key}`,
      title,
      breadcrumb: [pageTitle, title],
      target: { kind: "value", valueKey: value.key, pageId: page.id },
      fields: [title, pageTitle, value.key].map((field) => field.toLowerCase()),
    });
  }

  return rows;
}

/** 本地过滤：标题命中排在面包屑 / 键命中前面，其余保持快照顺序。 */
export function searchSettings(snapshot: HostSettingsSnapshot, query: string): readonly SettingsSearchHit[] {
  const needle = query.trim().toLowerCase();
  if (needle.length === 0) return [];
  const scored: { readonly row: SettingsSearchRow; readonly score: number }[] = [];
  for (const row of buildSettingsSearchRows(snapshot)) {
    if (!row.fields.some((field) => field.includes(needle))) continue;
    scored.push({ row, score: row.title.toLowerCase().includes(needle) ? 3 : 1 });
  }
  // sort 在 ES2019+ 是稳定的：同分保持快照顺序，不做多余的排序魔法。
  scored.sort((left, right) => right.score - left.score);
  return scored.map(({ row }) => ({
    id: row.id,
    title: row.title,
    breadcrumb: row.breadcrumb,
    target: row.target,
  }));
}

/** 面包屑文案：分类 › 条目。 */
export function breadcrumbText(hit: SettingsSearchHit): string {
  return hit.breadcrumb.join(" › ");
}
