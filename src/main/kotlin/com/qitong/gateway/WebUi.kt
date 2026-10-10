package com.qitong.gateway

/**
 * 綦桐AI网关 Web UI — 控制台风格
 * 设计原则：中性灰阶 + 单一强调色，无渐变堆砌、无 emoji 图标，
 * 信息均衡呈现，接近自托管运维面板（Grafana / Cloudflare Dashboard）的观感。
 * 桌面端：左侧分组导航；移动端：汉堡抽屉 + 底部导航。
 * 支持深色 / 浅色主题（localStorage 记忆，AdminJs4 控制）。
 */
object WebUi {

    private const val VER = "v1.122"

    fun loginHtml(): String = """<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>綦桐AI网关 · 登录</title>
<style>
*{margin:0;padding:0;box-sizing:border-box}
:root{--bg:#0B0D12;--surface:#12151C;--border:#232833;--inset:#0E1117;--primary:#3E7BFA;--text:#D6DAE1;--muted:#8791A0;--green:#3FB27F;--red:#E5636A}
body{font-family:-apple-system,"PingFang SC","Microsoft YaHei",sans-serif;min-height:100vh;background:var(--bg);display:flex;align-items:center;justify-content:center;color:var(--text);padding:16px}
.card{width:380px;max-width:100%;position:relative;z-index:1;background:rgba(18,21,28,.62);border:1px solid rgba(255,255,255,.09);border-radius:10px;padding:30px 28px;-webkit-backdrop-filter:blur(20px) saturate(1.6);backdrop-filter:blur(20px) saturate(1.6);box-shadow:inset 0 1px 0 rgba(255,255,255,.07),0 8px 28px rgba(0,0,0,.22)}
body::before{content:'';position:fixed;inset:0;z-index:0;pointer-events:none;background:radial-gradient(560px 400px at 82% 6%,rgba(62,123,250,.10),transparent 70%),radial-gradient(520px 380px at 6% 96%,rgba(62,123,250,.06),transparent 70%)}
.brand{font-size:17px;font-weight:700;letter-spacing:.5px;margin-bottom:2px}
.brand-sub{font-size:12px;color:var(--muted);margin-bottom:24px}
.tabs{display:flex;border-bottom:1px solid var(--border);margin-bottom:18px}
.tabs button{flex:1;background:none;border:0;color:var(--muted);font-size:14px;padding:10px 0;cursor:pointer;border-bottom:2px solid transparent}
.tabs button.on{color:var(--text);border-bottom-color:var(--primary);font-weight:600}
.pane{display:none}.pane.on{display:block}
label{display:block;font-size:12px;color:var(--muted);margin:12px 0 5px}
input{width:100%;padding:9px 12px;background:var(--inset);border:1px solid var(--border);border-radius:7px;color:var(--text);font-size:13px;outline:none;transition:border-color .15s}
input:focus{border-color:var(--primary)}
.btn{width:100%;margin-top:18px;padding:10px;border:0;border-radius:7px;background:var(--primary);color:#fff;font-size:14px;font-weight:600;cursor:pointer}
.btn:hover{filter:brightness(1.1)}
.msg{margin-top:12px;font-size:13px;min-height:18px;color:var(--red)}
.msg.ok{color:var(--green)}
.tip{margin-top:18px;font-size:11px;color:var(--muted);text-align:center;line-height:1.6}
</style>
</head>
<body>
<div class="card">
  <div class="brand">綦桐AI网关</div>
  <div class="brand-sub">AI API 中转与管理控制台</div>
  <div class="tabs">
    <button id="tabL" class="on" onclick="switchTab('L')">登录</button>
    <button id="tabR" onclick="switchTab('R')">注册</button>
  </div>
  <div class="pane on" id="paneL">
    <label>用户名</label><input id="lgUser" autocomplete="username">
    <label>密码</label><input id="lgPass" type="password" autocomplete="current-password" onkeydown="if(event.key==='Enter')doLogin()">
    <button class="btn" onclick="doLogin()">登 录</button>
  </div>
  <div class="pane" id="paneR">
    <label>用户名</label><input id="rgUser" autocomplete="username">
    <label>密码（至少6位）</label><input id="rgPass" type="password" autocomplete="new-password">
    <label>昵称（可选）</label><input id="rgName">
    <label>邀请码（可选）</label><input id="rgInvite">
    <button class="btn" onclick="doRegister()">注 册</button>
  </div>
  <div class="msg" id="msg"></div>
  <div class="tip">綦桐AI网关服务器版 $VER</div>
</div>
<script>
function $(id){return document.getElementById(id)}
function show(t,ok){var m=$('msg');m.textContent=t;m.className='msg'+(ok?' ok':'')}
function switchTab(k){
  $('tabL').className=k==='L'?'on':'';$('tabR').className=k==='R'?'on':'';
  $('paneL').className='pane'+(k==='L'?' on':'');$('paneR').className='pane'+(k==='R'?' on':'');
  show('');
}
async function postJson(url,body){
  var r=await fetch(url,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body)});
  try{return await r.json()}catch(e){return {code:-1,msg:'HTTP '+r.status}}
}
async function doLogin(){
  var u=$('lgUser').value.trim(),p=$('lgPass').value;
  if(!u||!p){show('请输入用户名和密码');return}
  show('登录中...');
  var r=await postJson('/api/auth/login',{username:u,password:p});
  if(r.code===0){localStorage.setItem('qt_token',r.data.token);show('登录成功，跳转中...',true);setTimeout(function(){location.href='/admin'},400)}
  else show(r.msg||'登录失败');
}
async function doRegister(){
  var u=$('rgUser').value.trim(),p=$('rgPass').value,n=$('rgName').value.trim(),inv=$('rgInvite').value.trim();
  if(!u){show('请输入用户名');return}
  if(!p||p.length<6){show('密码至少6个字符');return}
  show('注册中...');
  var r=await postJson('/api/auth/register',{username:u,password:p,displayName:n,inviteCode:inv});
  if(r.code===0){show('注册成功，请登录',true);$('lgUser').value=u;switchTab('L')}
  else show(r.msg||'注册失败');
}
</script>
</body>
</html>"""

    fun adminHtml(): String = """<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>綦桐AI网关 · 控制台</title>
<style>
${adminCss()}
</style>
</head>
<body>
<div id="app">
  <!-- 顶栏 -->
  <header class="topbar">
    <button class="burger" id="burger" onclick="toggleSidebar()" aria-label="菜单">
      <svg viewBox="0 0 24 24"><line x1="3" y1="6" x2="21" y2="6"/><line x1="3" y1="12" x2="21" y2="12"/><line x1="3" y1="18" x2="21" y2="18"/></svg>
    </button>
    <div class="topbar-title">綦桐AI网关 <span class="ver">$VER</span></div>
    <div class="topbar-right">
      <button class="tb-btn" onclick="openPalette()" title="搜索与命令 (Ctrl+K)">
        <svg viewBox="0 0 24 24"><circle cx="11" cy="11" r="7"/><line x1="21" y1="21" x2="16.5" y2="16.5"/></svg>
      </button>
      <button class="tb-btn" onclick="manualRefresh()" title="刷新当前页">
        <svg viewBox="0 0 24 24"><polyline points="23 4 23 10 17 10"/><path d="M20.49 15a9 9 0 1 1-2.12-9.36L23 10"/></svg>
      </button>
      <button class="tb-btn" onclick="toggleTheme()" id="themeBtn" title="切换主题">浅色</button>
      <button class="tb-btn" onclick="openThemeSettings()" title="主题外观与强调色">
        <svg viewBox="0 0 24 24"><path d="M12 2.7s6.3 6.6 6.3 10.6a6.3 6.3 0 1 1-12.6 0C5.7 9.3 12 2.7 12 2.7z"/></svg>
      </button>
      <button class="tb-btn" onclick="quickToggleGlass()" id="glassBtn" title="液态玻璃开关">玻璃</button>
      <span id="onlineDot" class="dot"></span>
      <span id="userInfo" class="user-name">未登录</span>
      <button class="logout-btn" onclick="doLogout()">退出</button>
    </div>
  </header>
  <div class="mask" id="mask" onclick="toggleSidebar()"></div>
  <!-- 侧边导航 -->
  <aside class="sidebar" id="sidebar">
    <div class="side-brand">綦桐AI网关<div class="side-brand-sub">AI API Gateway Console</div></div>
    <nav>
      <div class="nav-group">概览</div>
      <a data-page="dashboard" class="active"><svg viewBox="0 0 24 24"><rect x="3" y="3" width="7" height="9" rx="1"/><rect x="14" y="3" width="7" height="5" rx="1"/><rect x="14" y="12" width="7" height="9" rx="1"/><rect x="3" y="16" width="7" height="5" rx="1"/></svg><b>首页</b></a>
      <a data-page="usage"><svg viewBox="0 0 24 24"><line x1="18" y1="20" x2="18" y2="10"/><line x1="12" y1="20" x2="12" y2="4"/><line x1="6" y1="20" x2="6" y2="14"/></svg><b>用量统计</b></a>
      <div class="nav-group">模型接入</div>
      <a data-page="providers"><svg viewBox="0 0 24 24"><rect x="2" y="3" width="20" height="7" rx="2"/><rect x="2" y="14" width="20" height="7" rx="2"/><line x1="6" y1="6.5" x2="6.01" y2="6.5"/><line x1="6" y1="17.5" x2="6.01" y2="17.5"/></svg><b>服务商</b></a>
      <a data-page="models"><svg viewBox="0 0 24 24"><path d="M21 16V8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16z"/><polyline points="3.27 6.96 12 12.01 20.73 6.96"/><line x1="12" y1="22.08" x2="12" y2="12"/></svg><b>模型</b></a>
      <a data-page="speedtest"><svg viewBox="0 0 24 24"><polygon points="13 2 3 14 12 14 11 22 21 10 12 10 13 2"/></svg><b>测速排行</b></a>
      <a data-page="rules"><svg viewBox="0 0 24 24"><polyline points="16 3 21 3 21 8"/><line x1="4" y1="20" x2="21" y2="3"/><polyline points="21 16 21 21 16 21"/><line x1="15" y1="15" x2="21" y2="21"/><line x1="4" y1="4" x2="9" y2="9"/></svg><b>路由规则</b></a>
      <a data-page="keys"><svg viewBox="0 0 24 24"><rect x="3" y="11" width="18" height="11" rx="2"/><path d="M7 11V7a5 5 0 0 1 10 0v4"/></svg><b>API密钥</b></a>
      <div class="nav-group">机器人</div>
      <a data-page="qqbot" class="admin-only"><svg viewBox="0 0 24 24"><path d="M21 11.5a8.38 8.38 0 0 1-.9 3.8 8.5 8.5 0 0 1-7.6 4.7 8.38 8.38 0 0 1-3.8-.9L3 21l1.9-5.7a8.38 8.38 0 0 1-.9-3.8 8.5 8.5 0 0 1 4.7-7.6 8.38 8.38 0 0 1 3.8-.9h.5a8.48 8.48 0 0 1 8 8v.5z"/></svg><b>QQ机器人</b></a>
      <a data-page="weixin" class="admin-only"><svg viewBox="0 0 24 24"><path d="M21 11.5a8.38 8.38 0 0 1-.9 3.8 8.5 8.5 0 0 1-7.6 4.7 8.38 8.38 0 0 1-3.8-.9L3 21l1.9-5.7a8.38 8.38 0 0 1-.9-3.8 8.5 8.5 0 0 1 4.7-7.6 8.38 8.38 0 0 1 3.8-.9h.5a8.48 8.48 0 0 1 8 8v.5z"/><path d="M9 6.5a3 3 0 0 1 6 0"/></svg><b>微信机器人</b></a>
      <a data-page="yuanbao" class="admin-only"><svg viewBox="0 0 24 24"><path d="M12 2a10 10 0 1 0 10 10A10 10 0 0 0 12 2zm0 18a8 8 0 1 1 8-8 8 8 0 0 1-8 8z"/><circle cx="12" cy="12" r="4"/></svg><b>元宝Bot</b></a>
      <div class="nav-group">智能体</div>
      <a data-page="chat"><svg viewBox="0 0 24 24"><path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"/></svg><b>内置聊天</b></a>
      <a data-page="skills"><svg viewBox="0 0 24 24"><path d="M14.7 6.3a1 1 0 0 0 0 1.4l1.6 1.6a1 1 0 0 0 1.4 0l3.77-3.77a6 6 0 0 1-7.94 7.94l-6.91 6.91a2.12 2.12 0 0 1-3-3l6.91-6.91a6 6 0 0 1 7.94-7.94l-3.76 3.76z"/></svg><b>技能</b></a>
      <a data-page="workflows"><svg viewBox="0 0 24 24"><rect x="3" y="3" width="7" height="7" rx="1"/><rect x="14" y="3" width="7" height="7" rx="1"/><rect x="3" y="14" width="7" height="7" rx="1"/><path d="M14 14h7v7h-7z"/></svg><b>工作流</b></a>
      <a data-page="mcp" class="admin-only"><svg viewBox="0 0 24 24"><path d="M21 16V8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16z"/><circle cx="12" cy="10" r="3"/></svg><b>MCP</b></a>
      <a data-page="memory"><svg viewBox="0 0 24 24"><path d="M18 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2h6"/><polyline points="15 3 21 9 15 9"/><line x1="10" y1="14" x2="21" y2="3"/><line x1="18" y1="13" x2="18" y2="19"/></svg><b>记忆</b></a>
      <a data-page="terminal" class="admin-only"><svg viewBox="0 0 24 24"><polyline points="4 17 10 11 4 5"/><line x1="12" y1="19" x2="20" y2="19"/></svg><b>终端</b></a>
      <a data-page="tickets"><svg viewBox="0 0 24 24"><path d="M20.59 13.41l-7.17 7.17a2 2 0 0 1-2.83 0L2 12V2h10l8.59 8.59a2 2 0 0 1 0 2.83z"/><line x1="7" y1="7" x2="7.01" y2="7"/></svg><b>工单中心</b></a>
      <div class="nav-group">系统</div>
      <a data-page="profile"><svg viewBox="0 0 24 24"><path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2"/><circle cx="12" cy="7" r="4"/></svg><b>个人中心</b></a>
      <a data-page="logs"><svg viewBox="0 0 24 24"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/><line x1="16" y1="13" x2="8" y2="13"/><line x1="16" y1="17" x2="8" y2="17"/></svg><b>日志中心</b></a>
      <a data-page="settings" class="admin-only"><svg viewBox="0 0 24 24"><line x1="4" y1="21" x2="4" y2="14"/><line x1="4" y1="10" x2="4" y2="3"/><line x1="12" y1="21" x2="12" y2="12"/><line x1="12" y1="8" x2="12" y2="3"/><line x1="20" y1="21" x2="20" y2="16"/><line x1="20" y1="12" x2="20" y2="3"/><line x1="1" y1="14" x2="7" y2="14"/><line x1="9" y1="8" x2="15" y2="8"/><line x1="17" y1="16" x2="23" y2="16"/></svg><b>网关设置</b></a>
      <a data-page="announcements" class="admin-only"><svg viewBox="0 0 24 24"><path d="M18 8A6 6 0 0 0 6 8c0 7-3 9-3 9h18s-3-2-3-9"/><path d="M13.73 21a2 2 0 0 1-3.46 0"/></svg><b>公告管理</b></a>
      <a data-page="users" class="admin-only agent-only"><svg viewBox="0 0 24 24"><path d="M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2"/><circle cx="9" cy="7" r="4"/><path d="M23 21v-2a4 4 0 0 0-3-3.87"/><path d="M16 3.13a4 4 0 0 1 0 7.75"/></svg><b>用户管理</b></a>
      <a data-page="about"><svg viewBox="0 0 24 24"><circle cx="12" cy="12" r="10"/><line x1="12" y1="16" x2="12" y2="12"/><line x1="12" y1="8" x2="12.01" y2="8"/></svg><b>关于我们</b></a>
    </nav>
  </aside>
  <!-- 内容区 -->
  <main class="content" id="content">
    <div id="view-dashboard" class="view active"></div>
    <div id="view-providers" class="view"></div>
    <div id="view-models" class="view"></div>
    <div id="view-speedtest" class="view"></div>
    <div id="view-chat" class="view"></div>
    <div id="view-keys" class="view"></div>
    <div id="view-qqbot" class="view"></div>
    <div id="view-weixin" class="view"></div>
    <div id="view-yuanbao" class="view"></div>
    <div id="view-rules" class="view"></div>
    <div id="view-usage" class="view"></div>
    <div id="view-tickets" class="view"></div>
    <div id="view-terminal" class="view"></div>
    <div id="view-skills" class="view"></div>
    <div id="view-workflows" class="view"></div>
    <div id="view-mcp" class="view"></div>
    <div id="view-memory" class="view"></div>
    <div id="view-profile" class="view"></div>
    <div id="view-settings" class="view"></div>
    <div id="view-logs" class="view"></div>
    <div id="view-announcements" class="view"></div>
    <div id="view-users" class="view"></div>
    <div id="view-about" class="view"></div>
  </main>
  <!-- 底部导航（移动端）· 精简为4个核心 -->
  <nav class="bottom-nav" id="bottomNav">
    <a data-page="dashboard" class="active"><svg viewBox="0 0 24 24"><rect x="3" y="3" width="7" height="9" rx="1"/><rect x="14" y="3" width="7" height="5" rx="1"/><rect x="14" y="12" width="7" height="9" rx="1"/><rect x="3" y="16" width="7" height="5" rx="1"/></svg><b>首页</b></a>
    <a data-page="providers"><svg viewBox="0 0 24 24"><rect x="2" y="3" width="20" height="7" rx="2"/><rect x="2" y="14" width="20" height="7" rx="2"/></svg><b>服务商</b></a>
    <a data-page="models"><svg viewBox="0 0 24 24"><path d="M21 16V8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16z"/></svg><b>模型</b></a>
    <a data-page="chat"><svg viewBox="0 0 24 24"><path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"/></svg><b>内置聊天</b></a>
  </nav>
</div>
<!-- 命令面板 (Ctrl+K) -->
<div class="cmdk" id="cmdk" onclick="if(event.target===this)closePalette()">
  <div class="cmdk-box">
    <input id="cmdkInput" placeholder="输入页面名称或命令，回车执行第一项…" oninput="renderCmdk(this.value)" onkeydown="if(event.key==='Enter'){runFirstCmdk()}">
    <div class="cmdk-list" id="cmdkList"></div>
    <div class="cmdk-foot">Ctrl+K 打开 / Esc 关闭 / 回车执行第一项</div>
  </div>
</div>
<!-- 弹窗 -->
<div class="modal" id="modal">
  <div class="modal-box">
    <div class="modal-head"><h3 id="modalTitle"></h3><button class="modal-close" onclick="closeModal()">×</button></div>
    <div id="modalBody"></div>
  </div>
</div>
<div id="toast" class="toast"></div>
<script>
${adminJs()}
</script>
</body>
</html>"""

    /** 管理后台样式 — 控制台风格：中性灰阶 + 单强调色，明暗双主题 */
    fun adminCss(): String = """
*{margin:0;padding:0;box-sizing:border-box}
:root{
  --bg:#0B0D12;--surface:#12151C;--surface2:#1A1E28;--border:#232833;--inset:#0E1117;
  --primary:#3E7BFA;--primary2:#3E7BFA;--cyan:#3E7BFA;
  --green:#3FB27F;--red:#E5636A;--amber:#D99A3D;
  --text:#D6DAE1;--muted:#8791A0;
  --shadow:0 1px 3px rgba(0,0,0,.3);
  --surface-rgb:18,21,28;--primary-rgb:62,123,250;
}
[data-theme="light"]{
  --bg:#F4F5F7;--surface:#FFFFFF;--surface2:#EDF0F3;--border:#E1E5EA;--inset:#F4F5F7;
  --primary:#2F6BDF;--cyan:#2F6BDF;
  --green:#1F9D66;--red:#D24A52;--amber:#B97F2A;
  --text:#1F2430;--muted:#5F6B7C;
  --shadow:0 1px 2px rgba(15,23,42,.06);
  --surface-rgb:255,255,255;--primary-rgb:47,107,223;
}
html,body{height:100%}
body{font-family:-apple-system,BlinkMacSystemFont,"Segoe UI","PingFang SC","Hiragino Sans GB","Microsoft YaHei",sans-serif;background:var(--bg);color:var(--text);overflow-x:hidden;-webkit-font-smoothing:antialiased}
#app{min-height:100vh;display:flex;flex-direction:column}
/* ===== 顶栏 ===== */
.topbar{position:fixed;top:0;left:0;right:0;height:52px;background:var(--surface);border-bottom:1px solid var(--border);display:flex;align-items:center;padding:0 14px;z-index:1000;max-width:100vw;overflow:hidden}
.burger{background:none;border:0;color:var(--muted);cursor:pointer;padding:6px 8px;margin-right:4px}
.burger svg{width:20px;height:20px;stroke:currentColor;fill:none;stroke-width:2;stroke-linecap:round}
.topbar-title{font-size:14px;font-weight:600;color:var(--text)}
.topbar-title .ver{font-size:11px;color:var(--muted);margin-left:8px;font-weight:400}
.topbar-right{margin-left:auto;display:flex;align-items:center;gap:10px}
.tb-btn{height:30px;min-width:30px;padding:0 8px;background:var(--surface2);border:1px solid var(--border);border-radius:6px;color:var(--muted);cursor:pointer;font-size:12px;display:inline-flex;align-items:center;justify-content:center;transition:.15s}
.tb-btn:hover{color:var(--text);border-color:var(--muted)}
.tb-btn svg{width:15px;height:15px;stroke:currentColor;fill:none;stroke-width:2;stroke-linecap:round;stroke-linejoin:round}
.dot{width:8px;height:8px;border-radius:50%;background:var(--green);display:inline-block}
.dot.off{background:var(--red)}
.user-name{font-size:13px;color:var(--muted)}
.logout-btn{padding:5px 12px;border:1px solid var(--border);background:transparent;color:var(--muted);border-radius:6px;cursor:pointer;font-size:12px;transition:.15s}
.logout-btn:hover{border-color:var(--red);color:var(--red)}
.global-search{width:180px;padding:6px 12px;background:var(--inset);border:1px solid var(--border);border-radius:6px;color:var(--text);font-size:12px;outline:none;transition:.15s}
.global-search:focus{border-color:var(--primary)}
/* ===== 遮罩 ===== */
.mask{display:none;position:fixed;inset:0;background:rgba(0,0,0,.5);z-index:950}
.mask.show{display:block}
/* ===== 侧边栏（默认收起，三道杠展开悬浮最前） ===== */
.sidebar{position:fixed;top:52px;left:0;bottom:0;width:212px;background:var(--surface);border-right:1px solid var(--border);overflow-y:auto;z-index:951;transform:translateX(-100%);transition:transform .24s ease;box-shadow:6px 0 28px rgba(0,0,0,.22);display:flex;flex-direction:column;-webkit-overflow-scrolling:touch}
.sidebar nav{flex:1;overflow-y:auto;padding-bottom:24px;-webkit-overflow-scrolling:touch}
.sidebar.open{transform:translateX(0)}
.side-brand{padding:16px 18px 14px;font-size:14px;font-weight:700;color:var(--text);border-bottom:1px solid var(--border)}
.side-brand-sub{font-size:10px;color:var(--muted);font-weight:400;margin-top:2px;letter-spacing:.3px}
.nav-group{padding:14px 18px 5px;font-size:10px;color:var(--muted);text-transform:uppercase;letter-spacing:.1em}
.sidebar nav a{display:flex;align-items:center;gap:10px;padding:8px 18px;color:var(--muted);text-decoration:none;font-size:13px;cursor:pointer;transition:.12s;border-left:2px solid transparent}
.sidebar nav a svg{width:15px;height:15px;stroke:currentColor;fill:none;stroke-width:1.8;stroke-linecap:round;stroke-linejoin:round;flex-shrink:0}
.sidebar nav a:hover{background:var(--surface2);color:var(--text)}
.sidebar nav a.active{background:var(--surface2);color:var(--primary);border-left-color:var(--primary);font-weight:600}
/* ===== 内容区（全宽，侧边栏悬浮） ===== */
.content{margin-left:0;padding:68px 24px 24px;flex:1;min-height:100vh;max-width:100vw;transition:padding .22s ease}
.view{display:none}
.view.active{display:block}
/* ★ v1.120b DeepSeek式聊天：顶部留出 topbar(52px) 不被遮挡，聊天占满下方可视区 */
.content:has(#chatLayout){padding:52px 0 0!important}
#view-chat{height:100%}
#view-chat .chat-layout{height:calc(100dvh - 52px);min-height:0;border-radius:0}
[data-nav="side"] #view-chat .chat-layout{height:calc(100dvh - 52px)}
/* ===== 卡片 ===== */
.card{background:var(--surface);border:1px solid var(--border);border-radius:8px;padding:16px;margin-bottom:14px;max-width:100%;box-sizing:border-box;overflow:hidden}
.card h3{font-size:13px;margin-bottom:12px;color:var(--text);font-weight:600}
.grid{display:grid;gap:12px}
.grid-3{grid-template-columns:repeat(3,1fr)}
.grid-4{grid-template-columns:repeat(4,1fr)}
.grid-2{grid-template-columns:repeat(2,1fr)}
.stat{background:var(--surface);border:1px solid var(--border);border-radius:8px;padding:14px 16px}
.stat .num{font-size:22px;font-weight:700;color:var(--text);font-variant-numeric:tabular-nums;letter-spacing:-.02em}
.stat .lbl{font-size:12px;color:var(--muted);margin-top:3px}
.view.active{animation:fadeIn .18s ease}
@keyframes fadeIn{from{opacity:0}to{opacity:1}}
/* ===== 表格 ===== */
.table-wrap{overflow-x:auto;-webkit-overflow-scrolling:touch;border-radius:6px;max-width:100%;width:100%}
table{width:100%;border-collapse:collapse;font-size:13px}
th,td{padding:8px 12px;text-align:left;border-bottom:1px solid var(--border);white-space:nowrap}
th{color:var(--muted);font-weight:500;font-size:11px;text-transform:uppercase;letter-spacing:.05em;user-select:none}
tbody tr:last-child td{border-bottom:0}
tbody tr:hover td{background:var(--surface2)}
th .sort-ind{color:var(--primary);font-size:10px;margin-left:3px}
.tbl-tools{margin:8px 0;display:flex;gap:8px;align-items:center}
.tbl-filter{max-width:240px!important;padding:6px 10px!important;font-size:12px!important}
/* ===== 徽章 ===== */
.badge{padding:2px 8px;border-radius:4px;font-size:11px;font-weight:500;display:inline-block;background:var(--surface2);color:var(--muted)}
.badge.green{background:rgba(63,178,127,.14);color:var(--green)}
.badge.red{background:rgba(229,99,106,.14);color:var(--red)}
.badge.blue{background:rgba(var(--primary-rgb),.14);color:var(--cyan)}
.badge.purple{background:rgba(139,109,235,.14);color:#9B8BF0}
.badge.gray{background:var(--surface2);color:var(--muted)}
.badge.cyan{background:rgba(var(--primary-rgb),.14);color:var(--cyan)}
.badge.amber{background:rgba(217,154,61,.14);color:var(--amber)}
.pool-dot{width:10px;height:10px;border-radius:50%;background:var(--surface2);border:1.5px solid var(--border);display:inline-block;cursor:pointer;transition:.15s;vertical-align:middle}
.pool-dot.on{background:var(--green);border-color:var(--green)}
.pool-dot.pooled{background:var(--amber);border-color:var(--amber)}
.btn-ghost.danger{color:var(--red)!important;border-color:rgba(229,99,106,.4)!important}
.btn-ghost.danger:hover{background:rgba(229,99,106,.1)!important}
/* ===== 按钮 ===== */
.btn{padding:7px 16px;border:0;border-radius:6px;font-size:13px;cursor:pointer;transition:.15s;background:var(--primary);color:#fff;font-weight:500;display:inline-block}
.btn:hover{filter:brightness(1.12)}
.btn-danger{background:var(--red)}
.btn-sm{padding:4px 10px;font-size:12px;border-radius:5px}
.btn-ghost{padding:5px 11px;border:1px solid var(--border);background:transparent;color:var(--muted);border-radius:6px;cursor:pointer;font-size:12px;transition:.15s}
.btn-ghost:hover{border-color:var(--muted);color:var(--text)}
.action-bar{margin-bottom:14px;display:flex;gap:8px;align-items:center;flex-wrap:wrap}
/* ===== 日志中心 Tab ===== */
.log-tab{flex:1;background:none;border:0;color:var(--muted);font-size:14px;padding:10px 0;cursor:pointer;border-bottom:2px solid transparent;font-family:inherit}
.log-tab:hover{color:var(--text)}
.log-tab.on{color:var(--primary);border-bottom-color:var(--primary);font-weight:600}
/* ===== QQ机器人 Tab（移动端可横滑不挤压） ===== */
.qq-tabs{display:flex;gap:6px;border-bottom:1px solid var(--border);margin-bottom:14px;overflow-x:auto;-webkit-overflow-scrolling:touch;flex-wrap:nowrap;scrollbar-width:none;max-width:100%}
.qq-tabs::-webkit-scrollbar{display:none}
.qq-tabs .log-tab{flex:0 0 auto;white-space:nowrap;padding:10px 14px;font-size:13px;min-width:max-content}
/* ===== 表单 ===== */
.form-row{margin-bottom:12px}
.form-row label{display:block;font-size:12px;color:var(--muted);margin-bottom:5px}
.input{width:100%;padding:8px 11px;background:var(--inset);border:1px solid var(--border);border-radius:6px;color:var(--text);font-size:13px;outline:none;transition:border-color .15s;font-family:inherit}
.input:focus{border-color:var(--primary)}
select.input{appearance:none;background-image:url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='10' height='6'%3E%3Cpath d='M0 0l5 6 5-6z' fill='%238791A0'/%3E%3C/svg%3E");background-repeat:no-repeat;background-position:right 12px center}
input[type=range]{accent-color:var(--primary)}
/* ===== 弹窗 ===== */
.modal{position:fixed;inset:0;background:rgba(0,0,0,.55);display:none;align-items:center;justify-content:center;z-index:1200;padding:4vh 2vw}
.modal.show{display:flex}
.modal-box{width:480px;max-width:96vw;background:var(--surface);border:1px solid var(--border);border-radius:10px;padding:20px;max-height:88vh;overflow-y:auto;overscroll-behavior:contain;box-shadow:var(--shadow)}
.modal-head{display:flex;justify-content:space-between;align-items:center;margin-bottom:14px;flex-shrink:0}
.modal-head h3{font-size:14px;color:var(--text)}
.modal-close{background:none;border:0;color:var(--muted);font-size:20px;cursor:pointer}
#modalBody{max-height:calc(88vh - 90px);overflow-y:auto;overscroll-behavior:contain}
@media (max-width:640px){
 .modal{align-items:flex-end;padding:0}
 .modal-box{width:100vw;max-width:100vw;max-height:92vh;border-radius:14px 14px 0 0;padding:18px}
 #modalBody{max-height:calc(92vh - 80px)}
}
/* ===== 聊天（★v1.120 DeepSeek式全屏贴合：无卡片留白，左会话抽屉+右消息全宽，输入固定底部） ===== */
.chat-layout{position:relative;display:flex;gap:0;width:100%;height:100%;min-height:420px;background:var(--surface);overflow:hidden}
.chat-side{position:absolute;top:0;left:0;bottom:0;width:280px;max-width:84vw;z-index:30;background:var(--surface);border-right:1px solid var(--border);display:flex;flex-direction:column;overflow:hidden;transform:translateX(-100%);transition:transform .25s cubic-bezier(.4,0,.2,1);box-shadow:0 0 0 rgba(0,0,0,0)}
.chat-side.open{transform:translateX(0);box-shadow:10px 0 32px rgba(0,0,0,.22)}
.chat-overlay{position:absolute;inset:0;background:rgba(0,0,0,.32);z-index:25;opacity:0;pointer-events:none;transition:opacity .25s}
.chat-overlay.show{opacity:1;pointer-events:auto}
.chat-side-head{display:flex;justify-content:space-between;align-items:center;padding:16px 16px;border-bottom:1px solid var(--border);font-size:15px;font-weight:600;background:var(--surface)}
.chat-side-head .btn-ghost{border-radius:20px;padding:4px 12px}
.chat-side-list{flex:1;overflow-y:auto;padding:10px}
.chat-conv-item{position:relative;display:flex;align-items:center;gap:12px;padding:11px 12px;border-radius:12px;cursor:pointer;margin-bottom:3px;transition:.15s}
.chat-conv-item:hover{background:var(--surface2)}
.chat-conv-item.active{background:rgba(var(--primary-rgb),.12)}
.chat-conv-ava{width:38px;height:38px;border-radius:50%;background:rgba(var(--primary-rgb),.15);color:var(--primary);display:flex;align-items:center;justify-content:center;font-size:15px;font-weight:700;flex-shrink:0}
.chat-conv-body{flex:1;min-width:0}
.chat-conv-title{font-size:13.5px;color:var(--text);white-space:nowrap;overflow:hidden;text-overflow:ellipsis;padding-right:18px}
.chat-conv-time{font-size:11px;color:var(--muted);margin-top:3px}
.chat-conv-ops{display:none;position:absolute;top:6px;right:6px;gap:2px;background:var(--surface);border-radius:8px;padding:2px}
.chat-conv-ops .btn-ghost{padding:1px 5px;font-size:10px}
.chat-main{flex:1;display:flex;flex-direction:column;min-width:0;background:var(--surface)}
.chat-toolbar{display:flex;gap:10px;align-items:center;padding:12px 16px;border-bottom:1px solid var(--border);flex-shrink:0;background:var(--surface)}
.chat-burger{width:38px;height:38px;border-radius:10px;border:1px solid var(--border);background:var(--surface);color:var(--text);display:flex;align-items:center;justify-content:center;cursor:pointer;flex-shrink:0;font-size:18px}
.chat-burger:hover{background:var(--surface2)}
.chat-toolbar .chat-toolbar-title{font-size:14px;font-weight:600;color:var(--text);flex:1;min-width:0;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.chat-box{display:flex;flex-direction:column;flex:1;min-height:0}
.chat-msgs{flex:1;overflow-y:auto;padding:22px 24px;background:var(--inset);min-height:0}
.msg-row{display:flex;margin-bottom:16px;align-items:flex-start}
.msg-row.user{justify-content:flex-end}
.msg-row.user .bubble{background:rgba(var(--primary-rgb),.16);border:1px solid rgba(var(--primary-rgb),.28);border-bottom-right-radius:6px;max-width:72%}
.msg-row.assistant .bubble{background:var(--surface);border:1px solid var(--border);border-bottom-left-radius:6px;max-width:78%}
.msg-row.system .bubble{background:transparent;color:var(--muted);text-align:center;max-width:100%;font-size:12px}
.bubble{padding:10px 14px;border-radius:12px;line-height:1.6;font-size:13.5px;word-break:break-word}
.msg-time{font-size:10px;opacity:.6;margin-top:5px;text-align:right}
.chat-input{display:flex;gap:10px;padding:14px 16px;border-top:1px solid var(--border);flex-shrink:0;background:var(--surface)}
.chat-input .input{flex:1;min-width:0;border-radius:22px;padding:11px 16px;border-color:var(--border)}
.chat-input .input:focus{border-color:var(--primary)}
.chat-input .btn{border-radius:22px;padding:11px 24px}
.chat-att{width:40px;height:40px;border-radius:22px;border:1px solid var(--border);background:var(--surface);color:var(--muted);display:flex;align-items:center;justify-content:center;cursor:pointer;flex-shrink:0;font-size:16px}
.chat-att:hover{background:var(--surface2)}
.chat-reason{margin:0 0 6px;padding:6px 10px;background:rgba(120,120,120,.08);border:1px dashed var(--border);border-radius:10px;font-size:12px;color:var(--muted);cursor:pointer;max-width:72%}
/* ★ v1.121 结构化流水卡片：思考/工具/结果折叠（智能折叠式） */
.structured-group,.structured-think,.structured-tool,.structured-tool-result{margin:2px 0;border-radius:10px;overflow:hidden;max-width:100%}
.structured-group{border:1px solid var(--border);background:var(--surface2);font-size:12.5px}
.sg-row{display:flex;align-items:center;gap:6px;padding:7px 10px;cursor:pointer;user-select:none;transition:.12s}
.sg-row:hover{background:rgba(var(--primary-rgb),.08)}
.sg-caret{display:inline-block;transition:transform .18s;color:var(--muted);font-size:10px}
.structured-group.open .sg-caret{transform:rotate(90deg)}
.sg-title{flex:1;font-weight:600;color:var(--text);font-size:12.5px}
.sg-caret2{font-size:11px;color:var(--muted);opacity:.85}
.sg-body{display:none;padding:2px 10px 10px;border-top:1px dashed var(--border);margin-top:0}
.structured-group.open .sg-body{display:block}
.structured-think{border:1px solid rgba(var(--primary-rgb),.28);background:rgba(var(--primary-rgb),.06)}
.structured-think .st-row{display:flex;align-items:center;gap:6px;padding:7px 10px;cursor:pointer;user-select:none}
.structured-think .st-row:hover{background:rgba(var(--primary-rgb),.09)}
.stretched-think, .st-body{display:none;padding:8px 12px;border-top:1px dashed rgba(var(--primary-rgb),.25);max-height:280px;overflow-y:auto;white-space:pre-wrap;line-height:1.6;font-size:12.5px;color:var(--text)}
.structured-think.open .st-body{display:block}
.structured-think.open .sg-caret{transform:rotate(90deg)}
.structured-tool{border:1px solid var(--border);background:var(--surface)}
.st-tool-row{display:flex;align-items:center;gap:6px;padding:6px 10px;cursor:pointer;user-select:none;font-size:12.5px;transition:.12s}
.st-tool-row:hover{background:var(--surface2)}
.tool-ic{font-size:13px}
.tool-name{font-weight:600;color:var(--text);flex:1;min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.st-tool-body{display:none;padding:6px 10px;border-top:1px dashed var(--border)}
.st-tool-body pre{margin:0;background:var(--inset);border-radius:6px;padding:8px;font-size:11.5px;white-space:pre-wrap;word-break:break-all;color:var(--text);max-height:220px;overflow-y:auto}
.structured-tool.open .st-tool-body{display:block}
.structured-tool.open .sg-caret{transform:rotate(90deg)}
.structured-tool-result{border:1px solid var(--border);background:rgba(120,120,120,.05)}
.structured-tool-result .st-body{display:none}
.structured-tool-result.open .st-body{display:block}
.structured-tool-result.open .sg-caret{transform:rotate(90deg)}
.structured-details{max-width:100%;margin:4px 0;border:1px solid var(--border);border-radius:8px;padding:6px 10px;font-size:12.5px;background:var(--surface2)}
.chat-msg-structured .bubble > *{max-width:100%}
.chat-toggle{flex-shrink:0;padding:6px 12px;border-radius:16px;border:1px solid var(--border);background:var(--surface);color:var(--muted);font-size:12px;cursor:pointer;transition:.15s}
.chat-toggle:hover{border-color:var(--primary);color:var(--text)}
.chat-toggle.on{border-color:var(--primary);background:rgba(var(--primary-rgb),.14);color:var(--primary)}
.code-shell{margin:6px 0;border:1px solid var(--border);border-radius:10px;overflow:hidden;background:var(--inset)}
.code-head{display:flex;justify-content:space-between;align-items:center;padding:6px 10px;background:var(--surface2);border-bottom:1px solid var(--border);font-size:11px;color:var(--muted)}
.code-lang{font-family:ui-monospace,monospace;text-transform:lowercase}
.code-ops{display:flex;gap:6px}
.code-btn{padding:2px 8px;border-radius:6px;border:1px solid var(--border);background:var(--surface);color:var(--text);font-size:11px;cursor:pointer}
.code-btn:hover{border-color:var(--primary);color:var(--primary)}
.code-shell pre{margin:0;padding:10px;overflow-x:auto;font-size:12px;line-height:1.5;font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace;color:#e2e8f0}
.code-shell code{font-family:inherit;background:none;color:inherit}
.chat-reason .cr-body{display:none;white-space:pre-wrap;line-height:1.6;margin-top:4px}
.chat-reason.open .cr-body{display:block}
.bubble pre{background:#1e1e2e;color:#e6e6e6;border-radius:8px;padding:10px 12px;margin:6px 0;overflow-x:auto;font-size:12px;font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace;white-space:pre}
.bubble code{font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace}
/* ★ v1.122 增强 Markdown 渲染样式：标题/表格/列表/引用/任务/斜体 */
.md-h{margin:8px 0 4px;line-height:1.4}
h1.md-h{font-size:17px}h2.md-h{font-size:16px}h3.md-h{font-size:15px}h4.md-h,h5.md-h,h6.md-h{font-size:14px}
.md-table{margin:6px 0;overflow-x:auto;border-radius:8px;border:1px solid var(--border)}
.md-table table{border-collapse:collapse;width:100%;font-size:12.5px;min-width:280px}
.md-table th{background:var(--surface2);font-weight:600;text-align:left}
.md-table th,.md-table td{padding:6px 10px;border-bottom:1px solid var(--border)}
.md-table tr:last-child td{border-bottom:none}
.md-quote{margin:6px 0;padding:6px 12px;border-left:3px solid var(--primary);background:rgba(var(--primary-rgb),.06);border-radius:0 8px 8px 0;color:var(--muted);font-size:13px}
.md-ul,.md-ol{margin:4px 0;padding-left:22px}
.md-ul li,.md-ol li{margin:2px 0;line-height:1.6}
.md-task{display:flex;align-items:flex-start;gap:8px;margin:3px 0;font-size:13px}
.md-task input{width:15px;height:15px;margin-top:3px;accent-color:var(--primary)}
.bubble .ic{background:rgba(120,120,120,.14);border-radius:4px;padding:1px 5px;font-size:12px;font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace}
.bubble img{max-width:200px;border-radius:8px;margin:4px 0;display:block}
.bubble .att-chip{display:inline-flex;align-items:center;gap:4px;background:var(--surface2);border:1px solid var(--border);border-radius:8px;padding:3px 8px;font-size:12px;margin:2px 0}
.chat-attach-preview{display:flex;gap:6px;flex-wrap:wrap;margin-bottom:6px}
/* ===== 地址行 / 代码 ===== */
.addr-line{background:var(--inset);border:1px solid var(--border);border-radius:6px;padding:8px 12px;font-size:12px;font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace;color:var(--text);cursor:pointer;display:flex;justify-content:space-between;align-items:center;gap:8px;transition:.15s}
.addr-line:hover{border-color:var(--primary)}
.copy-tag{font-size:11px;color:var(--muted);font-family:inherit;flex-shrink:0}
.code-block{background:var(--inset);border:1px solid var(--border);border-radius:6px;padding:12px;font-size:12px;line-height:1.7;font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace;color:var(--text);white-space:pre-wrap;word-break:break-all;overflow-x:auto}
.snip-row{display:flex;align-items:center;gap:8px;margin-bottom:8px}
.snip-row .snip-name{font-size:12px;color:var(--muted);width:110px;flex-shrink:0}
.snip-row .addr-line{flex:1}
/* ===== 头像 ===== */
.avatar{width:56px;height:56px;border-radius:50%;background:var(--primary);color:#fff;font-size:24px;font-weight:600;display:flex;align-items:center;justify-content:center;margin:0 auto 10px}
/* ===== 命令面板 ===== */
.cmdk{position:fixed;inset:0;background:rgba(0,0,0,.55);display:none;align-items:flex-start;justify-content:center;z-index:1400;padding-top:12vh}
.cmdk.show{display:flex}
.cmdk-box{width:520px;max-width:92vw;background:var(--surface);border:1px solid var(--border);border-radius:10px;overflow:hidden;box-shadow:var(--shadow)}
.cmdk-box input{width:100%;padding:13px 16px;background:transparent;border:0;border-bottom:1px solid var(--border);color:var(--text);font-size:14px;outline:none}
.cmdk-list{max-height:320px;overflow-y:auto}
.cmdk-item{padding:9px 16px;font-size:13px;color:var(--text);cursor:pointer;display:flex;justify-content:space-between;align-items:center}
.cmdk-item:hover,.cmdk-item.sel{background:var(--surface2)}
.cmdk-item .cmdk-hint{font-size:11px;color:var(--muted)}
.cmdk-foot{padding:8px 16px;font-size:11px;color:var(--muted);border-top:1px solid var(--border)}
/* ===== Toast ===== */
.toast{position:fixed;top:62px;right:14px;padding:10px 16px;border-radius:7px;background:var(--surface);border:1px solid var(--border);color:var(--text);font-size:13px;z-index:1500;opacity:0;transition:.25s;pointer-events:none;max-width:80vw;box-shadow:var(--shadow)}
.toast.show{opacity:1}
.toast.ok{border-left:3px solid var(--green)}
.toast.err{border-left:3px solid var(--red)}
/* ===== 底部导航（移动端） ===== */
.bottom-nav{display:none}
/* ===== 响应式 ===== */
@media(max-width:1200px){.grid-3{grid-template-columns:repeat(2,1fr)}.grid-4{grid-template-columns:repeat(2,1fr)}}
@media(max-width:768px){
  .burger{display:block}
  .sidebar{transform:translateX(-100%)}
  .sidebar.open{transform:translateX(0)}
  /* ★ v1.104 修复：底部导航(约52px)遮挡侧边栏最后一项「关于我们」——底部留出空间 */
  .sidebar{padding-bottom:58px}
  .sidebar nav{padding-bottom:80px}
  .content{margin-left:0;padding:64px 14px 76px;max-width:100%}
  .grid-3,.grid-2,.grid-4{grid-template-columns:1fr}
  .bottom-nav{display:flex;position:fixed;bottom:0;left:0;right:0;background:var(--surface);border-top:1px solid var(--border);z-index:1000}
  .bottom-nav a{flex:1;display:flex;flex-direction:column;align-items:center;padding:7px 2px;color:var(--muted);text-decoration:none;font-size:10px;gap:2px}
  .bottom-nav a svg{width:17px;height:17px;stroke:currentColor;fill:none;stroke-width:1.8;stroke-linecap:round;stroke-linejoin:round}
  .bottom-nav a.active{color:var(--primary)}
  .topbar-title{overflow:hidden;text-overflow:ellipsis;white-space:nowrap;flex:1;min-width:0}
  .topbar-right{flex-shrink:0}
  .topbar-title .ver{display:none}
  .user-name{display:none}
  /* 移动端聊天：顶部留 topbar(52px) + 底部贴 bottom-nav(52px)，抽屉会话列表 */
  .content:has(#chatLayout){padding:52px 0 52px!important}
  #view-chat .chat-layout{height:calc(100dvh - 104px);min-height:0;border-radius:0}
  .chat-layout{height:100%;min-height:0}
  .chat-side{width:84vw;max-width:300px}
  .chat-main .chat-toolbar select{width:130px!important;font-size:11px!important}
  .chat-toolbar-title{font-size:12px!important}
  .chat-msgs{padding:14px 12px}
  .msg-row .bubble{max-width:82%;font-size:13px}
  .chat-input{padding:10px 12px}
  .chat-toolbar{padding:10px 12px}
}
/* ===== 主题外观设置控件 ===== */
.swatch-row{display:flex;gap:8px;flex-wrap:wrap;align-items:center}
.swatch{width:26px;height:26px;border-radius:6px;border:2px solid rgba(255,255,255,.18);cursor:pointer;transition:.15s;padding:0}
.swatch:hover{transform:scale(1.12)}
.swatch.sel{border-color:var(--text);box-shadow:0 0 0 2px var(--surface2)}
input[type=color]{padding:2px;cursor:pointer}
/* ===== 液态玻璃（html[data-glass=on] 生效；背景 ambience 让模糊有内容可糊） ===== */
body::before{content:'';position:fixed;inset:0;z-index:-1;pointer-events:none;
background:radial-gradient(640px 420px at 86% -8%,rgba(var(--primary-rgb),.09),transparent 70%),
radial-gradient(560px 420px at -8% 104%,rgba(var(--primary-rgb),.055),transparent 70%),
radial-gradient(720px 520px at 46% 118%,rgba(var(--surface-rgb),.9),transparent 62%)}
[data-theme="light"] body::before{background:radial-gradient(640px 420px at 86% -8%,rgba(var(--primary-rgb),.07),transparent 70%),
radial-gradient(560px 420px at -8% 104%,rgba(var(--primary-rgb),.045),transparent 70%)}
[data-glass="on"] .topbar,[data-glass="on"] .sidebar,[data-glass="on"] .modal-box,[data-glass="on"] .cmdk-box,[data-glass="on"] .toast,[data-glass="on"] .bottom-nav{
background:rgba(var(--surface-rgb),.62);
-webkit-backdrop-filter:blur(18px) saturate(1.65);
backdrop-filter:blur(18px) saturate(1.65);
box-shadow:inset 0 1px 0 rgba(255,255,255,.07),inset 0 -1px 0 rgba(0,0,0,.10),0 8px 28px rgba(0,0,0,.16);
border-color:rgba(255,255,255,.09)}
[data-glass="on"] .modal-box,[data-glass="on"] .cmdk-box{
background:rgba(var(--surface-rgb),.72);
-webkit-backdrop-filter:blur(26px) saturate(1.7);
backdrop-filter:blur(26px) saturate(1.7)}
[data-glass="on"] .toast.ok{border-left-color:var(--green)}
[data-glass="on"] .toast.err{border-left-color:var(--red)}
[data-theme="light"][data-glass="on"] .topbar,[data-theme="light"][data-glass="on"] .sidebar,[data-theme="light"][data-glass="on"] .modal-box,[data-theme="light"][data-glass="on"] .cmdk-box,[data-theme="light"][data-glass="on"] .toast,[data-theme="light"][data-glass="on"] .bottom-nav{
background:rgba(var(--surface-rgb),.58);
box-shadow:inset 0 1px 0 rgba(255,255,255,.9),inset 0 -1px 0 rgba(15,23,42,.04),0 8px 28px rgba(15,23,42,.08);
border-color:rgba(255,255,255,.6)}
[data-theme="light"][data-glass="on"] .modal-box,[data-theme="light"][data-glass="on"] .cmdk-box{
background:rgba(var(--surface-rgb),.68);
-webkit-backdrop-filter:blur(26px) saturate(1.7);
backdrop-filter:blur(26px) saturate(1.7)}
/* 系统级减少透明度偏好：自动退回实心面板 */
@media (prefers-reduced-transparency: reduce){
[data-glass="on"] .topbar,[data-glass="on"] .sidebar,[data-glass="on"] .modal-box,[data-glass="on"] .cmdk-box,[data-glass="on"] .toast,[data-glass="on"] .bottom-nav{
background:var(--surface);-webkit-backdrop-filter:none;backdrop-filter:none;box-shadow:var(--shadow)}
}
""".trimIndent()

    /** 后台 JS：核心 + 模型测速聊天 + 密钥规则用量用户 + UX 增强包 + QQ + 技能工作流MCP + 微信 + 元宝Bot（v1.119） */
    fun adminJs(): String = "var APP_VER = '" + VER + "';\n" + AdminJs1.js() + AdminJs2.js() + AdminJs3.js() + AdminJs4.js() + AdminJs5.js() + AdminJs6.js() + AdminJs7.js() + AdminJs9.js()
}
