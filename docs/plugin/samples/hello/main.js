// 最小示例：没有声明任何能力，所以只能做纯计算。
// 想读文件、联网、写 KV，必须在 manifest 的 capabilities 里声明，并由用户逐项授权。
export function greet(args) {
  const name = String(args?.name ?? "").trim();
  if (!name) return "请先告诉我要称呼谁。";
  return `你好，${name}！这是一个不需要任何权限的插件。`;
}
