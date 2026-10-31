/**
 * 媒体地址归一：把 Host 下发的 `imageUrls` / `voiceUrl` 变成浏览器真能加载的 src。
 *
 * 新 Host（capabilityFlags 含 media）给的是同源相对路径 `/api/media/<token>`，Cookie 自动带，
 * 直接用作 src 即可。老数据仍可能是这四种形态，都要兼容：
 *  - `data:` URI / `http(s):` URL / `blob:`：直接用；
 *  - 裸 base64：补 `data:image/png;base64,` 前缀（App 侧 `imageBase64List` 同写法）；
 *  - `file:///data/user/...` 或老式绝对路径：浏览器读不到，归成 unavailable 给占位。
 */
const BASE64_SAMPLE = 64;

export type MessageImage =
  | { readonly kind: "src"; readonly src: string }
  /** 取不到字节：手机本地文件（老数据），或完全认不得的地址形态。 */
  | { readonly kind: "unavailable"; readonly ref: string; readonly reason: "local-file" | "unknown" };

export function resolveMessageImages(urls: readonly string[] | undefined): readonly MessageImage[] {
  if (urls === undefined || urls.length === 0) return [];
  return urls.map((url) => {
    const trimmed = url.trim();
    if (trimmed.length === 0) return { kind: "unavailable", ref: "", reason: "unknown" };
    const src = borrowableSource(trimmed);
    if (src !== undefined) return { kind: "src", src };
    if (isRawBase64(trimmed)) return { kind: "src", src: `data:image/png;base64,${trimmed}` };
    // 手机本地路径（老数据）与「认不出的地址形态」分开说，避免误导读用户去找文件。
    const local = trimmed.startsWith("file://") || trimmed.startsWith("content://") || trimmed.startsWith("/");
    return { kind: "unavailable", ref: trimmed, reason: local ? "local-file" : "unknown" };
  });
}

/**
 * 语音地址。返回 undefined 表示这份地址浏览器取不到（老 Host 的 `file://` 引用），
 * 调用方据此降级成静态时长条，而不是挂一个必然报错的 `<audio>`。
 */
export function resolveVoiceUrl(url: string | undefined): string | undefined {
  if (url === undefined) return undefined;
  const trimmed = url.trim();
  if (trimmed.length === 0) return undefined;
  return borrowableSource(trimmed);
}

/**
 * 头像地址：与媒体同一套规则（同源 `/api/` 路径、http(s)、data）。
 * 头像 uri 也可能指向手机本地文件，浏览器取不到——调用方据此退回首字母。
 */
export function resolveAvatarUrl(uri: string | undefined): string | undefined {
  if (uri === undefined) return undefined;
  const trimmed = uri.trim();
  if (trimmed.length === 0) return undefined;
  return borrowableSource(trimmed);
}

/** 浏览器能自己取的几类地址：同源相对路径、http(s)、data、blob。 */
function borrowableSource(value: string): string | undefined {
  if (value.startsWith("data:") || value.startsWith("blob:")) return value;
  if (value.startsWith("http://") || value.startsWith("https://") || value.startsWith("//")) return value;
  // 同源媒体：新 Host 的 /api/media/<token>。其他以 / 开头的是老数据的手机文件绝对路径，
  // 不能当 URL 用（会变成 404 破图）。
  if (value.startsWith("/api/")) return value;
  return undefined;
}

/** 只在确实像 base64 时才补前缀，避免把本地路径当成图片数据。 */
function isRawBase64(value: string): boolean {
  if (value.length < BASE64_SAMPLE) return false;
  if (value.includes("/") && !/^[A-Za-z0-9+/=\s]+$/.test(value)) return false;
  return /^[A-Za-z0-9+/]+={0,2}$/.test(value.replace(/\s+/g, ""));
}
