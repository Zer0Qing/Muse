// 交互界面示例：表单由宿主原生渲染（跟随用户主题），提交后把值回传到这里。
// 注意：结果**写回输入框**由宿主决定，插件不直接往聊天记录里插消息。
export function fillForm(args) {
  const values = args?.values ?? {};
  const who = String(values.who ?? "").trim() || "（未填）";
  const why = String(values.why ?? "").trim() || "（未填）";
  const urgent = values.urgent === true ? "是" : "否";
  return `收到：对象=${who}；事由=${why}；加急=${urgent}`;
}
