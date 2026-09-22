package com.qitong.gateway

/**
 * 綦桐AI网关 Web UI — 深色科技风，一比一对齐 APP 主题
 * 主色靛蓝 #4F46E5 / 青 #06B6D4 / 深底 #0F172A / 卡片 #1E293B / 绿 #22C55E
 * 桌面端：左侧导航；移动端：顶部汉堡 + 底部导航 + 抽屉
 */
object WebUi {
    fun loginHtml(): String = """<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>綦桐AI网关 · 登录</title>
<style>
*{margin:0;padding:0;box-sizing:border-box}
body{font-family:-apple-system,"PingFang SC","Microsoft YaHei",sans-serif;min-height:100vh;background:linear-gradient(135deg,#0F172A 0%,#1E1B4B 55%,#164E63 100%);display:flex;align-items:center;justify-content:center;color:#E2E8F0;overflow:hidden}
body::before{content:'';position:fixed;inset:0;background:radial-gradient(ellipse at 20% 20%,rgba(79,70,229,.25) 0%,transparent 50%),radial-gradient(ellipse at 80% 80%,rgba(6,182,212,.18) 0%,transparent 50%);pointer-events:none}
.card{position:relative;width:400px;max-width:92vw;background:rgba(30,41,59,.85);border:1px solid rgba(100,116,139,.3);border-radius:20px;padding:40px 34px;backdrop-filter:blur(16px);box-shadow:0 25px 60px rgba(0,0,0,.5)}
.logo{text-align:center;margin-bottom:28px}
.logo .icon{font-size:38px;background:linear-gradient(135deg,#4F46E5,#06B6D4);-webkit-background-clip:text;-webkit-text-fill-color:transparent}
.logo h1{font-size:22px;font-weight:700;margin-top:8px;background:linear-gradient(90deg,#818CF8,#22D3EE);-webkit-background-clip:text;-webkit-text-fill-color:transparent}
.logo p{font-size:12px;color:#64748B;margin-top:6px}
.tabs{display:flex;background:rgba(15,23,42,.6);border-radius:10px;padding:4px;margin-bottom:24px}
.tabs button{flex:1;padding:9px 0;border:0;background:transparent;color:#94A3B8;font-size:14px;cursor:pointer;border-radius:8px;transition:.25s}
.tabs button.active{background:linear-gradient(135deg,#4F46E5,#6366F1);color:#fff;box-shadow:0 4px 14px rgba(79,70,229,.4)}
.form-group{margin-bottom:16px}
.form-group label{display:block;font-size:12px;color:#94A3B8;margin-bottom:6px}
.form-group input{width:100%;padding:12px 14px;background:rgba(15,23,42,.6);border:1px solid rgba(100,116,139,.3);border-radius:10px;color:#E2E8F0;font-size:14px;outline:none;transition:.25s}
.form-group input:focus{border-color:#6366F1;box-shadow:0 0 0 3px rgba(99,102,241,.15)}
.btn{width:100%;padding:13px 0;border:0;border-radius:10px;font-size:15px;font-weight:600;cursor:pointer;background:linear-gradient(90deg,#4F46E5,#06B6D4);color:#fff;letter-spacing:2px;transition:.25s}
.btn:hover{transform:translateY(-1px);box-shadow:0 10px 30px rgba(79,70,229,.35)}
.msg{min-height:20px;margin-top:12px;text-align:center;font-size:13px}
.msg.err{color:#F87171}.msg.ok{color:#34D399}
.tip{margin-top:18px;padding:10px 12px;background:rgba(6,182,212,.08);border-radius:8px;font-size:12px;color:#64748B;text-align:center}
</style>
</head>
<body>
<div class="card">
  <div class="logo">
    <div class="icon">⚡</div>
    <h1>綦桐AI网关</h1>
    <p>Docker Server v3.18.22-3 · 后台管理</p>
  </div>
  <div class="tabs">
    <button id="tabL" class="active" onclick="switchTab('L')">登录</button>
    <button id="tabR" onclick="switchTab('R')">注册</button>
  </div>
  <div id="panelL">
    <div class="form-group"><label>用户名</label><input id="lgUser" placeholder="请输入用户名"></div>
    <div class="form-group"><label>密码</label><input id="lgPass" type="password" placeholder="请输入密码" onkeydown="if(event.key==='Enter')doLogin()"></div>
    <button class="btn" onclick="doLogin()">登 录</button>
  </div>
  <div id="panelR" style="display:none">
    <div class="form-group"><label>用户名</label><input id="rgUser" placeholder="至少3个字符"></div>
    <div class="form-group"><label>密码</label><input id="rgPass" type="password" placeholder="至少6个字符"></div>
    <div class="form-group"><label>昵称（可选）</label><input id="rgName" placeholder="显示名称"></div>
    <button class="btn" onclick="doRegister()">注 册</button>
  </div>
  <div id="msg" class="msg"></div>
  <div class="tip">⚡ Kotlin→Docker 转换版 · v3.18.22-3 Server</div>
</div>
<script>
function $(id){return document.getElementById(id)}
function switchTab(t){
  $('tabL').className = t==='L' ? 'active' : '';
  $('tabR').className = t==='R' ? 'active' : '';
  $('panelL').style.display = t==='L' ? 'block' : 'none';
  $('panelR').style.display = t==='R' ? 'block' : 'none';
}
function show(m,ok){var e=$('msg');e.textContent=m;e.className='msg'+(ok?' ok':' err')}
async function postJson(url,data){
  try{
    var res=await fetch(url,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(data)});
    var t=await res.text();try{return JSON.parse(t)}catch(e){return {code:-1,msg:'响应异常'}}
  }catch(e){return {code:-1,msg:'网络错误: '+e.message}}
}
async function doLogin(){
  var u=$('lgUser').value.trim(),p=$('lgPass').value;
  if(!u||!p){show('请输入用户名和密码');return}
  show('登录中...',true);
  var r=await postJson('/api/auth/login',{username:u,password:p});
  if(r.code===0){localStorage.setItem('qt_token',r.data.token);show('✅ 登录成功，跳转中...',true);setTimeout(function(){location.href='/admin'},500)}
  else show(r.msg||'登录失败');
}
async function doRegister(){
  var u=$('rgUser').value.trim(),p=$('rgPass').value,n=$('rgName').value.trim();
  if(!u){show('请输入用户名');return}
  if(!p||p.length<6){show('密码至少6个字符');return}
  show('注册中...',true);
  var r=await postJson('/api/auth/register',{username:u,password:p,displayName:n});
  if(r.code===0){show('✅ 注册成功，请登录',true);$('lgUser').value=u;switchTab('L')}
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
<title>綦桐AI网关 · 管理后台</title>
<style>
${adminCss()}
</style>
</head>
<body>
<div id="app">
  <!-- 顶栏 -->
  <header class="topbar">
    <button class="burger" id="burger" onclick="toggleSidebar()">☰</button>
    <div class="topbar-title">⚡ 綦桐AI网关 <span class="ver">v3.18.22-3</span></div>
    <div class="topbar-right">
      <span id="onlineDot" class="dot"></span>
      <span id="userInfo" class="user-name">未登录</span>
      <button class="logout-btn" onclick="doLogout()">退出</button>
    </div>
  </header>
  <div class="mask" id="mask" onclick="toggleSidebar()"></div>
  <!-- 侧边导航（桌面常驻 / 移动抽屉） -->
  <aside class="sidebar" id="sidebar">
    <div class="side-brand">⚡ 綦桐AI网关</div>
    <nav>
      <a data-page="dashboard" class="active"><span>🏠</span><b>首页</b></a>
      <a data-page="providers"><span>🏪</span><b>服务商</b></a>
      <a data-page="models"><span>🤖</span><b>模型</b></a>
      <a data-page="speedtest"><span>⚡</span><b>测速排行</b></a>
      <a data-page="chat"><span>💬</span><b>内置聊天</b></a>
      <a data-page="keys"><span>🔑</span><b>API密钥</b></a>
      <a data-page="rules"><span>🔀</span><b>路由规则</b></a>
      <a data-page="usage"><span>📊</span><b>用量统计</b></a>
      <a data-page="settings"><span>⚙️</span><b>网关设置</b></a>
      <a data-page="users"><span>👥</span><b>用户管理</b></a>
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
    <div id="view-rules" class="view"></div>
    <div id="view-usage" class="view"></div>
    <div id="view-settings" class="view"></div>
    <div id="view-users" class="view"></div>
  </main>
  <!-- 底部导航（移动端） -->
  <nav class="bottom-nav" id="bottomNav">
    <a data-page="dashboard" class="active"><span>🏠</span><b>首页</b></a>
    <a data-page="providers"><span>🏪</span><b>服务商</b></a>
    <a data-page="models"><span>🤖</span><b>模型</b></a>
    <a data-page="chat"><span>💬</span><b>聊天</b></a>
    <a data-page="settings"><span>⚙️</span><b>设置</b></a>
  </nav>
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

    /** 管理后台样式 — 深色科技风，对齐 APP 主题 */
    fun adminCss(): String = """
*{margin:0;padding:0;box-sizing:border-box}
:root{--bg:#0F172A;--surface:#1E293B;--surface2:#334155;--border:rgba(100,116,139,.25);--primary:#4F46E5;--primary2:#6366F1;--cyan:#06B6D4;--green:#22C55E;--red:#EF4444;--amber:#F59E0B;--text:#E2E8F0;--muted:#94A3B8}
html,body{height:100%}
body{font-family:-apple-system,"PingFang SC","Microsoft YaHei",sans-serif;background:var(--bg);color:var(--text);overflow-x:hidden}
#app{min-height:100vh;display:flex;flex-direction:column}
/* ===== 顶栏 ===== */
.topbar{position:fixed;top:0;left:0;right:0;height:56px;background:rgba(15,23,42,.92);backdrop-filter:blur(12px);border-bottom:1px solid var(--border);display:flex;align-items:center;padding:0 16px;z-index:1000}
.burger{display:none;background:none;border:0;color:var(--text);font-size:22px;cursor:pointer;padding:6px 10px;margin-right:6px}
.topbar-title{font-size:16px;font-weight:700;color:var(--text)}
.topbar-title .ver{font-size:11px;color:var(--cyan);margin-left:6px;font-weight:400}
.topbar-right{margin-left:auto;display:flex;align-items:center;gap:12px}
.dot{width:8px;height:8px;border-radius:50%;background:var(--green);box-shadow:0 0 8px var(--green);display:inline-block}
.dot.off{background:var(--red);box-shadow:0 0 8px var(--red)}
.user-name{font-size:13px;color:var(--muted)}
.logout-btn{padding:5px 14px;border:1px solid var(--border);background:transparent;color:var(--muted);border-radius:8px;cursor:pointer;font-size:12px;transition:.2s}
.logout-btn:hover{border-color:var(--red);color:var(--red)}
/* ===== 遮罩 ===== */
.mask{display:none;position:fixed;inset:0;background:rgba(0,0,0,.55);z-index:900}
.mask.show{display:block}
/* ===== 侧边栏 ===== */
.sidebar{position:fixed;top:56px;left:0;bottom:0;width:220px;background:rgba(30,41,59,.9);backdrop-filter:blur(12px);border-right:1px solid var(--border);overflow-y:auto;z-index:950;transition:transform .25s ease}
.side-brand{padding:18px 20px;font-size:14px;font-weight:700;color:var(--text);border-bottom:1px solid var(--border);background:linear-gradient(135deg,rgba(79,70,229,.25),rgba(6,182,212,.15))}
.sidebar nav a{display:flex;align-items:center;gap:12px;padding:12px 20px;color:var(--muted);text-decoration:none;font-size:14px;cursor:pointer;transition:.2s;border-left:3px solid transparent}
.sidebar nav a span{width:22px;text-align:center;font-size:16px}
.sidebar nav a:hover{background:rgba(100,116,139,.1);color:var(--text)}
.sidebar nav a.active{background:linear-gradient(90deg,rgba(79,70,229,.25),rgba(6,182,212,.1));color:#fff;border-left-color:var(--primary2)}
/* ===== 内容区 ===== */
.content{margin-left:220px;padding:72px 20px 20px;flex:1;min-height:100vh}
.view{display:none}
.view.active{display:block}
/* ===== 卡片 ===== */
.card{background:var(--surface);border:1px solid var(--border);border-radius:14px;padding:18px;margin-bottom:14px}
.card h3{font-size:14px;margin-bottom:12px;color:var(--cyan)}
.grid{display:grid;gap:14px}
.grid-3{grid-template-columns:repeat(3,1fr)}
.grid-2{grid-template-columns:repeat(2,1fr)}
.stat{background:var(--surface);border:1px solid var(--border);border-radius:14px;padding:18px}
.stat .num{font-size:26px;font-weight:700;background:linear-gradient(90deg,#818CF8,#22D3EE);-webkit-background-clip:text;-webkit-text-fill-color:transparent}
.stat .lbl{font-size:12px;color:var(--muted);margin-top:4px}
/* ===== 表格 ===== */
.table-wrap{overflow-x:auto;-webkit-overflow-scrolling:touch;border-radius:10px}
table{width:100%;border-collapse:collapse;font-size:13px}
th,td{padding:10px 12px;text-align:left;border-bottom:1px solid var(--border);white-space:nowrap}
th{color:var(--muted);font-weight:500;font-size:12px}
tr:hover td{background:rgba(100,116,139,.06)}
/* ===== 徽章 ===== */
.badge{padding:3px 10px;border-radius:20px;font-size:11px;font-weight:500;display:inline-block}
.badge.green{background:rgba(34,197,94,.15);color:var(--green)}
.badge.red{background:rgba(239,68,68,.15);color:var(--red)}
.badge.blue{background:rgba(6,182,212,.15);color:var(--cyan)}
.badge.purple{background:rgba(99,102,241,.15);color:#A5B4FC}
.badge.gray{background:rgba(148,163,184,.15);color:var(--muted)}
.pool-dot{width:10px;height:10px;border-radius:50%;background:#334155;border:1.5px solid #475569;display:inline-block;cursor:pointer;transition:.2s;vertical-align:middle}
.pool-dot.on{background:var(--green);border-color:var(--green);box-shadow:0 0 6px var(--green);animation:poolPulse 1.5s ease-in-out infinite}
@keyframes poolPulse{0%,100%{opacity:1;box-shadow:0 0 4px var(--green)}50%{opacity:.6;box-shadow:0 0 10px var(--green)}}
.btn-ghost.danger{color:var(--red)!important;border-color:rgba(239,68,68,.4)!important}
.btn-ghost.danger:hover{background:rgba(239,68,68,.15)!important}
/* ===== 按钮 ===== */
.btn{padding:8px 18px;border:0;border-radius:9px;font-size:13px;cursor:pointer;transition:.2s;background:linear-gradient(90deg,var(--primary),var(--cyan));color:#fff;font-weight:500;display:inline-block}
.btn:hover{transform:translateY(-1px);box-shadow:0 6px 18px rgba(79,70,229,.3)}
.btn-danger{background:linear-gradient(90deg,#EF4444,#F97316)}
.btn-sm{padding:5px 12px;font-size:12px;border-radius:7px}
.btn-ghost{padding:6px 12px;border:1px solid var(--border);background:transparent;color:var(--muted);border-radius:8px;cursor:pointer;font-size:12px;transition:.2s}
.btn-ghost:hover{border-color:var(--cyan);color:var(--cyan)}
.action-bar{margin-bottom:14px;display:flex;gap:10px;align-items:center;flex-wrap:wrap}
/* ===== 表单 ===== */
.form-row{margin-bottom:12px}
.form-row label{display:block;font-size:12px;color:var(--muted);margin-bottom:5px}
.input{width:100%;padding:9px 12px;background:rgba(15,23,42,.6);border:1px solid var(--border);border-radius:8px;color:var(--text);font-size:13px;outline:none;transition:.2s}
.input:focus{border-color:var(--primary2);box-shadow:0 0 0 3px rgba(99,102,241,.12)}
select.input{appearance:none;background-image:url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='10' height='6'%3E%3Cpath d='M0 0l5 6 5-6z' fill='%2394A3B8'/%3E%3C/svg%3E");background-repeat:no-repeat;background-position:right 12px center}
/* ===== 弹窗 ===== */
.modal{position:fixed;inset:0;background:rgba(0,0,0,.6);display:none;align-items:center;justify-content:center;z-index:1200;backdrop-filter:blur(4px)}
.modal.show{display:flex}
.modal-box{width:480px;max-width:94vw;background:var(--surface);border:1px solid var(--border);border-radius:16px;padding:22px;max-height:86vh;overflow-y:auto}
.modal-head{display:flex;justify-content:space-between;align-items:center;margin-bottom:16px}
.modal-head h3{font-size:15px;color:var(--text)}
.modal-close{background:none;border:0;color:var(--muted);font-size:20px;cursor:pointer}
/* ===== 聊天 ===== */
.chat-box{display:flex;flex-direction:column;height:calc(100vh - 190px);min-height:360px}
.chat-msgs{flex:1;overflow-y:auto;padding:14px;background:rgba(15,23,42,.4);border-radius:12px;margin-bottom:10px}
.msg-row{display:flex;margin-bottom:10px}
.msg-row.user{justify-content:flex-end}
.msg-row .bubble{max-width:72%;padding:10px 14px;border-radius:12px;font-size:13px;line-height:1.6;word-break:break-word}
.msg-row.user .bubble{background:linear-gradient(135deg,rgba(79,70,229,.9),rgba(6,182,212,.85));border:1px solid rgba(99,102,241,.4)}
.msg-row.assistant .bubble{background:rgba(51,65,85,.8);border:1px solid var(--border)}
.msg-row.system .bubble{background:transparent;color:var(--muted);text-align:center;max-width:100%;font-size:12px}
.chat-input{display:flex;gap:8px}
.chat-input .input{flex:1}
/* ===== 地址行 ===== */
.addr-line{background:rgba(15,23,42,.5);border:1px solid var(--border);border-radius:8px;padding:9px 12px;font-size:13px;font-family:ui-monospace,Menlo,monospace;color:var(--cyan);cursor:pointer;display:flex;justify-content:space-between;align-items:center;transition:.2s}
.addr-line:hover{border-color:var(--primary2);background:rgba(79,70,229,.1)}
.copy-tag{font-size:11px;color:var(--muted);font-family:inherit}
/* ===== Toast ===== */
.toast{position:fixed;top:66px;right:16px;padding:11px 18px;border-radius:10px;background:var(--surface2);border:1px solid var(--border);color:var(--text);font-size:13px;z-index:1500;opacity:0;transition:.3s;pointer-events:none;max-width:80vw}
.toast.show{opacity:1}
.toast.ok{border-color:rgba(34,197,94,.5);color:#86EFAC}
.toast.err{border-color:rgba(239,68,68,.5);color:#FCA5A5}
/* ===== 底部导航（移动端） ===== */
.bottom-nav{display:none}
/* ===== 响应式 ===== */
@media(max-width:1200px){.grid-3{grid-template-columns:repeat(2,1fr)}}
@media(max-width:768px){
  .burger{display:block}
  .sidebar{transform:translateX(-100%)}
  .sidebar.open{transform:translateX(0)}
  .content{margin-left:0;padding:68px 12px 76px}
  .grid-3,.grid-2{grid-template-columns:1fr}
  .bottom-nav{display:flex;position:fixed;bottom:0;left:0;right:0;background:rgba(15,23,42,.96);backdrop-filter:blur(12px);border-top:1px solid var(--border);z-index:1000}
  .bottom-nav a{flex:1;display:flex;flex-direction:column;align-items:center;padding:8px 2px;color:var(--muted);text-decoration:none;font-size:10px;gap:2px}
  .bottom-nav a span{font-size:18px}
  .bottom-nav a.active{color:var(--cyan)}
  .topbar-title .ver{display:none}
  .user-name{display:none}
}
""".trimIndent()

    /** 后台 JS：拼接三个部分 */
    fun adminJs(): String = AdminJs1.js() + AdminJs2.js() + AdminJs3.js()
}