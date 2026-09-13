export type AgentTaskStatus = "queued" | "running" | "waiting_approval" | "completed" | "failed" | "cancelled";
export type GroupChatMode = "round_robin" | "free_rotation";

export interface AgentTask {
  readonly id: string;
  readonly accountId: string;
  readonly sessionId: string;
  readonly assistantId: string;
  readonly prompt: string;
  readonly status: AgentTaskStatus;
  readonly createdAt: string;
  readonly updatedAt: string;
  readonly error?: string;
}

export interface GroupChatMessage {
  readonly id: string;
  readonly chatId: string;
  readonly senderId: string;
  readonly senderName: string;
  readonly content: string;
  readonly createdAt: string;
}

const terminal: readonly AgentTaskStatus[] = ["completed", "failed", "cancelled"];
const allowed: Readonly<Record<AgentTaskStatus, readonly AgentTaskStatus[]>> = {
  queued: ["running", "cancelled"], running: ["waiting_approval", "completed", "failed", "cancelled"], waiting_approval: ["running", "failed", "cancelled"], completed: [], failed: [], cancelled: [],
};

/** Agent task state machine; queue and worker implementations can persist this record later. */
export class AgentTaskStore {
  private readonly tasks = new Map<string, AgentTask>();

  create(input: Omit<AgentTask, "id" | "status" | "createdAt" | "updatedAt">): AgentTask {
    if (!input.accountId || !input.sessionId || !input.assistantId || !input.prompt.trim()) throw new Error("agent task fields are required");
    const now = new Date().toISOString();
    const task: AgentTask = { ...input, id: crypto.randomUUID(), status: "queued", createdAt: now, updatedAt: now };
    this.tasks.set(task.id, task);
    return { ...task };
  }

  get(accountId: string, id: string): AgentTask | undefined { const task = this.tasks.get(id); return task?.accountId === accountId ? { ...task } : undefined; }

  transition(accountId: string, id: string, status: AgentTaskStatus, error?: string): AgentTask {
    const task = this.get(accountId, id);
    if (!task) throw new Error("agent task not found");
    if (!allowed[task.status].includes(status)) throw new Error(`invalid task transition ${task.status} -> ${status}`);
    const next: AgentTask = { ...task, status, updatedAt: new Date().toISOString(), ...(error === undefined ? {} : { error: error.slice(0, 500) }) };
    this.tasks.set(id, next);
    return { ...next };
  }

  list(accountId: string): AgentTask[] { return [...this.tasks.values()].filter((task) => task.accountId === accountId).map((task) => ({ ...task })); }
}

export function canRunGroupChat(mode: GroupChatMode, memberIds: readonly string[], maxMembers = 12): boolean {
  return (mode === "round_robin" || mode === "free_rotation") && memberIds.length >= 2 && memberIds.length <= maxMembers && new Set(memberIds).size === memberIds.length;
}

export function isTerminal(status: AgentTaskStatus): boolean { return terminal.includes(status); }
