package com.qitong.gateway

/** 后台 JS 第九部分：元宝 Bot —— 腾讯元宝开放平台 WS 通道（v1.119） */
object AdminJs9 {
  fun js(): String = """
 // ===== 元宝 Bot 管理（v1.119 腾讯元宝开放平台 WS 通道） =====
 loaders.yuanbao = function(){
  var box = $('view-yuanbao');
  if(!box) return;
  box.innerHTML = '<div class="card"><div class="qq-tabs">'+
   '<button class="log-tab" onclick="ybTab(0)">📊 动态预览</button>'+
   '<button class="log-tab" onclick="ybTab(1)">🤖 机器人</button>'+
   '<button class="log-tab" onclick="ybTab(2)">📋 运行日志</button></div>'+
   '<div id="ybPane"><div style="color:var(--muted);padding:20px">加载中…</div></div></div>';
  ybTab(0);
 };
var ybTabIdx = 0;
function ybTab(i){ ybTabIdx = i; if(i===0) ybOverview(); else if(i===1) ybLoadBots(); else if(i===2) ybLoadLogs(); }
function ybTime(ts){ if(!ts) return '-'; var d=new Date(ts); return d.toLocaleString('zh-CN',{hour12:false}); }
function ybStatusText(st){
 if(st==='在线') return '<span class="badge green">在线</span>';
 if(st==='连接中') return '<span class="badge amber">连接中</span>';
 return '<span class="badge red">'+esc(st||'离线')+'</span>';
}
// ---- 动态预览 ----
var ybFeedTimer = null;
function ybOverview(){
 var el=$('ybPane'); if(!el) return;
 clearInterval(ybFeedTimer);
 el.innerHTML = '<div id="ybOverview"><div style="color:var(--muted);padding:20px">加载中…</div></div>';
 api('/api/yuanbao/overview').then(function(r){
  var box=$('ybOverview'); if(!box) return;
  var d=r.data||{}; var ov=d.overview||{}; var bots=d.bots||[];
  var stats = '<div class="grid grid-4" style="margin-bottom:14px">'+
   '<div class="stat"><div class="num" style="color:var(--cyan)">'+(ov.onlineBots||0)+'/'+(ov.totalBots||0)+'</div><div class="lbl">在线机器人</div></div>'+
   '<div class="stat"><div class="num">'+(bots.length)+'</div><div class="lbl">已配机器人</div></div></div>';
  var rows=bots.map(function(b){
   return '<tr><td><b>'+esc(b.name||'未命名')+'</b></td>'+
    '<td>'+ybStatusText(b.status)+'</td>'+
    '<td><b style="color:var(--cyan)">'+(b.messagesHandled||0)+'</b> 条</td>'+
    '<td style="font-size:11px;color:var(--red)">'+esc(b.lastError||'')+'</td></tr>';
  }).join('');
  var feed=(d.recent||[]).map(function(l){
   var color = l.type==='error'?'var(--red)':(l.type==='command'||l.type==='status'?'var(--amber)':'var(--green)');
   return '<div style="display:flex;gap:8px;padding:6px 0;border-bottom:1px solid var(--border);font-size:12px">'+
    '<span class="badge" style="color:'+color+';font-size:10px">'+esc(l.type)+'</span>'+
    '<span style="flex:1;color:var(--text)">'+esc(l.content)+'</span>'+
    '<span style="color:var(--muted);font-size:11px">'+ybTime(l.createdAt)+'</span></div>';
  }).join('') || '<div style="color:var(--muted);padding:16px">暂无动态</div>';
  box.innerHTML = stats +
   '<div class="card" style="box-shadow:none;border:1px solid var(--border)"><h3>🤖 机器人状态</h3><div style="overflow-x:auto"><table class="tb"><thead><tr><th>名称</th><th>状态</th><th>消息数</th><th>最近错误</th></tr></thead><tbody>'+(rows||'<tr><td colspan="4" style="color:var(--muted)">暂无机器人，点「🤖 机器人 → + 添加」接入</td></tr>')+'</tbody></table></div></div>'+
   '<div class="card" style="box-shadow:none;border:1px solid var(--border)"><h3>📡 实时动态（每5秒刷新）</h3><div id="ybFeed">'+feed+'</div></div>';
 }).catch(function(){ var e2=$('ybOverview'); if(e2) e2.innerHTML='<div style="color:var(--red);padding:16px">加载失败</div>'; });
 ybFeedTimer = setInterval(function(){
  if(!$('ybOverview')){ clearInterval(ybFeedTimer); return; }
  api('/api/yuanbao/overview').then(function(r){
   var f=$('ybFeed'); if(f){
    var rec=(r.data&&r.data.recent)||[];
    f.innerHTML = rec.map(function(l){
     var color = l.type==='error'?'var(--red)':(l.type==='command'||l.type==='status'?'var(--amber)':'var(--green)');
     return '<div style="display:flex;gap:8px;padding:6px 0;border-bottom:1px solid var(--border);font-size:12px">'+
      '<span class="badge" style="color:'+color+';font-size:10px">'+esc(l.type)+'</span>'+
      '<span style="flex:1;color:var(--text)">'+esc(l.content)+'</span>'+
      '<span style="color:var(--muted);font-size:11px">'+ybTime(l.createdAt)+'</span></div>';
    }).join('');
   }
  });
 },5000);
}
// ---- 机器人列表 ----
function ybLoadBots(){
 var el=$('ybPane'); if(!el) return;
 el.innerHTML = '<div class="action-bar"><button class="btn" onclick="ybForm()">+ 添加元宝Bot</button><button class="btn-ghost" onclick="ybLoadBots()">刷新</button>'+
  '<span style="font-size:12px;color:var(--muted)">元宝Bot（腾讯元宝开放平台）· 填 AppKey/AppSecret 即自动连接</span></div><div style="overflow-x:auto"><div id="ybBotList"></div></div>';
 api('/api/yuanbao/bots').then(function(r){
  var box=$('ybBotList'); if(!box) return;
  var list=r.data||[];
  if(!list.length){ box.innerHTML='<div style="color:var(--muted);padding:18px">暂无元宝Bot，点「+ 添加」接入</div>'; return; }
  var rows=list.map(function(b){
   return '<tr><td><b>'+esc(b.name||'未命名')+'</b></td>'+
    '<td>'+ybStatusText(b.status)+'</td>'+
    '<td><b style="color:var(--cyan)">'+(b.messagesHandled||0)+'</b> 条</td>'+
    '<td style="font-size:11px;color:var(--red)">'+esc(b.lastError||'')+'</td>'+
    '<td style="white-space:nowrap">'+
    '<button class="btn-ghost btn-sm" onclick="ybEdit('+b.id+')">编辑</button> '+
    '<button class="btn-ghost btn-sm" onclick="ybRestart('+b.id+')">重连</button> '+
    '<button class="btn-ghost btn-sm" onclick="ybToggle('+b.id+')">'+(b.enabled?'停用':'启用')+'</button> '+
    '<button class="btn-ghost btn-sm danger" onclick="ybDel('+b.id+')">删</button></td></tr>';
  }).join('');
  box.innerHTML = '<table class="tb"><thead><tr><th>名称</th><th>状态</th><th>消息数</th><th>最近错误</th><th>操作</th></tr></thead><tbody>'+rows+'</tbody></table>';
 }).catch(function(e){ el.innerHTML='<div style="color:var(--red);padding:16px">加载失败：'+esc(e.message||e)+'</div>'; });
}
function ybEdit(id){
 api('/api/yuanbao/bots').then(function(r){
  var list=r.data||[];
  var b=list.filter(function(x){return x.id===id;})[0]||{};
  ybForm(b);
 });
}
function ybForm(b){
 b=b||{};
 var html =
  '<div class="form-row"><label>机器人名称（备注）</label><input class="input" id="ybName" value="'+esc(b.name||'')+'" placeholder="例如：元宝运营号"></div>'+
  '<div class="form-row"><label>AppKey（app_key，元宝开放平台创建机器人获取）</label><input class="input" id="ybAppKey" value="'+esc(b.appKey||'')+'" placeholder="如 edqPvHDk..."></div>'+
  '<div class="form-row"><label>AppSecret（app_secret）</label><input class="input" type="password" id="ybAppSecret" value="'+esc(b.appSecret||'')+'" placeholder="如 OyxA0NdY..."></div>'+
  '<div class="form-row"><label>使用模型（留空用 qtai-sj）</label><input class="input" id="ybModel" value="'+esc(b.aiModel||'')+'" placeholder="qtai-sj"></div>'+
  '<div class="form-row"><label>系统人设 / System Prompt（可选）</label><textarea class="input" id="ybPrompt" rows="3" placeholder="例如：你是綦桐小助理…">'+esc(b.systemPrompt||'')+'</textarea></div>'+
  '<div style="font-size:12px;color:var(--muted);background:var(--inset);padding:10px;border-radius:6px">AppKey 与 AppSecret 以 <b>appKey:appSecret</b> 形式从元宝开放平台「创建机器人」获得。保存后自动连接元宝 WS 通道。</div>';
 openModal(b.id?'编辑元宝Bot':'添加元宝Bot', html, function(){
  var name=$('ybName').value.trim();
  if(!name){ toast('名称必填', false); return; }
  if(!$('ybAppKey').value.trim()){ toast('AppKey 必填', false); return; }
  api('/api/yuanbao/bots',{method:'POST',body:{
   id:b.id||0, name:name,
   appKey:$('ybAppKey').value.trim(),
   appSecret:$('ybAppSecret').value.trim(),
   aiModel:$('ybModel').value.trim()||'qtai-sj',
   systemPrompt:$('ybPrompt').value, enabled:true
  }}).then(function(r){
   if(r.code===0){ toast('保存成功', true); closeModal(); ybLoadBots(); } else toast(r.msg||'保存失败', false);
  });
 });
}
function ybRestart(id){
 api('/api/yuanbao/bots/'+id+'/restart',{method:'POST'}).then(function(r){ toast(r.msg||'已重连', r.code===0); setTimeout(ybLoadBots,800); });
}
function ybToggle(id){
 api('/api/yuanbao/bots/'+id+'/toggle',{method:'POST'}).then(function(r){
  toast(r.msg||'', r.code===0);
  if(r.code===0) setTimeout(ybLoadBots,800);
 });
}
function ybDel(id){
 if(!confirm('确认删除该元宝Bot？')) return;
 api('/api/yuanbao/bots/'+id,{method:'DELETE'}).then(function(){ toast('已删除'); ybLoadBots(); });
}
// ---- 运行日志 ----
function ybLoadLogs(){
 var el=$('ybPane'); if(!el) return;
 el.innerHTML = '<div class="action-bar"><button class="btn-ghost" onclick="ybLoadLogs()">刷新</button>'+
  '<button class="btn-ghost danger" onclick="ybLogsClear()">🗑 清空全部</button>'+
  '<span style="font-size:12px;color:var(--muted)">元宝Bot通道运行日志（最近 200 条）</span></div><div id="ybLogList"></div>';
 api('/api/yuanbao/logs').then(function(r){
  var box=$('ybLogList'); if(!box) return;
  var list=r.data||[];
  if(!list.length){ box.innerHTML='<div style="color:var(--muted);padding:18px">暂无日志</div>'; return; }
  box.innerHTML = list.map(function(l){
   return '<div style="padding:6px 0;border-bottom:1px solid var(--border);font-size:12px">'+
    '<span style="color:var(--muted)">'+ybTime(l.createdAt)+'</span> <code>'+esc(l.type||'')+'</code> '+
    '<span style="word-break:break-all">'+esc(l.content||'')+'</span></div>';
  }).join('');
 });
}
function ybLogsClear(){
 if(!confirm('确认清空全部元宝日志？')) return;
 api('/api/yuanbao/logs/clear',{method:'POST'}).then(function(r){ toast(r.msg||'已清空', r.code===0); ybLoadLogs(); });
}
"""
}