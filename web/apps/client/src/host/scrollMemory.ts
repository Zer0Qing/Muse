/**
 * 会话滚动位置记忆（仅内存）。
 *
 * 切会话再切回来时不该回到顶部，也不该被当成「用户在底部」而强行贴底——
 * 所以这里只存数字，滚动恢复的时机由聊天页自己决定（内容换完再落位置）。
 */
const MAX_ENTRIES = 200;

export class ScrollMemory {
  private readonly tops = new Map<string, number>();

  remember(sessionId: string, top: number): void {
    if (!Number.isFinite(top) || top < 0) return;
    this.tops.set(sessionId, top);
    // 打开过的会话很多时，把最早的记忆挤掉，别让这张表无限长。
    while (this.tops.size > MAX_ENTRIES) {
      const oldest = this.tops.keys().next();
      if (oldest.done === true) break;
      this.tops.delete(oldest.value);
    }
  }

  recall(sessionId: string): number | undefined {
    return this.tops.get(sessionId);
  }

  forget(sessionId: string): void {
    this.tops.delete(sessionId);
  }

  size(): number {
    return this.tops.size;
  }
}
