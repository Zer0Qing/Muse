const API_BASE = import.meta.env.VITE_API_BASE ?? "http://localhost:8080";

export interface AdminSession { readonly token: string; readonly userId: string; readonly expiresAt: number; }
export interface Plan { readonly id: string; readonly name: string; readonly includedCredits: number; readonly prices: readonly { monthlyPriceFen: number; annualPriceFen: number; effectiveAt: string }[]; readonly byokAllowed: boolean; readonly hostedModelsAllowed: boolean; readonly archived: boolean; }
export interface AdminUser { readonly id: string; readonly email: string; readonly role: string; readonly createdAt: string; }

async function request<T>(path: string, init: RequestInit, session: AdminSession): Promise<T> {
  const headers = new Headers(init.headers);
  headers.set("content-type", "application/json");
  headers.set("authorization", `Bearer ${session.token}`);
  const response = await fetch(`${API_BASE}${path}`, { ...init, headers });
  const body: unknown = await response.json();
  if (!response.ok) throw new Error(body && typeof body === "object" && "message" in body && typeof body.message === "string" ? body.message : `请求失败 (${response.status})`);
  return body as T;
}

export async function login(email: string, password: string): Promise<AdminSession> {
  const response = await fetch(`${API_BASE}/api/v1/auth/login`, { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ email, password }) });
  const body = await response.json() as { session?: AdminSession; message?: string };
  if (!response.ok || !body.session) throw new Error(body.message ?? "登录失败");
  return body.session;
}
export const listPlans = (session: AdminSession) => request<{ plans: Plan[] }>("/api/v1/admin/plans", {}, session);
export const listUsers = (session: AdminSession) => request<{ users: AdminUser[] }>("/api/v1/admin/users", {}, session);
export const listAudit = (session: AdminSession) => request<{ entries: { actorId: string; action: string; target: string; at: string }[] }>("/api/v1/admin/audit", {}, session);
export const schedulePrice = (session: AdminSession, planId: string, monthlyPriceFen: number, annualPriceFen: number, effectiveAt: string) => request<{ plan: Plan }>(`/api/v1/admin/plans/${encodeURIComponent(planId)}/price`, { method: "POST", headers: { "idempotency-key": `price-${planId}-${effectiveAt}` }, body: JSON.stringify({ monthlyPriceFen, annualPriceFen, effectiveAt }) }, session);
export const createPlan = (session: AdminSession, input: { id: string; name: string; monthlyPriceFen: number; annualPriceFen: number; includedCredits: number; byokAllowed: boolean; hostedModelsAllowed: boolean }) => request<{ plan: Plan }>("/api/v1/admin/plans", { method: "POST", body: JSON.stringify({ ...input, limits: {} }) }, session);
export const grantCredits = (session: AdminSession, accountId: string, amount: number, reason: string) => request(`/api/v1/admin/credits/grant`, { method: "POST", headers: { "idempotency-key": `grant-${accountId}-${Date.now()}` }, body: JSON.stringify({ accountId, amount, reason }) }, session);
