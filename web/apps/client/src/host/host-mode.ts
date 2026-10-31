/**
 * Host (LAN phone runtime) vs SaaS mode selection.
 *
 * The phone's embedded server exposes `GET /api/health` → `{ "status": "ok" }`
 * and a PIN login, so a same-origin probe tells us the page was opened from the
 * phone. Anything else (Vite dev server, SaaS deployment) falls back to SaaS.
 */

export type AppMode = "host" | "saas";
export type ModeState = AppMode | "pending";

const HOST_FLAG = "muse.host";

export function isHostAuthenticated(): boolean {
  return sessionStorage.getItem(HOST_FLAG) === "1";
}

export function markHostAuthenticated(): void {
  sessionStorage.setItem(HOST_FLAG, "1");
}

export function clearHostAuthenticated(): void {
  sessionStorage.removeItem(HOST_FLAG);
}

function forcedMode(): AppMode | undefined {
  const query = new URLSearchParams(window.location.search).get("mode");
  if (query === "host") return "host";
  if (query === "saas") return "saas";
  if (import.meta.env.VITE_HOST_MODE === "true") return "host";
  return undefined;
}

/** Synchronous first guess; `pending` means "probe the origin before rendering". */
export function initialMode(): ModeState {
  const forced = forcedMode();
  if (forced) return forced;
  if (isHostAuthenticated()) return "host";
  return "pending";
}

/** Ask the current origin whether it is a Muse Host. Never throws. */
export async function detectMode(): Promise<AppMode> {
  const forced = forcedMode();
  if (forced) return forced;
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), 1500);
  try {
    const response = await fetch("/api/health", {
      credentials: "include",
      cache: "no-store",
      signal: controller.signal,
    });
    if (!response.ok) return "saas";
    const body: unknown = await response.json();
    return body !== null && typeof body === "object" && "status" in body && body.status === "ok" ? "host" : "saas";
  } catch {
    return "saas";
  } finally {
    clearTimeout(timer);
  }
}

/** Reload into the SaaS login (used only when the Host cannot be reached). */
export function switchToSaas(): void {
  const url = new URL(window.location.href);
  url.searchParams.set("mode", "saas");
  window.location.replace(url.toString());
}
