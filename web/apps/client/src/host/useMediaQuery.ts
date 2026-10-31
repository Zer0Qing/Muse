import { useEffect, useState } from "react";

/**
 * 订阅一条 CSS 媒体查询。
 *
 * 只用于「布局档位影响行为」的少数地方——目前只有会话左栏：桌面档常驻，要预取
 * 会话预览；手机 / 平板档抽屉收起时不该发这批请求。纯视觉的档位差异一律留给 CSS，
 * 不要在 JS 里再判一次。
 *
 * 查询串必须与 styles.css 里的断点一致（断言脚本会比对 1024 这个数字）。
 */
export function useMediaQuery(query: string): boolean {
  const [matches, setMatches] = useState(() => matchesNow(query));

  useEffect(() => {
    if (typeof window === "undefined" || typeof window.matchMedia !== "function") return;
    const list = window.matchMedia(query);
    const update = () => setMatches(list.matches);
    update();
    list.addEventListener("change", update);
    return () => list.removeEventListener("change", update);
  }, [query]);

  return matches;
}

/** 没有 matchMedia 的环境（老浏览器）当成不匹配：最多少预取一次，不会崩。 */
function matchesNow(query: string): boolean {
  if (typeof window === "undefined" || typeof window.matchMedia !== "function") return false;
  return window.matchMedia(query).matches;
}
