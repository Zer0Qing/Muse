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
  connectHost,
  hostLogout,
  hostPinLogin,
  type HostConnection,
  type HostConnectionStatus,
  type HostEvent,
  type HostMessage,
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
import "./styles.css";

const defaultProvider: ProviderConfig = { id: "request-provider", type: "openai", displayName: "OpenAI-compatible", baseUrl: "https://api.openai.com/v1", modelIds: [], supportsStreaming: true };
const providerTypes: ProviderConfig["type"][] = ["openai", "anthropic", "gemini", "deepseek", "openai_responses", "openai_compatible", "custom"];

function Login({ onLogin }: { onLogin: (session: ClientSession) => void }) {
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [registering, setRegistering] = useState(false);
  const [error, setError] = useState("");
  async function submit() {
    try { setError(""); if (registering) { await register(email, password); setRegistering(false); setError("账号已创建，请登录"); } else onLogin(await login(email, password)); }
    catch (cause) { setError(cause instanceof Error ? cause.message : "请求失败"); }
  }
  return <section className="panel auth-panel"><p className="eyebrow">MUSE WEB · PWA</p><h1>{registering ? "创建账号" : "欢迎回来"}</h1><p className="lede">BYOK 优先。API Key 只在本次浏览器会话内使用，不写入本地存储。</p><input aria-label="邮箱" placeholder="邮箱" value={email} onChange={(event) => setEmail(event.target.value)} /><input aria-label="密码" type="password" placeholder="至少 12 位密码" value={password} onChange={(event) => setPassword(event.target.value)} /><button type="button" onClick={() => void submit()}>{registering ? "注册" : "登录"}</button><button className="quiet" type="button" onClick={() => { setRegistering(!registering); setError(""); }}>{registering ? "已有账号，登录" : "创建新账号"}</button>{error && <p className="error">{error}</p>}</section>;
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
    } catch (cause) { setError(cause instanceof Error ? cause.message : "Provider 保存失败"); }
  }
  return <div className="workspace-section"><div className="section-title"><div><p className="eyebrow">PROVIDER VAULT</p><h3>我的 Provider</h3></div><span className="muted">密钥只写入服务端密文库，列表不会回显</span></div><div className="provider-cards">{providers.map((item) => <button className="saved-provider" type="button" key={item.id} onClick={() => { setId(item.id); setName(item.displayName); setType(item.type); setBaseUrl(item.baseUrl); setModels(item.modelIds.join(", ")); }}><b>{item.displayName}</b><small>{item.type} · {item.hasSecret ? "已配置密钥" : "缺少密钥"}</small></button>)}</div><div className="form-grid"><input placeholder="配置 ID" value={id} onChange={(event) => setId(event.target.value)} /><input placeholder="显示名称" value={name} onChange={(event) => setName(event.target.value)} /><select aria-label="Provider 类型" value={type} onChange={(event) => setType(event.target.value as ProviderConfig["type"])}>{providerTypes.map((item) => <option key={item} value={item}>{item}</option>)}</select><input placeholder="Base URL（HTTPS）" value={baseUrl} onChange={(event) => setBaseUrl(event.target.value)} /><input placeholder="模型 ID，逗号分隔" value={models} onChange={(event) => setModels(event.target.value)} /><input type="password" placeholder="API Key（保存时写入）" value={apiKey} onChange={(event) => setApiKey(event.target.value)} /></div><button type="button" disabled={!apiKey.trim()} onClick={() => void submit()}>保存 Provider</button>{error && <p className="error">{error}</p>}</div>;
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
    catch (cause) { setError(cause instanceof Error ? cause.message : "Assistant 保存失败"); }
  }
  const update = (key: keyof AssistantDraft, value: string) => setDraft((current) => ({ ...current, [key]: value }));
  return <div className="workspace-section"><div className="section-title"><div><p className="eyebrow">ASSISTANT STUDIO</p><h3>Assistant 人格</h3></div><span className="muted">提示词参与后续聊天绑定</span></div>{assistants.length > 0 && <select aria-label="选择 Assistant" value={selected?.id ?? ""} onChange={(event) => setSelected(assistants.find((item) => item.id === event.target.value))}>{assistants.map((item) => <option value={item.id} key={item.id}>{item.name}</option>)}</select>}<div className="form-grid"><input placeholder="名称" value={draft.name} onChange={(event) => update("name", event.target.value)} /><textarea placeholder="身份提示词" value={draft.identityPrompt} onChange={(event) => update("identityPrompt", event.target.value)} /><textarea placeholder="关系提示词" value={draft.relationshipPrompt} onChange={(event) => update("relationshipPrompt", event.target.value)} /><textarea placeholder="风格提示词" value={draft.stylePrompt} onChange={(event) => update("stylePrompt", event.target.value)} /></div><button type="button" disabled={!draft.name.trim()} onClick={() => void save()}>{selected ? "保存 Assistant" : "创建 Assistant"}</button>{error && <p className="error">{error}</p>}</div>;
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
  async function runMemorySearch() { try { setFacts(await searchMemory(session, memoryQuery, assistantId, spaceId)); } catch (cause) { setError(cause instanceof Error ? cause.message : "记忆搜索失败"); } }
  async function createSpace() { try { setError(""); const space = await createMemorySpace(session, spaceName); setSpaceId(space.id); setSpaceName(""); } catch (cause) { setError(cause instanceof Error ? cause.message : "记忆空间创建失败"); } }
  async function addFact() { try { setError(""); if (!spaceId) throw new Error("请先创建或填写 Space ID"); await addMemoryFact(session, { assistantId, spaceId, content: factText, importance: 1 }); setFactText(""); if (memoryQuery) await runMemorySearch(); } catch (cause) { setError(cause instanceof Error ? cause.message : "记忆保存失败"); } }
  async function runRagSearch() { try { const result = await searchRag(session, ragQuery); setChunks(result.chunks); } catch (cause) { setError(cause instanceof Error ? cause.message : "RAG 搜索失败"); } }
  async function indexText() { try { setError(""); await indexRagDocument(session, `web-${crypto.randomUUID()}`, documentText); setDocumentText(""); } catch (cause) { setError(cause instanceof Error ? cause.message : "文档索引失败"); } }
  return <div className="workspace-section"><div className="section-title"><div><p className="eyebrow">MEMORY · RAG</p><h3>知识与记忆</h3></div><span className="muted">当前 Assistant：{assistantId === "default" ? "默认" : assistantId.slice(0, 8)}</span></div><div className="knowledge-grid"><div className="knowledge-card"><h4>记忆事实</h4><div className="inline-form"><input placeholder="新建 Memory Space" value={spaceName} onChange={(event) => setSpaceName(event.target.value)} /><button type="button" disabled={!spaceName.trim()} onClick={() => void createSpace()}>新建</button></div><input placeholder="Space ID" value={spaceId} onChange={(event) => setSpaceId(event.target.value)} /><div className="inline-form"><input placeholder="添加一条事实" value={factText} onChange={(event) => setFactText(event.target.value)} /><button type="button" disabled={!factText.trim()} onClick={() => void addFact()}>记住</button></div><div className="inline-form"><input placeholder="搜索记忆" value={memoryQuery} onChange={(event) => setMemoryQuery(event.target.value)} /><button type="button" onClick={() => void runMemorySearch()}>搜索</button></div>{facts.map((fact) => <p className="result-line" key={fact.id}>{fact.content}<small>重要度 {fact.importance} · 命中 {fact.hitCount}</small></p>)}</div><div className="knowledge-card"><h4>RAG 文档</h4><textarea placeholder="粘贴文本，创建索引" value={documentText} onChange={(event) => setDocumentText(event.target.value)} /><button type="button" disabled={!documentText.trim()} onClick={() => void indexText()}>索引文本</button><div className="inline-form"><input placeholder="搜索已索引内容" value={ragQuery} onChange={(event) => setRagQuery(event.target.value)} /><button type="button" onClick={() => void runRagSearch()}>搜索</button></div>{chunks.map((chunk) => <p className="result-line" key={chunk.id}>{chunk.text}<small>{chunk.documentId} · {chunk.tokenEstimate} tokens</small></p>)}</div></div>{error && <p className="error">{error}</p>}</div>;
}

function FilePanel({ session }: { session: ClientSession }) {
  const [message, setMessage] = useState("选择文件后先向 API 申请一次性上传地址");
  async function upload(file: File) {
    try {
      const fileId = crypto.randomUUID();
      const plan = await presignFile(session, { fileId, name: file.name, mimeType: file.type, sizeBytes: file.size });
      const response = await fetch(plan.upload.url, { method: "PUT", headers: { "content-type": file.type }, body: file });
      if (!response.ok) throw new Error(`对象存储上传失败（${response.status}）`);
      const digest = await crypto.subtle.digest("SHA-256", await file.arrayBuffer());
      const sha256 = Array.from(new Uint8Array(digest), (byte) => byte.toString(16).padStart(2, "0")).join("");
      await completeFile(session, { fileId, name: file.name, mimeType: file.type, sizeBytes: file.size, objectKey: plan.objectKey, sha256 });
      setMessage(`${file.name} 已上传并登记，地址有效至 ${new Date(plan.upload.expiresAt).toLocaleTimeString()}`);
    } catch (cause) { setMessage(cause instanceof Error ? cause.message : "文件上传失败"); }
  }
  return <div className="workspace-section"><div className="section-title"><div><p className="eyebrow">OBJECT STORAGE</p><h3>文件</h3></div><span className="muted">API 不接收文件字节，浏览器直传 S3-compatible 存储</span></div><label className="file-drop">选择文件<input type="file" onChange={(event) => { const file = event.target.files?.[0]; if (file) void upload(file); }} /></label><p className="muted">{message}</p></div>;
}

function PushPanel({ session }: { session: ClientSession }) {
  const capabilities = useMemo(() => detectWebCapabilities(), []);
  const [message, setMessage] = useState(capabilities.push ? "浏览器支持 Push" : "当前浏览器不支持 Push");
  async function enable() {
    try { if (!capabilities.push || !capabilities.notifications) throw new Error("当前浏览器不支持通知"); const key = import.meta.env.VITE_VAPID_PUBLIC_KEY; if (!key) throw new Error("未配置 VITE_VAPID_PUBLIC_KEY"); const permission = await Notification.requestPermission(); if (permission !== "granted") throw new Error("通知权限未授予"); const registration = await navigator.serviceWorker.ready; const subscription = await registration.pushManager.subscribe({ userVisibleOnly: true, applicationServerKey: Uint8Array.from(atob(key.replace(/-/g, "+").replace(/_/g, "/")), (char) => char.charCodeAt(0)) }); await registerPushSubscription(session, subscription); setMessage("Push 已启用"); } catch (cause) { setMessage(cause instanceof Error ? cause.message : "Push 启用失败"); }
  }
  return <div className="workspace-section compact-section"><div><p className="eyebrow">PWA</p><h3>通知与离线能力</h3></div><p className="muted">{message}</p><button type="button" disabled={!capabilities.push} onClick={() => void enable()}>启用通知</button></div>;
}

function MessageContent({ content }: { content: string }) {
  const blocks = content.split(/```/);
  return <div className="message-content">{blocks.map((block, index) => {
    if (index % 2 === 1) {
      const lines = block.replace(/^\w+\r?\n/, "");
      return <pre key={`code-${index}`}><code>{lines}</code></pre>;
    }
    return block.split(/\n{2,}/).filter(Boolean).map((paragraph, paragraphIndex) => (
      <p key={`paragraph-${index}-${paragraphIndex}`}>{paragraph}</p>
    ));
  })}</div>;
}

function Workspace({ session, onLogout }: { session: ClientSession; onLogout: () => void }) {
  const [providers, setProviders] = useState<StoredProviderConfig[]>([]);
  const [assistants, setAssistants] = useState<AssistantRecord[]>([]);
  const [open, setOpen] = useState(false);
  const [error, setError] = useState("");
  useEffect(() => { const openWorkspace = () => setOpen(true); window.addEventListener("muse:workspace", openWorkspace); return () => window.removeEventListener("muse:workspace", openWorkspace); }, []);
  useEffect(() => { void Promise.all([listProviders(session), listAssistants(session)]).then(([nextProviders, nextAssistants]) => { setProviders(nextProviders); setAssistants(nextAssistants); }).catch((cause) => setError(cause instanceof Error ? cause.message : "工作区加载失败")); }, [session]);
  const assistantId = assistants[0]?.id ?? "default";
  return <>{open && <button className="workspace-scrim" type="button" aria-label="关闭设置" onClick={() => setOpen(false)} />}<aside className={open ? "workspace open" : "workspace"}><div className="workspace-head"><div><p className="eyebrow">MUSE WORKSPACE</p><h3>能力设置</h3></div><button className="quiet" type="button" onClick={() => setOpen(false)}>收起</button></div><ProviderEditor session={session} providers={providers} onSaved={(provider) => setProviders((items) => [provider, ...items.filter((item) => item.id !== provider.id)])} /><AssistantEditor session={session} assistants={assistants} onSaved={(assistant) => setAssistants((items) => [assistant, ...items.filter((item) => item.id !== assistant.id)])} /><KnowledgePanel session={session} assistantId={assistantId} /><FilePanel session={session} /><PushPanel session={session} />{error && <p className="error">{error}</p>}<button className="quiet logout-workspace" type="button" onClick={onLogout}>退出登录</button></aside></>;
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
  useEffect(() => { void Promise.all([refresh(), listProviders(session), listAssistants(session)]).then(([, nextProviders, nextAssistants]) => { setProviders(nextProviders); setAssistants(nextAssistants); if (nextProviders[0]) { setProviderId(nextProviders[0].id); if (nextProviders[0].modelIds[0]) setModel(nextProviders[0].modelIds[0]); } }).catch((cause) => setError(cause instanceof Error ? cause.message : "加载失败")); }, []);
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
      if (!controller.signal.aborted) { setError(cause instanceof Error ? cause.message : "发送失败"); setMessages(history); }
    } finally { if (abortRef.current === controller) abortRef.current = null; setBusy(false); }
  }
  return <><main className="app-layout">{mobileSidebarOpen && <button className="mobile-sidebar-scrim" type="button" aria-label="关闭会话列表" onClick={() => setMobileSidebarOpen(false)} />}<aside className={mobileSidebarOpen ? "sidebar mobile-open" : "sidebar"}><div className="brand"><b>Muse</b><span>Web</span></div><button type="button" onClick={() => void newChat()}>＋ 新对话</button><button className="workspace-toggle" type="button" onClick={() => window.dispatchEvent(new Event("muse:workspace"))}>设置 Provider / Assistant</button><div className="session-list">{sessions.map((item) => <button className={current?.id === item.id ? "session active" : "session"} key={item.id} type="button" onClick={async () => { setCurrent(item); setAssistantId(item.assistantId); setMessages(await listMessages(session, item.id)); setMobileSidebarOpen(false); }}>{item.title}</button>)}</div><button className="quiet" type="button" onClick={onLogout}>退出登录</button></aside><section className="chat"><header><button className="mobile-menu-button" type="button" aria-label="打开会话列表" onClick={() => setMobileSidebarOpen(true)}>☰</button><div><p className="eyebrow">PRIVATE CONVERSATION</p><h2>{current?.title ?? "新对话"}</h2></div><span className="status">{busy ? "生成中…" : "在线"}</span></header><div className="messages" role="log" aria-live="polite" aria-label="消息记录">{messages.length === 0 && <div className="empty"><h3>从一句话开始</h3><p>在设置面板配置 Provider、Assistant、记忆和文件能力。</p></div>}{messages.map((message) => <article className={`message ${message.role}`} key={message.id}><small>{message.role === "user" ? "你" : "Muse"}</small><MessageContent content={message.content || "…"} />{message.reasoning && <details><summary>思考过程</summary><p>{message.reasoning}</p></details>}</article>)}</div><div className="composer"><div className="provider-row"><select aria-label="费用模式" value={mode} onChange={(event) => setMode(event.target.value as "byok" | "hosted")}><option value="byok">BYOK · 我的 Key</option><option value="hosted">Hosted · 平台额度</option></select>{mode === "byok" && <select aria-label="传输方式" value={transport} onChange={(event) => setTransport(event.target.value as "direct" | "proxy")}><option value="direct">直连 Provider</option><option value="proxy">服务端代理（已保存 Provider）</option></select>}<select aria-label="Assistant" value={assistantId} onChange={(event) => setAssistantId(event.target.value)}><option value="default">默认 Assistant</option>{assistants.map((item) => <option value={item.id} key={item.id}>{item.name}</option>)}</select><select aria-label="Provider" value={providerId} onChange={(event) => { setProviderId(event.target.value); const selected = providers.find((item) => item.id === event.target.value); if (selected?.modelIds[0]) setModel(selected.modelIds[0]); }}>{providers.length === 0 && <option value="">临时 Provider</option>}{providers.map((item) => <option value={item.id} key={item.id}>{item.displayName}</option>)}</select><input aria-label="模型" value={model} onChange={(event) => setModel(event.target.value)} />{mode === "byok" && transport === "direct" && <input aria-label="API Key" type="password" placeholder="本次会话 API Key" value={apiKey} onChange={(event) => setApiKey(event.target.value)} />}</div><div className="send-row"><textarea aria-label="消息" placeholder="写点什么…" value={input} onChange={(event) => setInput(event.target.value)} onKeyDown={(event) => { if (event.key === "Enter" && !event.shiftKey) { event.preventDefault(); void send(); } }} /><button type="button" className={busy ? "stop-button" : "send-button"} disabled={busy ? false : (needsKey(mode, transport) && !apiKey.trim())} onClick={() => busy ? stop() : void send()}>{busy ? "停止" : "发送"}</button></div>{error && <p className="error">{error}</p>}</div></section></main><Workspace session={session} onLogout={onLogout} /></>;
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

function HostLogin({ onLogin }: { onLogin: () => void }) {
  const [pin, setPin] = useState("");
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  async function submit() {
    if (!/^\d{6}$/.test(pin)) { setError("请输入 6 位 PIN"); return; }
    setBusy(true); setError("");
    try { await hostPinLogin(pin); onLogin(); }
    catch (cause) { setError(cause instanceof Error ? cause.message : "Host 连接失败"); }
    finally { setBusy(false); }
  }
  return <section className="panel auth-panel host-login"><p className="eyebrow">MUSE HOST · LOCAL RUNTIME</p><h1>连接 Muse</h1><p className="lede">网页只负责展示，模型、记忆、工具和数据都由手机上的 Muse 负责处理。</p><input aria-label="Host PIN" inputMode="numeric" maxLength={6} type="password" placeholder="手机端 6 位 PIN" value={pin} onChange={(event) => setPin(event.target.value.replace(/\D/g, ""))} onKeyDown={(event) => { if (event.key === "Enter") void submit(); }} /><button type="button" disabled={busy} onClick={() => void submit()}>{busy ? "连接中…" : "连接 Host"}</button>{error && <p className="error">{error}</p>}</section>;
}

function HostMessageContent({ message, isLastAssistant, onRegenerate, onContinue }: { message: HostMessage; isLastAssistant: boolean; onRegenerate: () => void; onContinue: () => void }) {
  return <article className={`message ${message.role}`} key={message.id}><small>{message.role === "user" ? "你" : message.role === "tool" ? "工具" : "Muse"}</small><MessageContent content={message.content || "…"} />{message.reasoning && <details><summary>思考过程</summary><p>{message.reasoning}</p></details>}{message.toolName && <details><summary>工具调用：{message.toolName}</summary><p>{message.content}</p></details>}{isLastAssistant && <div className="message-actions"><button className="quiet" type="button" onClick={onRegenerate}>重新生成</button><button className="quiet" type="button" onClick={onContinue}>继续生成</button></div>}</article>;
}

function HostChat({ onLogout }: { onLogout: () => void }) {
  const [connection, setConnection] = useState<HostConnection>();
  const [connectionStatus, setConnectionStatus] = useState<HostConnectionStatus>("reconnecting");
  const [sessions, setSessions] = useState<NonNullable<HostEvent["sessions"]>>([]);
  const [messages, setMessages] = useState<HostMessage[]>([]);
  const [currentSessionId, setCurrentSessionId] = useState<string>();
  const [input, setInput] = useState("");
  const [isStreaming, setIsStreaming] = useState(false);
  const [pendingApprovals, setPendingApprovals] = useState<NonNullable<HostEvent["pendingApprovals"]>>([]);
  const [error, setError] = useState("");
  const [mobileSidebarOpen, setMobileSidebarOpen] = useState(false);
  const connectionRef = useRef<HostConnection | undefined>(undefined);

  useEffect(() => {
    let active = true;
    let unsubscribeEvents: (() => void) | undefined;
    let unsubscribeStatus: (() => void) | undefined;
    void connectHost().then((next) => {
      if (!active) { next.close(); return; }
      setConnection(next);
      connectionRef.current = next;
      unsubscribeStatus = next.onStatus((status) => {
        if (!active) return;
        setConnectionStatus(status);
        if (status === "connected") setError("");
        if (status === "reconnecting") setError("手机 Host 连接已断开，正在重新连接…");
      });
      unsubscribeEvents = next.subscribe((event) => {
        if (event.type === "state.snapshot") {
          setSessions([...(event.sessions ?? [])]);
          setMessages([...(event.messages ?? [])]);
          setCurrentSessionId(event.sessionId);
          setIsStreaming(event.isStreaming === true);
          setPendingApprovals([...(event.pendingApprovals ?? [])]);
        }
        if (event.type === "error") setError(event.error ?? "Host 命令失败");
      });
      next.send({ type: "hello", requestId: crypto.randomUUID() });
    }).catch((cause) => {
      if (active) {
        setConnectionStatus("closed");
        setError(cause instanceof Error ? cause.message : "无法连接 Muse Host");
      }
    });
    return () => {
      active = false;
      unsubscribeEvents?.();
      unsubscribeStatus?.();
      connectionRef.current?.close();
      connectionRef.current = undefined;
    };
  }, []);

  function sendCommand(command: Parameters<HostConnection["send"]>[0]) {
    if (!connection) { setError("Host 尚未连接"); return; }
    try {
      connection.send({ ...command, requestId: crypto.randomUUID() });
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "Host 暂时不可用");
    }
  }
  function selectSession(sessionId: string) {
    sendCommand({ type: "session.select", sessionId });
    setMobileSidebarOpen(false);
  }
  function renameSession(sessionId: string, currentTitle: string) {
    const title = window.prompt("会话名称", currentTitle)?.trim();
    if (title) sendCommand({ type: "session.rename", sessionId, title });
  }
  function archiveSession(sessionId: string) {
    sendCommand({ type: "session.archive", sessionId, archived: true });
  }
  function deleteSession(sessionId: string) {
    if (window.confirm("删除这个会话？")) sendCommand({ type: "session.delete", sessionId });
  }
  function resolveApproval(toolCallId: string, decision: "approved" | "denied") {
    sendCommand({ type: "tool.approval.resolve", generationId: toolCallId, text: decision });
  }
  function send() {
    const text = input.trim();
    if (!text || isStreaming) return;
    setInput(""); setError("");
    sendCommand({ type: "chat.send", ...(currentSessionId ? { sessionId: currentSessionId } : {}), text });
  }
  const current = sessions.find((item) => item.id === currentSessionId);
  return <main className="app-layout host-layout">{mobileSidebarOpen && <button className="mobile-sidebar-scrim" type="button" aria-label="关闭会话列表" onClick={() => setMobileSidebarOpen(false)} />}<aside className={mobileSidebarOpen ? "sidebar mobile-open" : "sidebar"}><div className="brand"><b>Muse</b><span>Host</span></div><button type="button" onClick={() => sendCommand({ type: "session.new" })}>＋ 新对话</button><div className="host-badge">手机主机运算 · Web 远程界面</div><div className="session-list">{sessions.map((item) => <div className="session-entry" key={item.id}><button className={currentSessionId === item.id ? "session active" : "session"} type="button" onClick={() => selectSession(item.id)}>{item.pinned ? "📌 " : ""}{item.title}</button><div className="session-actions"><button className="quiet" type="button" aria-label={`重命名 ${item.title}`} onClick={() => renameSession(item.id, item.title)}>改名</button><button className="quiet" type="button" aria-label={`归档 ${item.title}`} onClick={() => archiveSession(item.id)}>归档</button><button className="quiet danger-action" type="button" aria-label={`删除 ${item.title}`} onClick={() => deleteSession(item.id)}>删</button></div></div>)}</div><button className="quiet" type="button" onClick={() => { void hostLogout(); onLogout(); }}>退出 Host</button></aside><section className="chat"><header><button className="mobile-menu-button" type="button" aria-label="打开会话列表" onClick={() => setMobileSidebarOpen(true)}>☰</button><div><p className="eyebrow">MUSE HOST · {connectionStatus === "connected" ? "CONNECTED" : connectionStatus === "reconnecting" ? "RECONNECTING" : "OFFLINE"}</p><h2>{current?.title ?? "Muse"}</h2></div><span className={connectionStatus === "reconnecting" ? "status reconnecting-status" : isStreaming ? "status streaming-status" : "status"}>{connectionStatus === "reconnecting" ? "重连中…" : isStreaming ? "生成中…" : connectionStatus === "connected" ? "在线" : "离线"}</span></header><div className="messages" role="log" aria-live="polite" aria-label="Host 消息记录">{pendingApprovals.map((approval) => <div className="approval-card" key={approval.toolCallId}><b>需要批准工具：{approval.toolName}</b><pre>{approval.argumentsPreview}</pre><div><button type="button" onClick={() => resolveApproval(approval.toolCallId, "approved")}>批准</button><button type="button" className="quiet" onClick={() => resolveApproval(approval.toolCallId, "denied")}>拒绝</button></div></div>)}{messages.length === 0 && <div className="empty"><h3>从一句话开始</h3><p>此页面只展示手机 Muse 的真实会话状态。</p></div>}{messages.map((message, index) => <HostMessageContent key={message.id} message={message} isLastAssistant={message.role === "assistant" && index === messages.length - 1} onRegenerate={() => sendCommand({ type: "chat.regenerate", ...(currentSessionId ? { sessionId: currentSessionId } : {}) })} onContinue={() => sendCommand({ type: "chat.continue", ...(currentSessionId ? { sessionId: currentSessionId } : {}) })} />)}</div><div className="composer"><div className="host-runtime-note">Provider、记忆、工具和审批由手机 Muse Host 负责</div><div className="send-row"><textarea aria-label="消息" placeholder="写点什么…" value={input} onChange={(event) => setInput(event.target.value)} onKeyDown={(event) => { if (event.key === "Enter" && !event.shiftKey) { event.preventDefault(); send(); } }} /><button type="button" className={isStreaming ? "stop-button" : "send-button"} disabled={connectionStatus !== "connected" || (!isStreaming && !input.trim())} onClick={() => isStreaming ? sendCommand({ type: "chat.stop", ...(currentSessionId ? { sessionId: currentSessionId } : {}) }) : send()}>{isStreaming ? "停止" : "发送"}</button></div>{error && <p className="error">{error}</p>}</div></section></main>;
}

function App() {
  const [session, setSession] = useState<ClientSession | undefined>(readStoredClientSession);
  function handleLogin(value: ClientSession) { sessionStorage.setItem("muse.session", JSON.stringify(value)); setSession(value); }
  function logout() { sessionStorage.removeItem("muse.session"); setSession(undefined); }
  const hostMode = new URLSearchParams(window.location.search).get("mode") === "host" || import.meta.env.VITE_HOST_MODE === "true";
  const [hostAuthenticated, setHostAuthenticated] = useState(() => sessionStorage.getItem("muse.host") === "1");
  if (hostMode) {
    function hostLogoutAndClear() { sessionStorage.removeItem("muse.host"); setHostAuthenticated(false); }
    return hostAuthenticated ? <HostChat onLogout={hostLogoutAndClear} /> : <main className="shell"><HostLogin onLogin={() => { sessionStorage.setItem("muse.host", "1"); setHostAuthenticated(true); }} /></main>;
  }
  return session ? <Chat session={session} onLogout={logout} /> : <main className="shell"><Login onLogin={handleLogin} /></main>;
}

if ("serviceWorker" in navigator) window.addEventListener("load", () => navigator.serviceWorker.register("/sw.js"));
createRoot(document.getElementById("root")!).render(<StrictMode><App /></StrictMode>);
