// 私有 KV 示例：host.storage 只属于这个插件，别的插件读不到。
// 容量上限 8MB；写满时会返回错误而不是静默丢弃。
const KEY = "notes";

function readAll() {
  const raw = host.storage.get(KEY);
  if (!raw) return [];
  try {
    const parsed = JSON.parse(raw);
    return Array.isArray(parsed) ? parsed : [];
  } catch {
    // 数据坏了就当空的，不要因为一条坏记录让整个插件不可用
    return [];
  }
}

export function noteSave(args) {
  const text = String(args?.text ?? "").trim();
  if (!text) return "内容为空，没有保存。";
  const all = readAll();
  all.push({ text, at: Date.now() });
  host.storage.set(KEY, JSON.stringify(all.slice(-50)));
  return `已保存，共 ${Math.min(all.length, 50)} 条。`;
}

export function noteList() {
  const all = readAll().slice(-10);
  if (all.length === 0) return "还没有任何笔记。";
  return all.map((n, i) => `${i + 1}. ${n.text}`).join("\n");
}
