/**
 * S1：设置项元数据的读取口径（advanced / widget / dependsOn）。
 *
 * 这些判断的**依据**由 Host 下发（HostSettingDefinitions 是唯一的规则源），
 * 这里只负责「按依据渲染」。老 Host 不下发这些字段，因此每个函数都必须能处理缺失：
 * 缺失时退回与旧版一致的行为，而不是把行藏掉或换成一个更怪的控件。
 */
import type { HostSettingValue } from "../api.js";

/** 高级项：默认折叠。老 Host 不下发时按普通项处理。 */
export function isAdvanced(value: HostSettingValue): boolean {
  return value.advanced === true;
}

/** 控件名；Host 没给就按类型与范围派生，规则与服务端 widgetName() 一致。 */
export function widgetOf(value: HostSettingValue): string {
  if (typeof value.widget === "string" && value.widget.length > 0) return value.widget;
  switch (value.type) {
    case "boolean":
      return "switch";
    case "enum":
      return (value.options?.length ?? 0) >= 2 ? "select" : "text";
    case "number":
      return value.min !== undefined && value.max !== undefined && value.step !== undefined ? "slider" : "number";
    default:
      return "text";
  }
}

/**
 * 依赖是否满足。`valueOf(key)` 取当前快照里那一项的字符串值。
 *
 * 三种「拿不到」全部按**满足**处理：老 Host 不下发 dependsOn、依赖项没被下发、
 * 值还没同步到。把行藏起来的后果是用户以为功能不存在，比多显示一行严重得多。
 */
export function dependencyMet(value: HostSettingValue, valueOf: (key: string) => string | undefined): boolean {
  const dependsOn = value.dependsOn;
  if (dependsOn === undefined || dependsOn.key.length === 0) return true;
  const allowed = dependsOn.values ?? [];
  if (allowed.length === 0) return true;
  const current = valueOf(dependsOn.key);
  if (current === undefined) return true;
  return allowed.some((candidate) => candidate.toLowerCase() === current.toLowerCase());
}

/** 未满足时要在行里说什么：依赖项的标题（Host 已本地化）。 */
export function dependencyLabelKey(): string {
  return "settings.dependencyHint";
}

/** 按是否高级拆两组；保持 Host 给定的顺序，不重排。 */
export function splitAdvanced(values: readonly HostSettingValue[]): {
  readonly plain: readonly HostSettingValue[];
  readonly advanced: readonly HostSettingValue[];
} {
  const plain: HostSettingValue[] = [];
  const advanced: HostSettingValue[] = [];
  for (const value of values) {
    (isAdvanced(value) ? advanced : plain).push(value);
  }
  return { plain, advanced };
}
