import { StrictMode, useEffect, useMemo, useRef, useState } from "react";
import { createRoot } from "react-dom/client";
import type { ChatMessage, ProviderConfig } from "@muse/contracts";
import { detectWebCapabilities } from "@muse/contracts";
import {
  addMemoryFact,
  appendMessage,
  completeFile,
  createAssistant,
  createMemorySpace,
  createSession,
  indexRagDocument,
  listAssistants,
  listMessages,
  listProviders,
  listSessions,
  login,
  presignFile,
  register,
  registerPushSubscription,
  saveProvider,
  searchMemory,
  searchRag,
  streamChat,
  streamDirectByok,
  updateAssistant,
  type AssistantRecord,
  type ClientSession,
  type MemoryFact,
  type RagChunk,
  type SessionRecord,
  type StoredProviderConfig,
} from "./api.js";
import { MessageContent } from "./components/MessageContent.js";
import { HostApp } from "./host/HostApp.js";
import { detectMode, initialMode, type ModeState } from "./host/host-mode.js";
import { translateSaas as t } from "./i18n/saas.js";
import "./styles.css";
// Muse 组件基元的样式（取值来自 --muse-* 令牌）。基元本身目前没有页面级使用点，
// 这里先把样式层挂上，避免后续引入基元时才发现暗色/令牌没有生效。
import "./components/primitives.css";

const defaultProvider: ProviderConfig = { id: "request-provider", type: "openai", displayName: "OpenAI-compatible", baseUrl: "https://api.openai.com/v1", modelIds: [], supportsStreaming: true };
const providerTypes: ProviderConfig["type"][] = ["openai", "anthropic", "gemini", "deepseek", "openai_responses", "openai_compatible", "custom"];

function Login({ onLogin }: { onLogin: (session: ClientSession) => void }) {
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [registering, setRegistering] = useState(false);
  const [error, setError] = useState("");
  async function submit() {
    try { setError(""); if (registering) { await register(email, password); setRegistering(false); setError(t("账号已创建，请登录")); } else onLogin(await login(email, password)); }
    catch (cause) { setError(cause instanceof Error ? cause.message : t("请求失败")); }
  }
  return <section className="panel auth-panel"><p className="eyebrow">MUSE WEB · PWA</p><h1>{registering ? t("创建账号") : t("欢迎回来")}</h1><p className="lede">{t("BYOK 优先。API Key 只在本次浏览器会话内使用，不写入本地存储。")}</p><input aria-label={t("邮箱")} placeholder={t("邮箱")} value={email} onChange={(event) => setEmail(event.target.value)} /><input aria-label={t("密码")} type="password" placeholder={t("至少 12 位密码")} value={password} onChange={(event) => setPassword(event.target.value)} /><button type="button" onClick={() => void submit()}>{registering ? t("注册") : t("登录")}</button><button className="quiet" type="button" onClick={() => { setRegistering(!registering); setError(""); }}>{registering ? t("已有账号，登录") : t("创建新账号")}</button>{error && <p className="error">{error}</p>}</section>;
}

function ProviderEditor({ session, providers, onSaved }: { session: ClientSession; providers: StoredProviderConfig[]; onSaved: (provider: StoredProviderConfig) => void }) {
  const [id, setId] = useState("");
  const [name, setName] = useState("");
  const [type, setType] = useState<ProviderConfig["type"]>("openai");
  const [baseUrl, setBaseUrl] = useState("https://api.openai.com/v1");
  const [models, setModels] = useState("");
  const [apiKey, setApiKey] = useState("");
  const [error, setError] = useState("");
  async function submit() {
    try {
      setError("");
      const provider: ProviderConfig = { id: id.trim() || crypto.randomUUID(), type, displayName: name.trim() || type, baseUrl: baseUrl.trim(), modelIds: models.split(",").map((item) => item.trim()).filter(Boolean), supportsStreaming: true };
      onSaved(await saveProvider(session, provider, apiKey.trim())); setApiKey("");
    } catch (cause) { setError(cause instanceof Error ? cause.message : t("Provider 保存失败")); }
  }
  return <div className="workspace-section"><div className="section-title"><div><p className="eyebrow">{t("PROVIDER VAULT")}</p><h3>{t("我的 Provider")}</h3></div><span className="muted">{t("密钥只写入服务端密文库，列表不会回显")}</span></div><div className="provider-cards">{providers.map((item) => <button className="saved-provider" type="button" key={item.id} onClick={() => { setId(item.id); setName(item.displayName); setType(item.type); setBaseUrl(item.baseUrl); setModels(item.modelIds.join(", ")); }}><b>{item.displayName}</b><small>{item.type} · {item.hasSecret ? t("已配置密钥") : t("缺少密钥")}</small></button>)}</div><div className="form-grid"><input placeholder={t("配置 ID")} value={id} onChange={(event) => setId(event.target.value)} /><input placeholder={t("显示名称")} value={name} onChange={(event) => setName(event.target.value)} /><select aria-label={t("Provider 类型")} value={type} onChange={(event) => setType(event.target.value as ProviderConfig["type"])}>{providerTypes.map((item) => <option key={item} value={item}>{item}</option>)}</select><input placeholder={t("Base URL（HTTPS）")} value={baseUrl} onChange={(event) => setBaseUrl(event.target.value)} /><input placeholder={t("模型 ID，逗号分隔")} value={models} onChange={(event) => setModels(event.target.value)} /><input type="password" placeholder={t("API Key（保存时写入）")} value={apiKey} onChange={(event) => setApiKey(event.target.value)} /></div><button type="button" disabled={!apiKey.trim()} onClick={() => void submit()}>{t("保存 Provider")}</button>{error && <p className="error">{error}</p>}</div>;
}

function AssistantEditor({ session, assistants, onSaved }: { session: ClientSession; assistants: AssistantRecord[]; onSaved: (assistant: AssistantRecord) => void }) {
  const [selected, setSelected] = useState<AssistantRecord | undefined>(assistants[0]);
  type AssistantDraft = Pick<AssistantRecord, "name" | "identityPrompt" | "relationshipPrompt" | "stylePrompt">;
  const [draft, setDraft] = useState<AssistantDraft>({ name: "", identityPrompt: "", relationshipPrompt: "", stylePrompt: "" });
  const [error, setError] = useState("");
  useEffect(() => { const item = selected ?? assistants[0]; if (item) { setSelected(item); setDraft({ name: item.name, identityPrompt: item.identityPrompt, relationshipPrompt: item.relationshipPrompt, stylePrompt: item.stylePrompt }); } }, [assistants, selected]);
  async function save() {
    if (!draft) return;
    try { setError(""); const item = selected ? await updateAssistant(session, { ...selected, ...draft }) : await createAssistant(session, draft); onSaved(item); setSelected(item); }
    catch (cause) { setError(cause instanceof Error ? cause.message : t("Assistant 保存失败")); }
  }
  const update = (key: keyof AssistantDraft, value: string) => setDraft((current) => ({ ...current, [key]: value }));
  return <div className="workspace-section"><div className="section-title"><div><p className="eyebrow">{t("ASSISTANT STUDIO")}</p><h3>{t("Assistant 人格")}</h3></div><span className="muted">{t("提示词参与后续聊天绑定")}</span></div>{assistants.length > 0 && <select aria-label={t("选择 Assistant")} value={selected?.id ?? ""} onChange={(event) => setSelected(assistants.find((item) => item.id === event.target.value))}>{assistants.map((item) => <option value={item.id} key={item.id}>{item.name}</option>)}</select>}<div className="form-grid"><input placeholder={t("名称")} value={draft.name} onChange={(event) => update("name", event.target.value)} /><textarea placeholder={t("身份提示词")} value={draft.identityPrompt} onChange={(event) => update("identityPrompt", event.target.value)} /><textarea placeholder={t("关系提示词")} value={draft.relationshipPrompt} onChange={(event) => update("relationshipPrompt", event.target.value)} /><textarea placeholder={t("风格提示词")} value={draft.stylePrompt} onChange={(event) => update("stylePrompt", event.target.value)} /></div><button type="button" disabled={!draft.name.trim()} onClick={() => void save()}>{selected ? t("保存 Assistant") : t("创建 Assistant")}</button>{error && <p className="error">{error}</p>}</div>;
}

function KnowledgePanel({ session, assistantId }: { session: ClientSession; assistantId: string }) {
  const [memoryQuery, setMemoryQuery] = useState("");
  const [facts, setFacts] = useState<MemoryFact[]>([]);
  const [factText, setFactText] = useState("");
  const [spaceId, setSpaceId] = useState("");
  const [spaceName, setSpaceName] = useState("");
  const [ragQuery, setRagQuery] = useState("");
  const [chunks, setChunks] = useState<RagChunk[]>([]);
  const [documentText, setDocumentText] = useState("");
  const [error, setError] = useState("");
  async function runMemorySearch() { try { setFacts(await searchMemory(session, memoryQuery, assistantId, spaceId)); } catch (cause) { setError(cause instanceof Error ? cause.message : t("记忆搜索失败")); } }
  async function createSpace() { try { setError(""); const space = await createMemorySpace(session, spaceName); setSpaceId(space.id); setSpaceName(""); } catch (cause) { setError(cause instanceof Error ? cause.message : t("记忆空间创建失败")); } }
  async function addFact() { try { setError(""); if (!spaceId) throw new Error(t("请先创建或填写 Space ID")); await addMemoryFact(session, { assistantId, spaceId, content: factText, importance: 1 }); setFactText(""); if (memoryQuery) await runMemorySearch(); } catch (cause) { setError(cause instanceof Error ? cause.message : t("记忆保存失败")); } }
  async function runRagSearch() { try { const result = await searchRag(session, ragQuery); setChunks(result.chunks); } catch (cause) { setError(cause instanceof Error ? cause.message : t("RAG 搜索失败")); } }
  async function indexText() { try { setError(""); await indexRagDocument(session, `web-${crypto.randomUUID()}`, documentText); setDocumentText(""); } catch (cause) { setError(cause instanceof Error ? cause.message : t("文档索引失败")); } }
  return <div className="workspace-section"><div className="section-title"><div><p className="eyebrow">{t("MEMORY · RAG")}</p><h3>{t("知识与记忆")}</h3></div><span className="muted">{t("当前 Assistant：")}{assistantId === "default" ? t("默认") : assistantId.slice(0, 8)}</span></div><div className="knowledge-grid"><div className="knowledge-card"><h4>{t("记忆事实")}</h4><div className="inline-form"><input placeholder={t("新建 Memory Space")} value={spaceName} onChange={(event) => setSpaceName(event.target.value)} /><button type="button" disabled={!spaceName.trim()} onClick={() => void createSpace()}>{t("新建")}</button></div><input placeholder={t("Space ID")} value={spaceId} onChange={(event) => setSpaceId(event.target.value)} /><div className="inline-form"><input placeholder={t("添加一条事实")} value={factText} onChange={(event) => setFactText(event.target.value)} /><button type="button" disabled={!factText.trim()} onClick={() => void addFact()}>{t("记住")}</button></div><div className="inline-form"><input placeholder={t("搜索记忆")} value={memoryQuery} onChange={(event) => setMemoryQuery(event.target.value)} /><button type="button" onClick={() => void runMemorySearch()}>{t("搜索")}</button></div>{facts.map((fact) => <p className="result-line" key={fact.id}>{fact.content}<small>{t("重要度 ")}{fact.importance} {t(" · 命中 ")}{fact.hitCount}</small></p>)}</div><div className="knowledge-card"><h4>{t("RAG 文档")}</h4><textarea placeholder={t("粘贴文本，创建索引")} value={documentText} onChange={(event) => setDocumentText(event.target.value)} /><button type="button" disabled={!documentText.trim()} onClick={() => void indexText()}>{t("索引文本")}</button><div className="inline-form"><input placeholder={t("搜索已索引内容")} value={ragQuery} onChange={(event) => setRagQuery(event.target.value)} /><button type="button" onClick={() => void runRagSearch()}>{t("搜索")}</button></div>{chunks.map((chunk) => <p className="result-line" key={chunk.id}>{chunk.text}<small>{chunk.documentId} · {chunk.tokenEstimate} tokens</small></p>)}</div></div>{error && <p className="error">{error}</p>}</div>;
}

function FilePanel({ session }: { session: ClientSession }) {
  const [message, setMessage] = useState(t("选择文件后先向 API 申请一次性上传地址"));
  async function upload(file: File) {
    try {
      const fileId = crypto.randomUUID();
      const plan = await presignFile(session, { fileId, name: file.name, mimeType: file.type, sizeBytes: file.size });
      const response = await fetch(plan.upload.url, { method: "PUT", headers: { "content-type": file.type }, body: file });
      if (!response.ok) throw new Error(t("对象存储上传失败（{status}）", { status: response.status }));
      const digest = await crypto.subtle.digest("SHA-256", await file.arrayBuffer());
      const sha256 = Array.from(new Uint8Array(digest), (byte) => byte.toString(16).padStart(2, "0")).join("");
      await completeFile(session, { fileId, name: file.name, mimeType: file.type, sizeBytes: file.size, objectKey: plan.objectKey, sha256 });
      setMessage(t("{name} 已上传并登记，地址有效至 {time}", { name: file.name, time: new Date(plan.upload.expiresAt).toLocaleTimeString() }));
    } catch (cause) { setMessage(cause instanceof Error ? cause.message : t("文件上传失败")); }
  }
  return <div className="workspace-section"><div className="section-title"><div><p className="eyebrow">{t("OBJECT STORAGE")}</p><h3>{t("文件")}</h3></div><span className="muted">{t("API 不接收文件字节，浏览器直传 S3-compatible 存储")}</span></div><label className="file-drop">{t("选择文件")}<input type="file" onChange={(event) => { const file = event.target.files?.[0]; if (file) void upload(file); }} /></label><p className="muted">{message}</p></div>;
}

function PushPanel({ session }: { session: ClientSession }) {
  const capabilities = useMemo(() => detectWebCapabilities(), []);
  const [message, setMessage] = useState(capabilities.push ? t("浏览器支持 Push") : t("当前浏览器不支持 Push"));
  async function enable() {
    try { if (!capabilities.push || !capabilities.notifications) throw new Error(t("当前浏览器不支持通知")); const key = import.meta.env.VITE_VAPID_PUBLIC_KEY; if (!key) throw new Error(t("未配置 VITE_VAPID_PUBLIC_KEY")); const permission = await Notification.requestPermission(); if (permission !== "granted") throw new Error(t("通知权限未授予")); const registration = await navigator.serviceWorker.ready; const subscription = await registration.pushManager.subscribe({ userVisibleOnly: true, applicationServerKey: Uint8Array.from(atob(key.replace(/-/g, "+").replace(/_/g, "/")), (char) => char.charCodeAt(0)) }); await registerPushSubscription(session, subscription); setMessage(t("Push 已启用")); } catch (cause) { setMessage(cause instanceof Error ? cause.message : t("Push 启用失败")); }
  }
  return <div className="workspace-section compact-section"><div><p className="eyebrow">PWA</p><h3>{t("通知与离线能力")}</h3></div><p className="muted">{message}</p><button type="button" disabled={!capabilities.push} onClick={() => void enable()}>{t("启用通知")}</button></div>;
}

function Workspace({ session, onLogout }: { session: ClientSession; onLogout: () => void }) {
  const [providers, setProviders] = useState<StoredProviderConfig[]>([]);
  const [assistants, setAssistants] = useState<AssistantRecord[]>([]);
  const [open, setOpen] = useState(false);
  const [error, setError] = useState("");
  useEffect(() => { const openWorkspace = () => setOpen(true); window.addEventListener("muse:workspace", openWorkspace); return () => window.removeEventListener("muse:workspace", openWorkspace); }, []);
  useEffect(() => { void Promise.all([listProviders(session), listAssistants(session)]).then(([nextProviders, nextAssistants]) => { setProviders(nextProviders); setAssistants(nextAssistants); }).catch((cause) => setError(cause instanceof Error ? cause.message : t("工作区加载失败"))); }, [session]);
  const assistantId = assistants[0]?.id ?? "default";
  return <>{open && <button className="workspace-scrim" type="button" aria-label={t("关闭设置")} onClick={() => setOpen(false)} />}<aside className={open ? "workspace open" : "workspace"}><div className="workspace-head"><div><p className="eyebrow">{t("MUSE WORKSPACE")}</p><h3>{t("能力设置")}</h3></div><button className="quiet" type="button" onClick={() => setOpen(false)}>{t("收起")}</button></div><ProviderEditor session={session} providers={providers} onSaved={(provider) => setProviders((items) => [provider, ...items.filter((item) => item.id !== provider.id)])} /><AssistantEditor session={session} assistants={assistants} onSaved={(assistant) => setAssistants((items) => [assistant, ...items.filter((item) => item.id !== assistant.id)])} /><KnowledgePanel session={session} assistantId={assistantId} /><FilePanel session={session} /><PushPanel session={session} />{error && <p className="error">{error}</p>}<button className="quiet logout-workspace" type="button" onClick={onLogout}>{t("退出登录")}</button></aside></>;
}

function Chat({ session, onLogout }: { session: ClientSession; onLogout: () => void }) {
  const [sessions, setSessions] = useState<SessionRecord[]>([]);
  const [current, setCurrent] = useState<SessionRecord | undefined>();
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [input, setInput] = useState("");
  const [apiKey, setApiKey] = useState("");
  const [mode, setMode] = useState<"byok" | "hosted">("byok");
  const [transport, setTransport] = useState<"direct" | "proxy">("direct");
  const [providers, setProviders] = useState<StoredProviderConfig[]>([]);
  const [assistants, setAssistants] = useState<AssistantRecord[]>([]);
  const [assistantId, setAssistantId] = useState("default");
  const [providerId, setProviderId] = useState("");
  const [model, setModel] = useState("gpt-4o-mini");
  const [busy, setBusy] = useState(false);
  const [mobileSidebarOpen, setMobileSidebarOpen] = useState(false);
  const [error, setError] = useState("");
  const abortRef = useRef<AbortController | null>(null);
  const provider = providers.find((item) => item.id === providerId) ?? defaultProvider;
  async function refresh() {
    const result = await listSessions(session);
    setSessions(result);
    const target = current && result.find((item) => item.id === current.id) ? current : result[0];
    if (target) {
      setCurrent(target);
      setAssistantId(target.assistantId);
      setMessages(await listMessages(session, target.id));
      return;
    }
    const created = await createSession(session, assistantId === "default" ? undefined : assistantId);
    setSessions([created]);
    setCurrent(created);
    setAssistantId(created.assistantId);
    setMessages([]);
  }
  useEffect(() => { void Promise.all([refresh(), listProviders(session), listAssistants(session)]).then(([, nextProviders, nextAssistants]) => { setProviders(nextProviders); setAssistants(nextAssistants); if (nextProviders[0]) { setProviderId(nextProviders[0].id); if (nextProviders[0].modelIds[0]) setModel(nextProviders[0].modelIds[0]); } }).catch((cause) => setError(cause instanceof Error ? cause.message : t("加载失败"))); }, []);
  async function newChat() { const created = await createSession(session, assistantId === "default" ? undefined : assistantId); setSessions((items) => [created, ...items]); setCurrent(created); setAssistantId(created.assistantId); setMessages([]); setMobileSidebarOpen(false); }
  function stop() { abortRef.current?.abort(); }
  async function send() {
    const needsKey = mode === "byok" && transport === "direct";
    if (!current || !input.trim() || busy || (needsKey && !apiKey.trim())) return;
    const text = input.trim(); setInput(""); setBusy(true); setError("");
    const userMessage: ChatMessage = { id: crypto.randomUUID(), role: "user", content: text, createdAt: new Date().toISOString() };
    const history = [...messages, userMessage]; setMessages(history);
    const controller = new AbortController(); abortRef.current = controller;
    try {
      let assistant = ""; let reasoning = "";
      const systemMessage = assistantPrompt(assistants.find((item) => item.id === assistantId));
      const directMessages = systemMessage ? [systemMessage, ...history] : history;
      const directByok = transport === "direct" && mode === "byok";
      if (directByok) await appendMessage(session, current.id, userMessage);
      const events = directByok
        ? streamDirectByok({ messages: directMessages, provider, model, apiKey, signal: controller.signal })
        : streamChat({ session, sessionId: current.id, messages: history, provider, ...(providerId ? { providerId } : {}), model, apiKey, mode, transport, signal: controller.signal });
      for await (const event of events) {
        if (event.type === "content_delta") assistant += event.delta;
        if (event.type === "reasoning_delta") reasoning += event.delta;
        if (event.type === "error") throw new Error(event.message);
        setMessages([...history, { id: "streaming", role: "assistant", content: assistant, ...(reasoning ? { reasoning } : {}), createdAt: new Date().toISOString() }]);
      }
      if (!controller.signal.aborted) {
        if (directByok && assistant.trim()) {
          await appendMessage(session, current.id, { id: crypto.randomUUID(), role: "assistant", content: assistant, ...(reasoning ? { reasoning } : {}), createdAt: new Date().toISOString() });
        }
        await refresh();
      }
    } catch (cause) {
      if (!controller.signal.aborted) { setError(cause instanceof Error ? cause.message : t("发送失败")); setMessages(history); }
    } finally { if (abortRef.current === controller) abortRef.current = null; setBusy(false); }
  }
  return <><main className="app-layout">{mobileSidebarOpen && <button className="mobile-sidebar-scrim" type="button" aria-label={t("关闭会话列表")} onClick={() => setMobileSidebarOpen(false)} />}<aside className={mobileSidebarOpen ? "sidebar mobile-open" : "sidebar"}><div className="brand"><b>Muse</b><span>Web</span></div><button type="button" onClick={() => void newChat()}>{t("＋ 新对话")}</button><button className="workspace-toggle" type="button" onClick={() => window.dispatchEvent(new Event("muse:workspace"))}>{t("设置 Provider / Assistant")}</button><div className="session-list">{sessions.map((item) => <button className={current?.id === item.id ? "session active" : "session"} key={item.id} type="button" onClick={async () => { setCurrent(item); setAssistantId(item.assistantId); setMessages(await listMessages(session, item.id)); setMobileSidebarOpen(false); }}>{item.title}</button>)}</div><button className="quiet" type="button" onClick={onLogout}>{t("退出登录")}</button></aside><section className="chat"><header><button className="mobile-menu-button" type="button" aria-label={t("打开会话列表")} onClick={() => setMobileSidebarOpen(true)}>☰</button><div><p className="eyebrow">{t("PRIVATE CONVERSATION")}</p><h2>{current?.title ?? t("新对话")}</h2></div><span className="status">{busy ? t("生成中…") : t("在线")}</span></header><div className="messages" role="log" aria-live="polite" aria-label={t("消息记录")}>{messages.length === 0 && <div className="empty"><h3>{t("从一句话开始")}</h3><p>{t("在设置面板配置 Provider、Assistant、记忆和文件能力。")}</p></div>}{messages.map((message) => <article className={`message ${message.role}`} key={message.id}><small>{message.role === "user" ? t("你") : "Muse"}</small><MessageContent content={message.content || "…"} />{message.reasoning && <details><summary>{t("思考过程")}</summary><p>{message.reasoning}</p></details>}</article>)}</div><div className="composer"><div className="provider-row"><select aria-label={t("费用模式")} value={mode} onChange={(event) => setMode(event.target.value as "byok" | "hosted")}><option value="byok">{t("BYOK · 我的 Key")}</option><option value="hosted">{t("Hosted · 平台额度")}</option></select>{mode === "byok" && <select aria-label={t("传输方式")} value={transport} onChange={(event) => setTransport(event.target.value as "direct" | "proxy")}><option value="direct">{t("直连 Provider")}</option><option value="proxy">{t("服务端代理（已保存 Provider）")}</option></select>}<select aria-label={t("Assistant")} value={assistantId} onChange={(event) => setAssistantId(event.target.value)}><option value="default">{t("默认 Assistant")}</option>{assistants.map((item) => <option value={item.id} key={item.id}>{item.name}</option>)}</select><select aria-label={t("Provider")} value={providerId} onChange={(event) => { setProviderId(event.target.value); const selected = providers.find((item) => item.id === event.target.value); if (selected?.modelIds[0]) setModel(selected.modelIds[0]); }}>{providers.length === 0 && <option value="">{t("临时 Provider")}</option>}{providers.map((item) => <option value={item.id} key={item.id}>{item.displayName}</option>)}</select><input aria-label={t("模型")} value={model} onChange={(event) => setModel(event.target.value)} />{mode === "byok" && transport === "direct" && <input aria-label={t("API Key")} type="password" placeholder={t("本次会话 API Key")} value={apiKey} onChange={(event) => setApiKey(event.target.value)} />}</div><div className="send-row"><textarea aria-label={t("消息")} placeholder={t("写点什么…")} value={input} onChange={(event) => setInput(event.target.value)} onKeyDown={(event) => { if (event.key === "Enter" && !event.shiftKey) { event.preventDefault(); void send(); } }} /><button type="button" className={busy ? "stop-button" : "send-button"} disabled={busy ? false : (needsKey(mode, transport) && !apiKey.trim())} onClick={() => busy ? stop() : void send()}>{busy ? t("停止") : t("发送")}</button></div>{error && <p className="error">{error}</p>}</div></section></main><Workspace session={session} onLogout={onLogout} /></>;
}

function assistantPrompt(assistant: AssistantRecord | undefined): ChatMessage | undefined {
  if (!assistant) return undefined;
  const content = [assistant.identityPrompt, assistant.relationshipPrompt, assistant.stylePrompt].map((item) => item.trim()).filter(Boolean).join("\n\n").slice(0, 60_000);
  return content ? { id: `system:${assistant.id}`, role: "system", content, createdAt: new Date().toISOString() } : undefined;
}

function needsKey(mode: "byok" | "hosted", transport: "direct" | "proxy"): boolean { return mode === "byok" && transport === "direct"; }

function readStoredClientSession(): ClientSession | undefined {
  const raw = sessionStorage.getItem("muse.session");
  if (!raw) return undefined;
  try {
    const parsed: unknown = JSON.parse(raw);
    if (!parsed || typeof parsed !== "object") throw new Error("stored session is not an object");
    const stored = parsed as Partial<ClientSession>;
    const storedToken: unknown = Reflect.get(stored, "token");
    const userId = stored.userId;
    const expiresAt = stored.expiresAt;
    if (typeof storedToken !== "string" || typeof userId !== "string" || typeof expiresAt !== "number") {
      throw new Error("stored session has invalid fields");
    }
    if (expiresAt <= Date.now()) {
      sessionStorage.removeItem("muse.session");
      return undefined;
    }
    return { token: storedToken, userId, expiresAt };
  } catch {
    sessionStorage.removeItem("muse.session");
    return undefined;
  }
}

/** SaaS (cloud account) shell; kept as-is next to the Host mobile shell. */
function SaasApp() {
  const [session, setSession] = useState<ClientSession | undefined>(readStoredClientSession);
  function handleLogin(value: ClientSession) { sessionStorage.setItem("muse.session", JSON.stringify(value)); setSession(value); }
  function logout() { sessionStorage.removeItem("muse.session"); setSession(undefined); }
  return session ? <Chat session={session} onLogout={logout} /> : <main className="shell"><Login onLogin={handleLogin} /></main>;
}

/**
 * Entry gate: a page served by the phone (LAN) prefers Host mode with the PIN;
 * everything else (dev server, SaaS deploy) keeps the cloud account flow.
 */
function App() {
  const [mode, setMode] = useState<ModeState>(initialMode);
  useEffect(() => {
    if (mode !== "pending") return;
    let active = true;
    void detectMode().then((next) => { if (active) setMode(next); });
    return () => { active = false; };
  }, [mode]);
  if (mode === "pending") {
    return <main className="shell"><div className="boot-screen"><span className="boot-dot" /><p>{t("正在识别运行环境…")}</p></div></main>;
  }
  return mode === "host" ? <HostApp /> : <SaasApp />;
}

// iOS over plain http://LAN-IP is not a secure context, so the service worker is
// optional by design: no SW registration must never block the app.
if ("serviceWorker" in navigator && window.isSecureContext) {
  window.addEventListener("load", () => {
    navigator.serviceWorker.register("/sw.js").catch(() => undefined);
  });
}
createRoot(document.getElementById("root")!).render(<StrictMode><App /></StrictMode>);
