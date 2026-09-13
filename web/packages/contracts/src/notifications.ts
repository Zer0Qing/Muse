export interface PushSubscriptionRecord {
  readonly id: string;
  readonly accountId: string;
  readonly endpoint: string;
  readonly p256dh: string;
  readonly auth: string;
  readonly userAgent?: string;
  readonly createdAt: string;
  readonly lastSeenAt: string;
}

export interface WebCapabilities {
  readonly serviceWorker: boolean;
  readonly push: boolean;
  readonly notifications: boolean;
  readonly camera: boolean;
  readonly microphone: boolean;
  readonly share: boolean;
  readonly unsupportedAndroidCapabilities: readonly string[];
}

export function detectWebCapabilities(): WebCapabilities {
  const navigatorValue = typeof navigator === "undefined" ? undefined : navigator;
  return {
    serviceWorker: typeof navigatorValue?.serviceWorker !== "undefined",
    push: typeof navigatorValue?.serviceWorker !== "undefined" && "PushManager" in globalThis,
    notifications: "Notification" in globalThis,
    camera: typeof navigatorValue?.mediaDevices?.getUserMedia === "function",
    microphone: typeof navigatorValue?.mediaDevices?.getUserMedia === "function",
    share: typeof navigatorValue?.share === "function",
    unsupportedAndroidCapabilities: ["accessibility", "shizuku", "root", "cross-app-control", "system-notifications", "android-widgets"],
  };
}
