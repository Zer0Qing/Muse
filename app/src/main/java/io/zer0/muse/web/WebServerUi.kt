package io.zer0.muse.web

/**
 * WebServer 根路径 UI（单页应用：PIN 登录 → 会话列表 → 消息只读浏览）。
 * 纯内嵌 HTML/CSS/JS，无外部依赖，移动端/桌面浏览器均可使用。
 * 鉴权走 Cookie（登录接口自动种 httpOnly Cookie，前端无需管理 token）。
 *
 * 视觉遵循 Muse mono 主题设计语言：纯黑白极简，跟随系统深浅色
 * （浅色：白底黑字；深色：纯黑底白字，卡片 #1C1C1E）。
 */
object WebServerUi {

    const val INDEX_HTML: String = """<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<meta name="referrer" content="no-referrer">
<title>Muse · Web</title>
<style>
:root{
  --bg:#ffffff; --surface:#f7f7f8; --surface2:#eaeaec; --line:#e4e4e7;
  --ink:#000000; --text:#1c1c1e; --muted:#6b6b6b; --dim:#9a9a9f;
  --user-bubble:#000000; --user-ink:#ffffff; --ai-bubble:#f2f2f3; --ai-ink:#1c1c1e;
  --ok:#1a7f37; --err:#d93025;
}
@media (prefers-color-scheme: dark){
  :root{
    --bg:#000000; --surface:#1c1c1e; --surface2:#2c2c2e; --line:#2c2c2e;
    --ink:#ffffff; --text:#f2f2f3; --muted:#98989f; --dim:#6b6b70;
    --user-bubble:#ffffff; --user-ink:#000000; --ai-bubble:#1c1c1e; --ai-ink:#f2f2f3;
    --ok:#81c995; --err:#f28b82;
  }
}
*{box-sizing:border-box;margin:0;padding:0}
body{background:var(--bg);color:var(--ink);font:14px/1.6 -apple-system,BlinkMacSystemFont,"Segoe UI","PingFang SC","Microsoft YaHei",sans-serif;-webkit-font-smoothing:antialiased;min-height:100vh}
.wrap{max-width:680px;margin:0 auto;padding:20px 16px 40px}
a{color:var(--ink)}
/* ── 顶栏 ── */
.top{display:flex;align-items:center;gap:8px;padding:4px 0 18px;border-bottom:1px solid var(--line)}
.top .logo{font-size:17px;font-weight:700;letter-spacing:.2px}
.top .sp{flex:1}
.top button{background:none;color:var(--text);border:1px solid var(--line);border-radius:7px;padding:5px 11px;font-size:12.5px;cursor:pointer;font-family:inherit}
.top button:hover{background:var(--surface)}
/* ── 登录 ── */
.login{max-width:340px;margin:18vh auto 0;text-align:center}
.login .brand{font-size:21px;font-weight:700;margin-bottom:4px}
.login .sub{color:var(--muted);font-size:13px;margin-bottom:30px}
.login input{width:100%;padding:13px;font-size:21px;letter-spacing:14px;text-align:center;background:var(--surface);color:var(--ink);border:1px solid var(--line);border-radius:8px;outline:none;margin-bottom:14px;caret-color:var(--ink)}
.login input:focus{border-color:var(--ink)}
.login .btn{width:100%;padding:12px;font-size:14px;font-weight:600;background:var(--ink);color:var(--bg);border:none;border-radius:8px;cursor:pointer;font-family:inherit}
.login .btn:disabled{opacity:.4}
.login .err{color:var(--err);font-size:13px;margin-top:12px;min-height:18px}
.login .tip{color:var(--dim);font-size:11.5px;margin-top:24px}
/* ── 会话列表 ── */
.list{display:flex;flex-direction:column}
.item{display:block;width:100%;text-align:left;background:none;border:none;border-bottom:1px solid var(--line);padding:13px 2px;cursor:pointer;color:var(--ink);font-family:inherit}
.item:last-child{border-bottom:none}
.item:hover .t{text-decoration:underline}
.item .t{font-size:14px;font-weight:600;display:flex;align-items:center;gap:8px}
.item .pin{font-size:10px;border:1px solid var(--ink);border-radius:3px;padding:0 4px;color:var(--ink)}
.item .p{color:var(--muted);font-size:12.5px;margin-top:3px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.item .m{color:var(--dim);font-size:11px;margin-top:4px}
.empty{color:var(--dim);text-align:center;padding:48px 0;font-size:13px}
/* ── 消息（对齐 App：用户右黑底白字，AI 左浅灰底）── */
.msg{display:flex;margin:14px 0;gap:8px}
.msg .av{width:26px;height:26px;border-radius:50%;display:flex;align-items:center;justify-content:center;font-size:11px;font-weight:700;flex-shrink:0;margin-top:2px}
.msg.user{flex-direction:row-reverse}
.msg.user .av{background:var(--surface2);color:var(--muted)}
.msg.ai .av{background:var(--surface2);color:var(--muted)}
.msg .body{max-width:84%;display:flex;flex-direction:column;align-items:flex-start}
.msg.user .body{align-items:flex-end}
.msg .b{padding:9px 13px;font-size:13.5px;white-space:pre-wrap;word-break:break-word;line-height:1.65}
.msg.user .b{background:var(--user-bubble);color:var(--user-ink);border-radius:16px 16px 4px 16px}
.msg.ai .b{background:var(--ai-bubble);color:var(--ai-ink);border-radius:4px 16px 16px 16px}
.msg .b code{background:var(--surface2);padding:1px 5px;border-radius:4px;font-size:12px;font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace}
.msg.user .b code{background:rgba(128,128,128,.25)}
.msg .b pre{background:var(--surface2);border-radius:8px;padding:10px;margin:6px 0;overflow:auto;font-size:12.5px;font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace}
.msg .b a{text-decoration:underline}
.msg .meta{font-size:11px;color:var(--dim);margin-top:4px;padding:0 4px}
.msg .rs{font-size:12px;color:var(--muted);background:var(--surface);border:1px solid var(--line);border-radius:8px;padding:8px 10px;margin-bottom:6px;white-space:pre-wrap;word-break:break-word;max-height:140px;overflow:auto;width:100%}
.back{display:inline-flex;align-items:center;gap:4px;background:none;border:none;color:var(--muted);cursor:pointer;font-size:12.5px;margin-bottom:4px;padding:6px 0;font-family:inherit}
.back:hover{color:var(--ink)}
/* ── 设置 ── */
.settings h2{font-size:13px;font-weight:600;color:var(--muted);margin:22px 0 8px;letter-spacing:.3px}
.row{display:flex;justify-content:space-between;align-items:center;border-bottom:1px solid var(--line);padding:11px 2px}
.row .n{font-weight:600;font-size:13.5px}
.row .d{color:var(--muted);font-size:12px;margin-top:1px}
.badge{font-size:11px;color:var(--dim);flex-shrink:0}
.badge.on{color:var(--ok)}
.loading{color:var(--dim);text-align:center;padding:40px 0;font-size:13px}
.hide{display:none!important}
</style>
</head>
<body>
<div class="wrap">

  <!-- 登录页 -->
  <div id="view-login" class="login">
    <div class="brand">Muse</div>
    <div class="sub">输入手机端设置页生成的 6 位 PIN</div>
    <input id="pin" type="password" inputmode="numeric" maxlength="6" placeholder="······" autocomplete="off">
    <button id="login-btn" class="btn">连接</button>
    <div id="login-err" class="err"></div>
    <div class="tip">仅限本机与已授权的局域网设备访问</div>
  </div>

  <!-- 主界面 -->
  <div id="view-main" class="hide">
    <div class="top">
      <span class="logo">Muse</span>
      <span class="sp"></span>
      <button id="btn-refresh">刷新</button>
      <button id="btn-settings">设置</button>
      <button id="btn-logout">退出</button>
    </div>
    <div id="page-sessions"></div>
    <div id="page-messages" class="hide"></div>
    <div id="page-settings" class="hide"></div>
  </div>

</div>
<script>
(function(){
"use strict";
var $=function(id){return document.getElementById(id)};
var state={view:"login",session:null};

/* ── 视图切换 ── */
function show(v){
  state.view=v;
  $("view-login").classList.toggle("hide",v!=="login");
  $("view-main").classList.toggle("hide",v==="login");
  $("page-sessions").classList.toggle("hide",v!=="sessions");
  $("page-messages").classList.toggle("hide",v!=="messages");
  $("page-settings").classList.toggle("hide",v!=="settings");
}
function fmtTime(ms){
  try{var d=new Date(ms);var p=function(n){return n<10?"0"+n:""+n};
    return d.getFullYear()+"-"+p(d.getMonth()+1)+"-"+p(d.getDate())+" "+p(d.getHours())+":"+p(d.getMinutes());
  }catch(e){return ""}
}
function esc(s){return String(s==null?"":s).replace(/&/g,"&amp;").replace(/</g,"&lt;").replace(/>/g,"&gt;")}
function inlineMd(s){
  var t=esc(s);
  t=t.replace(/```([\s\S]*?)```/g,"<pre>$1</pre>");
  t=t.replace(/\*\*(.+?)\*\*/g,"<b>$1</b>");
  t=t.replace(/`([^`]+)`/g,"<code>$1</code>");
  t=t.replace(/^&gt;\s?(.*)$/gm,"<blockquote style='border-left:2px solid var(--dim);padding-left:8px;color:var(--muted);margin:4px 0'>$1</blockquote>");
  t=t.replace(/(https?:\/\/[^\s<]+)/g,"<a href='$1' target='_blank' rel='noopener'>$1</a>");
  return t;
}
function api(path){
  return fetch(path,{credentials:"same-origin"}).then(function(r){
    if(r.status===401){show("login");throw new Error("unauthorized")}
    if(!r.ok){return r.json().then(function(j){throw new Error(j.message||j.error||("HTTP "+r.status))})}
    return r.json();
  });
}

/* ── 登录 ── */
function doLogin(){
  var pin=$("pin").value.trim();
  if(pin.length!==6){$("login-err").textContent="请输入 6 位 PIN";return}
  $("login-btn").disabled=true;$("login-err").textContent="";
  fetch("/api/auth/pin-login",{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify({pin:pin})})
    .then(function(r){
      if(!r.ok){return r.json().then(function(j){throw new Error(j.message||"PIN 错误")})}
      return r.json();
    })
    .then(function(){ $("pin").value=""; loadSessions(); })
    .catch(function(e){ $("login-err").textContent=e.message||"连接失败"; })
    .finally(function(){ $("login-btn").disabled=false; });
}
$("login-btn").onclick=doLogin;
$("pin").addEventListener("keydown",function(e){if(e.key==="Enter")doLogin()});

/* ── 会话列表 ── */
function loadSessions(){
  show("sessions");
  $("page-sessions").innerHTML='<div class="loading">加载中…</div>';
  api("/api/sessions").then(function(list){
    if(!list||!list.length){$("page-sessions").innerHTML='<div class="empty">暂无会话</div>';return}
    var h="<div class='list'>";
    list.forEach(function(s){
      h+="<div class='item' onclick='window.__openSession(\""+s.id+"\")'>"
        +"<div class='t'>"+esc(s.title||"未命名")+(s.pinned?"<span class='pin'>置顶</span>":"")+"</div>"
        +"<div class='p'>"+esc(s.lastMessagePreview||"")+"</div>"
        +"<div class='m'>"+fmtTime(s.updatedAt||s.createdAt)+"</div></div>";
    });
    h+="</div>";
    $("page-sessions").innerHTML=h;
  }).catch(function(e){$("page-sessions").innerHTML='<div class="empty">加载失败：'+esc(e.message)+'</div>'});
}
window.__openSession=function(id){
  state.session=id;
  show("messages");
  $("page-messages").innerHTML='<button class="back" onclick="window.__backList()">← 返回</button><div class="loading">加载中…</div>';
  api("/api/sessions/"+encodeURIComponent(id)+"/messages").then(function(msgs){
    if(!msgs||!msgs.length){$("page-messages").innerHTML='<button class="back" onclick="window.__backList()">← 返回</button><div class="empty">暂无消息</div>';return}
    var h="<button class='back' onclick='window.__backList()'>← 返回</button>";
    msgs.forEach(function(m){
      var isUser=m.role==="USER"||m.role==="user";
      h+="<div class='msg "+(isUser?"user":"ai")+"'>"
        +"<div class='av'>"+(isUser?"我":"M")+"</div><div class='body'>";
      if(m.reasoning)h+="<div class='rs'>"+esc(m.reasoning)+"</div>";
      h+="<div class='b'>"+inlineMd(m.content||"")+"</div>";
      h+="<div class='meta'>"+(m.modelId?esc(m.modelId)+" · ":"")+fmtTime(m.createdAt)+"</div></div></div>";
    });
    $("page-messages").innerHTML=h;
  }).catch(function(e){$("page-messages").innerHTML='<button class="back" onclick="window.__backList()">← 返回</button><div class="empty">加载失败：'+esc(e.message)+'</div>'});
};
window.__backList=function(){loadSessions()};

/* ── 设置 ── */
function loadSettings(){
  show("settings");
  $("page-settings").innerHTML='<div class="loading">加载中…</div>';
  api("/api/settings").then(function(s){
    var h="<h2>当前模型</h2>"
      +"<div class='row'><div><div class='n'>"+esc(s.selectedModelId||"未选择")+"</div><div class='d'>Provider: "+esc(s.activeProviderId||"无")+"</div></div></div>"
      +"<h2>模型供应商</h2>";
    if(!s.providers||!s.providers.length)h+='<div class="empty">暂无供应商</div>';
    s.providers.forEach(function(p){
      h+="<div class='row'><div><div class='n'>"+esc(p.name)+"</div><div class='d'>"+esc(p.type)+" · "+p.modelCount+" 个模型</div></div><span class='badge "+(p.enabled?"on":"")+"'>"+(p.enabled?"启用":"停用")+"</span></div>";
    });
    $("page-settings").innerHTML=h;
  }).catch(function(e){$("page-settings").innerHTML='<div class="empty">加载失败：'+esc(e.message)+'</div>'});
}

/* ── 顶栏按钮 ── */
$("btn-refresh").onclick=function(){
  if(state.view==="sessions")loadSessions();
  else if(state.view==="messages"&&state.session)window.__openSession(state.session);
  else if(state.view==="settings")loadSettings();
};
$("btn-settings").onclick=loadSettings;
$("btn-logout").onclick=function(){
  document.cookie="muse_token=;Max-Age=0;path=/";
  show("login");
};

/* ── 初始 ── */
api("/api/sessions").then(function(){loadSessions()}).catch(function(){show("login")});
})();
</script>
</body>
</html>"""
}
