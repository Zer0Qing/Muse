/**
 * 待办请求的**超时策略**（Host WS 客户端）。
 *
 * 存在的原因是一个很具体的僵局：客户端发出命令后把待办挂在各个 `pending*` 映射里，
 * 只有收到对应事件才会清。如果服务端**收下却没回任何事件**（卡死、被系统杀掉、
 * 协议版本不匹配导致分支没走到），那些待办就永远挂着 —— 按钮一直转圈、
 * 开关一直禁用，而且用户看不出是"还在做"还是"已经死了"。
 *
 * 这里把"到点了该怎么办"抽成纯函数，是因为**读和写必须区别对待**：
 *
 *  - **读**（列表 / 详情 / 预览 / 设置快照）：没回应就是没数据，
 *    停掉转圈、给一句"没有回应"，让用户可以重试；
 *  - **写**：超时的真相是**结果未知** —— 命令可能已经执行成功了。
 *    所以既不回滚乐观写（那会把成功的改动撤回，制造"没保存上"的假象），
 *    也不清掉待办（迟到的 `error` 事件仍然要能回滚），只提示"状态未确认，请刷新"。
 *
 * 这一条正是阶段 2 验收里"写操作结果未知时不盲目重发"的落点。
 */

/** 到点判定为"没有回应"的时长：本机环回连接上正常回复都是毫秒级。 */
export const PENDING_TIMEOUT_MS = 45_000;

export type PendingRequestKind = "read" | "write";

export interface PendingSettlePolicy {
  /** 是否把仍在转圈的只读切片停下来。 */
  readonly resetLoading: boolean;
  /** 是否回滚乐观写 —— 写超时**必须**是 false（结果未知不能假装失败）。 */
  readonly rollback: boolean;
  /** 提示文案的词条键。 */
  readonly messageKey: "error.noResponse" | "error.writeUnconfirmed";
  /** 是否丢弃待办登记（写超时保留，好让迟到的 error 事件仍能回滚）。 */
  readonly forgetRequest: boolean;
}

const READ_POLICY: PendingSettlePolicy = {
  resetLoading: true,
  rollback: false,
  messageKey: "error.noResponse",
  forgetRequest: true,
};

const WRITE_POLICY: PendingSettlePolicy = {
  resetLoading: false,
  // 关键：超时时不回滚。回滚等于向用户宣布"这次没写进去"，
  // 而事实是"不知道"；服务端如果最终失败会补发 error 事件，那时才回滚。
  rollback: false,
  messageKey: "error.writeUnconfirmed",
  forgetRequest: false,
};

export function settlePolicyFor(kind: PendingRequestKind): PendingSettlePolicy {
  return kind === "write" ? WRITE_POLICY : READ_POLICY;
}
