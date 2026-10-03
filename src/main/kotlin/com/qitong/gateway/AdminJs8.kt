package com.qitong.gateway

/** 后台 JS 第八部分：智能工具 —— QQ/微信机器人/技能/MCP/工作流/终端 汇总入口（v71） */
object AdminJs8 {
  fun js(): String = """
// ===== 智能工具（v71：QQ/微信/技能/MCP/工作流/终端统一入口） =====
 loaders.aitools = function(){
  var box = $('view-aitools');
  if(!box) return;
  var cards = [
   {page:'qqbot', icon:'💬', name:'QQ 机器人', desc:'QQ 通道：动态概览/插件/群配置/日志/绑定', color:'#4F46E5'},
   {page:'weixin', icon:'💚', name:'微信机器人', desc:'微信 bot 通道：扫码登录/机器人管理/日志', color:'#06B6D4'},
   {page:'skills', icon:'🧩', name:'技能库', desc:'网关技能：查状态/测速/余额/启停模型', color:'#8B5CF6'},
   {page:'mcp', icon:'🔌', name:'MCP 工具', desc:'外部工具接入：天气/搜索/域名/业务接口', color:'#EC4899'},
   {page:'workflows', icon:'⚙️', name:'工作流', desc:'自动化：触发词→多步骤执行', color:'#F59E0B'},
   {page:'terminal', icon:'🖥', name:'终端', desc:'沙盒终端：远程命令/异步长任务', color:'#10B981'},
   {page:'chat', icon:'🤖', name:'内置聊天', desc:'qtai-sj 对话：自由调度全部工具', color:'#3B82F6'},
   {page:'memory', icon:'🧠', name:'记忆', desc:'长期记忆管理：重要信息自动记住', color:'#A855F7'}
  ];
  var html = '<div class="card"><div class="qq-tabs"><span style="font-size:15px;font-weight:700">🧰 智能工具中心</span><span style="font-size:12px;color:var(--muted)">QQ/微信机器人 · 技能 · MCP · 工作流 · 终端 · 记忆</span></div>'+
   '<div style="display:grid;grid-template-columns:repeat(auto-fill,minmax(240px,1fr));gap:14px;padding:16px 0">'+
   cards.map(function(c){
    return '<div style="background:var(--surface2);border:1px solid var(--border);border-radius:12px;padding:18px;cursor:pointer" onclick="goPage(\''+c.page+'\')">'+
     '<div style="font-size:26px;margin-bottom:8px">'+c.icon+'</div>'+
     '<div style="font-weight:700;font-size:15px;color:var(--text)">'+c.name+'</div>'+
     '<div style="font-size:12px;color:var(--muted);margin-top:6px">'+c.desc+'</div>'+
     '<div style="height:3px;background:'+c.color+';border-radius:2px;margin-top:12px;opacity:.6"></div></div>';
   }).join('')+'</div></div>'+
   '<div class="card" style="margin-top:14px"><div style="font-size:14px;font-weight:700;margin-bottom:10px">📊 通道状态一览</div><div id="aiToolsStatus" style="font-size:13px;color:var(--muted)">加载中…</div></div>';
  box.innerHTML = html;
  // 状态一览
  var statusHtml = '';
  api('/api/qq/bots').then(function(r){
   var bots=r.data||[];
   statusHtml += '💬 QQ机器人：<b>'+(bots.filter(function(b){return b.online}).length)+'</b>/'+bots.length+' 在线<br/>';
   api('/api/weixin/bots').then(function(w){
    var wx=w.data||[];
    statusHtml += '💚 微信bot：<b>'+(wx.filter(function(b){return b.online}).length)+'</b>/'+wx.length+' 在线<br/>';
    var el=$('aiToolsStatus'); if(el) el.innerHTML = statusHtml + '<span style="color:var(--muted)">（点击上方卡片进入对应功能）</span>';
   });
  }).catch(function(){ var el=$('aiToolsStatus'); if(el) el.innerHTML='状态加载失败'; });
 };
// 跳页
function goPage(pg){ switchView(pg); }
// ★ v71 关于页渲染（修复被挡/空白）
 loaders.about = function(){
  var box = $('view-about');
  if(!box) return;
  box.innerHTML =
   '<div class="card" style="max-width:640px;margin:0 auto;padding:30px;text-align:center">'+
   '<div style="font-size:42px;margin-bottom:12px">🚀</div>'+
   '<div style="font-size:22px;font-weight:800;color:var(--text)">綦桐AI网关</div>'+
   '<div style="font-size:13px;color:var(--muted);margin:6px 0 18px">Docker Server Edition · AI API Gateway Console</div>'+
   '<div style="font-size:13px;line-height:2;text-align:left;background:var(--surface2);border:1px solid var(--border);border-radius:10px;padding:16px 20px;color:var(--text)">'+
   '<div>📌 版本：<b>'+APP_VER+'</b></div>'+
   '<div>🧠 通道：QQ机器人 · 微信bot（ilink）· 内置聊天</div>'+
   '<div>🛠 能力：qtai-sj 自由调度 · 技能 · MCP · 工作流 · 终端 · 心跳 · 记忆</div>'+
   '<div>🔐 权限：管理员/代理/普通用户 分级 · 账号绑定</div>'+
   '<div>💬 智能工具中心：侧边栏「智能工具」汇总全部功能入口</div>'+
   '</div>'+
   '<div style="font-size:12px;color:var(--muted);margin-top:16px">© 2026 綦桐 · All rights reserved</div></div>';
 };
"""
}