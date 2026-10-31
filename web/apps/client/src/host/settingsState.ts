import type { HostSettingsSnapshot } from "../api.js";

/**
 * 设置快照的本地派生与乐观更新。
 *
 * 单独成模块是为了能脱离 WebSocket 单独验证：字段投影（写一份、三处同时生效）与
 * 回滚（把旧值原样写回）都是纯函数，Host 拒绝写入时的「恢复原状」就是再调一次 apply。
 */

/** 开关的当前值：条目上的 checked 与 toggles 列表任取其一（同一份数据两种投影）。 */
export function toggleValue(snapshot: HostSettingsSnapshot, key: string): boolean | undefined {
  for (const group of snapshot.groups) {
    for (const entry of group.entries) {
      if (entry.toggleKey === key && entry.checked !== undefined) return entry.checked;
    }
  }
  return snapshot.toggles.find((item) => item.key === key)?.enabled;
}

/** 一个键的当前值：优先 `values[]`，否则回退到旧的两处布尔投影。 */
export function settingValueOf(snapshot: HostSettingsSnapshot, key: string): unknown {
  const fromValues = snapshot.values?.find((item) => item.key === key);
  if (fromValues !== undefined) return fromValues.value;
  return toggleValue(snapshot, key);
}

/**
 * 乐观更新：**同时**改三处投影（values / toggles / 条目 checked），
 * 因为老主页只看后两处、新子页只看 `values`。Host 不回推快照，这是唯一的即时反馈；
 * 写入被拒时拿同一函数把旧值写回去，就完成了回滚。
 */
export function applySettingValue(snapshot: HostSettingsSnapshot, key: string, value: unknown): HostSettingsSnapshot {
  const values = snapshot.values;
  const asBoolean = typeof value === "boolean" ? value : undefined;
  return {
    ...snapshot,
    groups: snapshot.groups.map((group) => ({
      ...group,
      entries: group.entries.map((entry) =>
        entry.toggleKey === key && asBoolean !== undefined ? { ...entry, checked: asBoolean } : entry,
      ),
    })),
    toggles: snapshot.toggles.map((item) =>
      item.key === key && asBoolean !== undefined ? { ...item, enabled: asBoolean } : item,
    ),
    ...(values === undefined ? {} : { values: values.map((item) => (item.key === key ? { ...item, value } : item)) }),
  };
}
