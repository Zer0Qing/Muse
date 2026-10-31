import type { HostMessage, HostToolCall } from "../api.js";

/**
 * 消息的「是否需要重渲染」判定，以及快照合并。
 *
 * Host 每帧重推整个 messages 数组，但绝大多数消息其实没变。这里做两件事：
 *  1. `areMessagesEqual` 只看真正影响渲染的字段（id / 正文 / 状态 / 工具调用 / 引用 / 媒体地址…），
 *     React.memo 用它当比较函数；
 *  2. `mergeMessages` 在快照到达时把没变的消息**换回旧对象引用**，否则 memo 会因为新数组里的
 *     新对象全部失效。两个函数共用同一套字段判断，不会出现「合并说没变、memo 说变了」。
 */

function sameStrings(left: readonly string[] | undefined, right: readonly string[] | undefined): boolean {
  if (left === right) return true;
  const a = left ?? [];
  const b = right ?? [];
  if (a.length !== b.length) return false;
  return a.every((value, index) => value === b[index]);
}

function sameToolCalls(left: readonly HostToolCall[] | undefined, right: readonly HostToolCall[] | undefined): boolean {
  if (left === right) return true;
  const a = left ?? [];
  const b = right ?? [];
  if (a.length !== b.length) return false;
  return a.every((call, index) => {
    const other = b[index];
    if (other === undefined) return false;
    return (
      call.callId === other.callId &&
      call.name === other.name &&
      call.argumentsPreview === other.argumentsPreview &&
      call.resultPreview === other.resultPreview &&
      call.success === other.success &&
      call.durationMs === other.durationMs
    );
  });
}

/** 两条消息渲染出来是否一模一样（引用相同直接判等）。 */
export function areMessagesEqual(prev: HostMessage, next: HostMessage): boolean {
  if (prev === next) return true;
  return (
    prev.id === next.id &&
    prev.role === next.role &&
    prev.content === next.content &&
    prev.reasoning === next.reasoning &&
    prev.status === next.status &&
    prev.errorText === next.errorText &&
    prev.quotedContent === next.quotedContent &&
    prev.favorite === next.favorite &&
    prev.toolName === next.toolName &&
    prev.toolCallId === next.toolCallId &&
    prev.modelId === next.modelId &&
    prev.createdAt === next.createdAt &&
    prev.voiceUrl === next.voiceUrl &&
    prev.voiceDurationMs === next.voiceDurationMs &&
    sameStrings(prev.imageUrls, next.imageUrls) &&
    sameStrings(prev.citationUrls, next.citationUrls) &&
    sameToolCalls(prev.toolCalls, next.toolCalls)
  );
}

/**
 * 快照合并：从头逐条比对，没变的消息直接复用旧对象，变了的（通常是流式的最后一条）用新的。
 *
 * 只在**同前缀**上复用——消息顺序、条数一变就整份换成新数组，避免把引用错配到别的消息上。
 */
export function mergeMessages(
  previous: readonly HostMessage[],
  next: readonly HostMessage[],
): readonly HostMessage[] {
  if (previous.length === 0 || next.length === 0) return next;
  let index = 0;
  while (index < previous.length && index < next.length) {
    const older = previous[index];
    const newer = next[index];
    if (older === undefined || newer === undefined || !areMessagesEqual(older, newer)) break;
    index += 1;
  }
  if (index === 0) return next;
  if (index === next.length && previous.length === next.length) return previous;
  const merged = next.slice();
  for (let position = 0; position < index; position += 1) {
    const older = previous[position];
    if (older !== undefined) merged[position] = older;
  }
  return merged;
}

/** MessageBubble 的 memo 比较：数据字段全等就跳过重渲染（回调都由上层保证是稳定的）。 */
export interface BubbleCompareProps {
  readonly message: HostMessage;
  readonly streaming: boolean;
  readonly showActions: boolean;
  readonly toolStatus?: string | undefined;
  readonly capabilities: unknown;
  readonly favorited?: boolean | undefined;
  readonly actionsOpen?: boolean | undefined;
  readonly selectionMode?: boolean | undefined;
}

export function areBubblePropsEqual(prev: BubbleCompareProps, next: BubbleCompareProps): boolean {
  return (
    prev.streaming === next.streaming &&
    prev.showActions === next.showActions &&
    prev.toolStatus === next.toolStatus &&
    prev.capabilities === next.capabilities &&
    prev.favorited === next.favorited &&
    prev.actionsOpen === next.actionsOpen &&
    prev.selectionMode === next.selectionMode &&
    areMessagesEqual(prev.message, next.message)
  );
}
