/**
 * 把文本写进剪贴板。
 *
 * Host 页面是局域网 `http://` 打开的，不是 secure context：Chrome / Safari 在这种来源下
 * 根本不暴露 `navigator.clipboard`。所以先走异步 Clipboard API（本机 localhost、HTTPS 部署），
 * 失败或缺失再退到「隐藏 textarea + execCommand」这条老路——它仍会在用户手势的同步调用里工作。
 * 返回 false 表示两条路都没成，调用方必须把失败说出来，不能假装复制成功。
 */
export async function copyTextToClipboard(text: string): Promise<boolean> {
  if (text.length === 0) return false;
  const clipboard = typeof navigator !== "undefined" ? navigator.clipboard : undefined;
  if (clipboard?.writeText !== undefined) {
    try {
      await clipboard.writeText(text);
      return true;
    } catch {
      // 权限被拒（非用户手势、页面失焦）时继续走下面的兜底。
    }
  }
  if (typeof document === "undefined") return false;
  const area = document.createElement("textarea");
  area.value = text;
  area.setAttribute("readonly", "");
  area.setAttribute("aria-hidden", "true");
  area.style.position = "fixed";
  area.style.top = "0";
  area.style.left = "0";
  area.style.width = "1px";
  area.style.height = "1px";
  area.style.opacity = "0";
  const previous = document.activeElement instanceof HTMLElement ? document.activeElement : null;
  document.body.appendChild(area);
  let copied = false;
  try {
    area.select();
    copied = document.execCommand("copy");
  } catch {
    copied = false;
  } finally {
    area.remove();
    if (previous !== null) {
      try {
        previous.focus();
      } catch {
        // 焦点还不回原来的元素不影响复制结果本身。
      }
    }
  }
  return copied;
}
