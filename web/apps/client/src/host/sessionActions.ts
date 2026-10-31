/**
 * 会话操作的纯逻辑：重命名校验、删除确认文案、归档可见性、新建会话要不要先选助手。
 *
 * 抽出来是为了能脱离 DOM 断言这些规则（尤其是「空名不允许」和删除文案里
 * 必须写清「会话与全部消息」）。
 */
export interface SessionLike {
  readonly id: string;
  readonly title: string;
  readonly archived: boolean;
}

/** 重命名：只有空白也算空，不允许提交。 */
export function isRenameValid(draft: string): boolean {
  return draft.trim().length > 0;
}

/** 提交前把首尾空白去掉、中间连续空白压成一个空格。 */
export function normalizeSessionTitle(draft: string): string {
  return draft.trim().replace(/\s+/g, " ");
}

/** 归档会话默认隐藏，打开「显示归档」才出现。 */
export function visibleSessions<T extends { readonly archived: boolean }>(
  sessions: readonly T[],
  showArchived: boolean,
): readonly T[] {
  return showArchived ? sessions : sessions.filter((session) => !session.archived);
}

/** 助手多于一个才需要先选；一个或零个（还没读到）都直接建。 */
export function shouldAskAssistant(assistants: readonly { readonly id: string }[]): boolean {
  return assistants.length > 1;
}

/** 只有一个助手时把它带上；否则交给 Host 用默认助手。 */
export function assistantIdForNewSession(assistants: readonly { readonly id: string }[]): string | undefined {
  return assistants.length === 1 ? assistants[0]?.id : undefined;
}
