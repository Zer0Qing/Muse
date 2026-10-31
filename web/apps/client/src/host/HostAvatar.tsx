import { useState } from "react";
import { resolveAvatarUrl } from "./messageMedia.js";

interface HostAvatarProps {
  /** 助手名：没有可用头像时取首字。 */
  readonly name: string;
  readonly uri?: string | undefined;
  readonly size?: "small" | "large";
}

/**
 * 助手头像。
 *
 * 头像 uri 可能是手机本地路径（浏览器取不到），也可能是 `/api/` 同源路径或 http(s)；
 * 取不到、或者图片加载失败，就退回名字首字——不留空框也不留碎图。
 * 名字始终由旁边的文字给出，这里对读屏隐藏，避免念两遍。
 */
export function HostAvatar({ name, uri, size = "small" }: HostAvatarProps) {
  const [failed, setFailed] = useState(false);
  const src = failed ? undefined : resolveAvatarUrl(uri);
  const initial = firstChar(name);
  return (
    <span className={size === "large" ? "host-avatar is-large" : "host-avatar"} aria-hidden="true">
      {src === undefined ? (
        <span className="host-avatar-initial">{initial}</span>
      ) : (
        <img src={src} alt="" onError={() => setFailed(true)} />
      )}
    </span>
  );
}

function firstChar(name: string): string {
  const trimmed = name.trim();
  if (trimmed.length === 0) return "?";
  return [...trimmed][0] ?? "?";
}
