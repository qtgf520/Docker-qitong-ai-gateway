package com.qitong.gateway

/** 后台 JS 第五部分：QQ 机器人 —— 动态概览/插件指令/群配置/独立用户/日志 */
object AdminJs5 {
  fun js(): String = """
// ===== QQ 机器人管理 =====
var qqTab = 'overview';
var qqFeedTimer = null;
function qqTabBtn(t){ qqTab=t; loaders.qqbot(); }
 loaders.qqbot = function(){
 var box = $('view-qqbot');
 var tabs = [
  ['overview','动态概览'],['cmds','插件指令'],['groups','群配置'],
  ['users','独立用户'],['points','积分排行'],['logs','运行日志']
 ].map(function(x){
  return '<button class="log-tab" style="'+(qqTab===x[0]?'color:var(--primary);border-bottom-color:var(--primary);font-weight:600':'')+'" onclick="qqTabBtn(\''+x[0]+'\')">'+x[1]+'</button>';
 }).join('');
 box.innerHTML = '<div class="card"><div class="qq-tabs">'+tabs+'</div>'+
  '<div id="qqPane">'+qqTabHtml()+'</div></div>';
 if(qqTab==='overview') qqLoadOverview();
 if(qqTab==='cmds') qqLoadCmds();
 if(qqTab==='groups') qqLoadGroups();
 if(qqTab==='users') qqLoadUsers();
 if(qqTab==='points') qqLoadPoints();
 if(qqTab==='logs') qqLoadLogs();
 };
function qqTabHtml(){
 if(qqTab==='overview') return '<div id="qqOverview"><div style="color:var(--muted);padding:20px">加载中…</div></div>';
 if(qqTab==='cmds') return '<div class="action-bar"><button class="btn" onclick="qqCmdForm()">+ 新建指令插件</button><button class="btn-ghost" onclick="qqCmdHelp()">插件开发说明</button><span style="font-size:12px;color:var(--muted)">小栗子式：触发词 -> 回复/HTTP/AI，按优先级匹配，命中即停</span></div><div id="qqCmdList"></div>';
 if(qqTab==='groups') return '<div id="qqGroupList"><div style="color:var(--muted);padding:20px">加载中…</div></div>';
 if(qqTab==='users') return '<div id="qqUserList"><div style="color:var(--muted);padding:20px">加载中…</div></div>';
 if(qqTab==='points') return '<div style="font-size:12px;color:var(--muted);margin-bottom:8px">群里发「签到」每日得 5-20 积分，「我的积分」查询。此处可查看排行并手动调整。</div><div id="qqPointsList"></div>';
 return '<div class="action-bar"><button class="btn-ghost" onclick="qqLoadLogs()">刷新</button></div><div id="qqLogList"></div>';
}
function qqTime(ts){ if(!ts) return '-'; var d=new Date(ts); return d.toLocaleString('zh-CN',{hour12:false}); }

// ---- 动态概览（自动刷新） ----
function qqLoadOverview(){
 clearInterval(qqFeedTimer);
 api('/api/qq/overview').then(function(r){
  var el=$('qqOverview'); if(!el) return;
  var d=r.data||{}; var ov=d.overview||{};
  var stats = '<div class="grid grid-4" style="margin-bottom:14px">'+
   '<div class="stat"><div class="num">'+(ov.todayMessages||0)+'</div><div class="lbl">今日消息</div></div>'+
   '<div class="stat"><div class="num" style="color:var(--green)">'+(d.bots||[]).filter(function(b){return b.online}).length+'/'+(d.bots||[]).length+'</div><div class="lbl">在线机器人</div></div>'+
   '<div class="stat"><div class="num">'+(ov.totalGroups||0)+'</div><div class="lbl">已登记群</div></div>'+
   '<div class="stat"><div class="num">'+(ov.totalUsers||0)+'</div><div class="lbl">独立用户</div></div></div>';
  var botRows=(d.bots||[]).map(function(b){
   return '<tr><td><b>'+esc(b.name||'未命名')+'</b></td><td><code style="font-size:11px">'+esc(b.appid)+'</code></td>'+
    '<td>'+(b.online?'<span class="badge green">在线</span>':'<span class="badge gray">离线</span>')+'</td>'+
    '<td>'+(b.messagesHandled||0)+'</td><td style="font-size:11px;color:var(--red)">'+esc(b.lastError||'')+'</td>'+
    '<td style="white-space:nowrap">'+
    '<button class="btn-ghost btn-sm" onclick="qqOpenForm('+JSON.stringify(b).replace(/"/g,'&quot;')+')">编辑</button> '+
    '<button class="btn-ghost btn-sm" onclick="qqToggle('+b.id+')">'+(b.online?'停用':'启用')+'</button> '+
    '<button class="btn-ghost btn-sm" onclick="qqRestart('+b.id+')">重连</button> '+
    '<button class="btn-ghost btn-sm danger" onclick="qqDel('+b.id+')">删</button></td></tr>';
  }).join('') || '<tr><td colspan="6" style="color:var(--muted)">还没有机器人，点上方「添加机器人」接入第一个。</td></tr>';
  var botsTable='<div class="card" style="box-shadow:none;border:1px solid var(--border)"><div class="action-bar"><h3 style="margin:0">机器人状态</h3>'+
   '<button class="btn" onclick="qqOpenForm()">+ 添加机器人</button></div>'+
   '<div class="table-wrap"><table><thead><tr><th>名称</th><th>AppID</th><th>状态</th><th>已处理</th><th>异常</th><th>操作</th></tr></thead><tbody>'+botRows+'</tbody></table></div></div>';
  var feed=(d.recent||[]).map(function(l){
   var color = l.type==='error'?'var(--red)':(l.type==='command'?'var(--amber)':'var(--green)');
   return '<div style="display:flex;gap:10px;padding:7px 0;border-bottom:1px solid var(--border);font-size:12px">'+
    '<span class="badge" style="color:'+color+'">'+esc(l.type)+'</span>'+
    '<span style="flex:1;color:var(--text)">'+esc(l.content)+'</span>'+
    '<span style="color:var(--muted)">'+(l.latencyMs||0)+'ms</span>'+
    '<span style="color:var(--muted)">'+qqTime(l.createdAt)+'</span></div>';
  }).join('') || '<div style="color:var(--muted);padding:16px">暂无动态</div>';
  el.innerHTML = stats + '<div class="grid grid-2">'+botsTable+
   '<div class="card" style="box-shadow:none;border:1px solid var(--border)"><h3>实时动态（每5秒刷新）</h3><div id="qqFeed">'+feed+'</div></div></div>';
 });
 qqFeedTimer = setInterval(function(){
  if(!$('qqOverview')){ clearInterval(qqFeedTimer); return; }
  api('/api/qq/overview').then(function(r){
   var f=$('qqFeed'); if(!f) return;
   var rec=(r.data&&r.data.recent)||[];
   f.innerHTML = rec.map(function(l){
    var color = l.type==='error'?'var(--red)':(l.type==='command'?'var(--amber)':'var(--green)');
    return '<div style="display:flex;gap:10px;padding:7px 0;border-bottom:1px solid var(--border);font-size:12px">'+
     '<span class="badge" style="color:'+color+'">'+esc(l.type)+'</span>'+
     '<span style="flex:1;color:var(--text)">'+esc(l.content)+'</span>'+
     '<span style="color:var(--muted)">'+(l.latencyMs||0)+'ms</span>'+
     '<span style="color:var(--muted)">'+qqTime(l.createdAt)+'</span></div>';
   }).join('');
  });
 },5000);
}

// ---- 机器人增删改启停 ----
function qqOpenForm(b){
 b=b||{};
 var html =
  '<div class="form-row"><label>机器人名称（备注）</label><input class="input" id="qbName" value="'+esc(b.name||'')+'" placeholder="例如：客服小号1"></div>'+
  '<div class="form-row"><label>AppID（QQ开放平台机器人详情页）</label><input class="input" id="qbAppid" value="'+esc(b.appid||'')+'" '+(b.appid?'readonly':'')+'></div>'+
  '<div class="form-row"><label>AppSecret（机器人密钥，新版鉴权必需）</label><input class="input" id="qbSecret" type="password" value="" placeholder="'+(b.appSecret?'已保存，留空则不修改':'QQ开放平台 AppSecret')+'"></div>'+
  '<div class="form-row"><label><input type="checkbox" id="qbSandbox"'+(b.useSandbox?' checked':'')+'> 沙箱环境（开发调试用，正式发布请取消勾选）</label></div>'+
  '<div class="form-row"><label>使用模型（留空用 qtai-sj 自动选最快）</label><input class="input" id="qbModel" value="'+esc(b.aiModel||'')+'" placeholder="qtai-sj"></div>'+
  '<div class="form-row"><label>系统人设 / System Prompt（可选）</label><textarea class="input" id="qbPrompt" rows="3" placeholder="例如：你是綦桐AI，语气活泼…">'+esc(b.systemPrompt||'')+'</textarea></div>'+
  '<div class="form-row"><label>入群欢迎语（可选，新群首次@自动下发）</label><input class="input" id="qbWelcome" value="'+esc(b.welcome||'')+'"></div>';
 openModal(b.appid?'编辑机器人':'添加机器人', html, function(){
  var appid=$('qbAppid').value.trim();
  var token=$('qbToken') && $('qbToken').value ? $('qbToken').value.trim() : '';
  var appSecret=$('qbSecret').value.trim();
  if(!appid){ toast('AppID 必填', false); return; }
  if(!appSecret && !token && !b.appid){ toast('AppSecret 必填（新版鉴权）', false); return; }
  api('/api/qq/bots',{method:'POST',body:{
   appid:appid, token:token, appSecret:appSecret,
   useSandbox:$('qbSandbox').checked?'true':'false',
   name:$('qbName').value.trim(),
   aiModel:$('qbModel').value.trim()||'qtai-sj',
   systemPrompt:$('qbPrompt').value, welcome:$('qbWelcome').value.trim(), enabled:true
  }}).then(function(r){
   if(r.code===0){ toast('保存成功', true); closeModal(); qqLoadOverview(); } else toast(r.msg||'保存失败', false);
  });
 });
}
function qqToggle(id){
 api('/api/qq/bots/'+id+'/toggle',{method:'POST'}).then(function(r){
  toast(r.msg||'', r.code===0); if(r.code===0) qqLoadOverview();
 });
}
function qqRestart(id){
 api('/api/qq/bots/'+id+'/restart',{method:'POST'}).then(function(r){
  toast(r.msg||'已重连', r.code===0);
 });
}
function qqDel(id){
 if(!confirm('确定删除该机器人？会断开它的连接。')) return;
 api('/api/qq/bots/'+id,{method:'DELETE'}).then(function(r){
  if(r.code===0){ toast('已删除', true); qqLoadOverview(); } else toast(r.msg, false);
 });
}

// ---- 插件指令 ----
function qqLoadCmds(){
 api('/api/qq/commands').then(function(r){
  var el=$('qqCmdList'); if(!el) return;
  var list=(r&&r.data)||[];
  if(!list.length){ el.innerHTML='<div style="color:var(--muted);padding:16px">还没有指令。例如：触发词"帮助" -> 回复"发任意问题@我即可"。</div>'; return; }
  var rows=list.map(function(c){
   return '<tr><td><b>'+esc(c.name||c.trigger)+'</b></td>'+
    '<td><code style="font-size:11px">'+esc(c.trigger)+'</code></td>'+
    '<td>'+esc(c.matchType)+'</td><td>'+esc(c.action)+'</td>'+
    '<td style="max-width:220px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap">'+esc(c.content)+'</td>'+
    '<td>'+(c.enabled?'<span class="badge green">启用</span>':'<span class="badge gray">停用</span>')+'</td>'+
    '<td style="white-space:nowrap"><button class="btn-ghost btn-sm" onclick="qqCmdForm('+JSON.stringify(c).replace(/"/g,'&quot;')+')">编辑</button> '+
    '<button class="btn-ghost btn-sm danger" onclick="qqCmdDel('+c.id+')">删</button></td></tr>';
  }).join('');
  el.innerHTML='<div class="table-wrap"><table><thead><tr><th>名称</th><th>触发词</th><th>匹配</th><th>动作</th><th>内容</th><th>状态</th><th>操作</th></tr></thead><tbody>'+rows+'</tbody></table></div>';
 });
}
function qqCmdForm(c){
 c=c||{};
 var html =
  '<div class="form-row"><label>名称（备注）</label><input class="input" id="ccName" value="'+esc(c.name||'')+'"></div>'+
  '<div class="form-row"><label>触发词</label><input class="input" id="ccTrigger" value="'+esc(c.trigger||'')+'" placeholder="例如：帮助 / 天气 / 签到"></div>'+
  '<div class="form-row"><label>匹配方式</label><select class="input" id="ccMatch">'+
   '<option value="exact"'+(c.matchType==='exact'?' selected':'')+'">完全等于</option>'+
   '<option value="contains"'+(c.matchType==='contains'?' selected':'')+'">包含关键词</option>'+
   '<option value="regex"'+(c.matchType==='regex'?' selected':'')+'">正则表达式</option></select></div>'+
  '<div class="form-row"><label>动作</label><select class="input" id="ccAction" onchange="qqCmdHint()">'+
   '<option value="reply"'+(c.action==='reply'?' selected':'')+'>回复固定文案</option>'+
   '<option value="http"'+(c.action==='http'?' selected':'')+'>HTTP 接口（GET 返回内容）</option>'+
   '<option value="ai"'+(c.action==='ai'?' selected':'')+'>AI 角色扮演（内容为人设前缀）</option></select></div>'+
  '<div class="form-row"><label id="ccContentLabel">内容</label><textarea class="input" id="ccContent" rows="3">'+esc(c.content||'')+'</textarea></div>'+
  '<div class="form-row"><label>每用户冷却（秒）</label><input class="input" id="ccCd" type="number" value="'+(c.cooldown!=null?c.cooldown:5)+'"></div>'+
  '<div class="form-row"><label>优先级（越大越先匹配）</label><input class="input" id="ccPri" type="number" value="'+(c.priority||0)+'"></div>';
 openModal(c.id?'编辑指令':'新建指令', html, function(){
  api('/api/qq/commands',{method:'POST',body:{
   id:c.id, name:$('ccName').value.trim(), trigger:$('ccTrigger').value.trim(),
   matchType:$('ccMatch').value, action:$('ccAction').value, content:$('ccContent').value,
   enabled:true, cooldown:parseInt($('ccCd').value)||5, priority:parseInt($('ccPri').value)||0
  }}).then(function(r){
   if(r.code===0){ toast('已保存',true); closeModal(); qqLoadCmds(); } else toast(r.msg,false);
  });
 });
}
function qqCmdDel(id){ if(!confirm('删除该指令？'))return;
 api('/api/qq/commands/'+id,{method:'DELETE'}).then(function(r){ if(r.code===0){toast('已删除',true);qqLoadCmds();}else toast(r.msg,false); }); }
function qqCmdHelp(){
 var html = '<div style="font-size:13px;line-height:1.8;color:var(--text)">'+
  '<p><b>三种动作：</b></p>'+
  '<p>1. <b>回复固定文案</b>：触发后把「内容」原样发到群里。例：触发词=帮助，内容="发任意问题@我即可聊天"</p>'+
  '<p>2. <b>HTTP 插件</b>：触发后 GET 你填的 URL，把返回文本（前500字）发到群里。例：接一个天气/签到/机器人接口。</p>'+
  '<p>3. <b>AI 角色扮演</b>：把「内容」当人设前缀，拼上用户原话交给大模型。例：内容="你是一个毒舌吐槽王，请简短吐槽："</p>'+
  '<p style="margin-top:8px"><b>匹配方式：</b>完全等于 / 包含关键词 / 正则表达式。</p>'+
  '<p><b>内置指令</b>（无需配置）：签到、我的积分、全员禁言、解除全员禁言。</p>'+
  '<div class="code-block" style="margin-top:8px">HTTP 插件示例 URL：<br>https://你的接口/api/qq?msg=用户原话<br>要求返回纯文本，会原样发到群里</div>'+
  '</div>';
 openModal('插件开发说明', html, null, {hideFooter:true});
}

// ---- 群配置 ----
function qqLoadGroups(){
 api('/api/qq/groups').then(function(r){
  var el=$('qqGroupList'); if(!el) return;
  var list=(r&&r.data)||[];
  if(!list.length){ el.innerHTML='<div style="color:var(--muted);padding:16px">暂无群记录。让机器人进群并@它一次即可。</div>'; return; }
  var rows=list.map(function(g){
   return '<tr><td><code style="font-size:11px">'+esc(g.groupOpenid)+'</code></td>'+
    '<td>'+(g.aiEnabled?'<span class="badge green">AI开</span>':'<span class="badge gray">AI关</span>')+'</td>'+
    '<td style="max-width:220px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap">'+esc(g.greeting||'-')+'</td>'+
    '<td><button class="btn-ghost btn-sm" onclick="qqGroupEdit('+JSON.stringify(g).replace(/"/g,'&quot;')+')">配置</button></td></tr>';
  }).join('');
  el.innerHTML='<div class="table-wrap"><table><thead><tr><th>群 openid</th><th>AI</th><th>备注</th><th>操作</th></tr></thead><tbody>'+rows+'</tbody></table></div>';
 });
}
function qqGroupEdit(g){
 var html='<div class="form-row"><label>群 openid</label><input class="input" value="'+esc(g.groupOpenid)+'" readonly></div>'+
  '<div class="form-row"><label><input type="checkbox" id="ggAi" '+(g.aiEnabled?'checked':'')+'> 启用 AI 回复</label></div>'+
  '<div class="form-row"><label><input type="checkbox" id="ggWelcome" '+(g.welcomeEnabled?'checked':'')+'> 启用入群欢迎</label></div>'+
  '<div class="form-row"><label>欢迎语/备注</label><input class="input" id="ggGreeting" value="'+esc(g.greeting||'')+'"></div>';
 openModal('群配置', html, function(){
  api('/api/qq/groups/update',{method:'POST',body:{groupOpenid:g.groupOpenid,
   aiEnabled:$('ggAi').checked, welcomeEnabled:$('ggWelcome').checked, greeting:$('ggGreeting').value}})
  .then(function(r){ if(r.code===0){toast('已保存',true);closeModal();qqLoadGroups();}else toast(r.msg,false); });
 });
}

// ---- 独立用户 ----
function qqLoadUsers(){
 api('/api/qq/users').then(function(r){
  var el=$('qqUserList'); if(!el) return;
  var list=(r&&r.data)||[];
  if(!list.length){ el.innerHTML='<div style="color:var(--muted);padding:16px">暂无用户。有人@机器人后自动登记。</div>'; return; }
  var rows=list.map(function(u){
   return '<tr><td><code style="font-size:11px">'+esc(u.openid.substring(0,12))+'…</code></td>'+
    '<td>'+esc(u.displayName||'-')+'</td>'+
    '<td>'+(u.aiEnabled?'<span class="badge green">AI</span>':'<span class="badge gray">禁言</span>')+'</td>'+
    '<td>'+(u.totalMessages||0)+'</td>'+
    '<td>'+qqTime(u.lastActiveAt)+'</td>'+
    '<td><button class="btn-ghost btn-sm" onclick="qqUserEdit('+JSON.stringify(u).replace(/"/g,'&quot;')+')">人设</button></td></tr>';
  }).join('');
  el.innerHTML='<div class="table-wrap"><table><thead><tr><th>openid</th><th>昵称</th><th>AI</th><th>消息数</th><th>最后活跃</th><th>操作</th></tr></thead><tbody>'+rows+'</tbody></table></div>';
 });
}
function qqUserEdit(u){
 var html='<div class="form-row"><label>openid</label><input class="input" value="'+esc(u.openid)+'" readonly></div>'+
  '<div class="form-row"><label>昵称备注</label><input class="input" id="uuName" value="'+esc(u.displayName||'')+'"></div>'+
  '<div class="form-row"><label>独立人设（覆盖该用户的 system prompt，留空用机器人默认）</label><textarea class="input" id="uuPersona" rows="3">'+esc(u.persona||'')+'</textarea></div>'+
  '<div class="form-row"><label><input type="checkbox" id="uuAi" '+(u.aiEnabled?'checked':'')+'> 允许此用户使用 AI</label></div>'+
  '<div class="form-row" style="display:flex;gap:8px;margin-top:6px">'+
   '<button class="btn-ghost btn-sm" onclick="qqUserMemory(\''+esc(u.openid)+'\')">查看长期记忆</button>'+
   '<button class="btn-ghost btn-sm danger" onclick="qqUserMemoryClear(\''+esc(u.openid)+'\')">清空记忆</button></div>';
 openModal('用户独立配置', html, function(){
  api('/api/qq/users/update',{method:'POST',body:{openid:u.openid,
   displayName:$('uuName').value, persona:$('uuPersona').value, aiEnabled:$('uuAi').checked}})
  .then(function(r){ if(r.code===0){toast('已保存',true);closeModal();qqLoadUsers();}else toast(r.msg,false); });
 });
}
function qqUserMemory(openid){
 api('/api/qq/users/memory?openid='+encodeURIComponent(openid)).then(function(r){
  var list=(r&&r.data)||[];
  var body = list.length
   ? list.map(function(m,i){return '<div style="padding:6px 0;border-bottom:1px solid var(--border);font-size:13px">'+(i+1)+'. '+esc(m)+'</div>'}).join('')
   : '<div style="color:var(--muted);padding:12px">该用户还没有长期记忆。</div>';
  openModal('长期记忆 · '+openid.substring(0,12), body, null, {hideFooter:true});
 });
}
function qqUserMemoryClear(openid){
 if(!confirm('确认清空该用户的全部长期记忆？'))return;
 api('/api/qq/users/memory/clear',{method:'POST',body:{openid:openid}}).then(function(r){
  toast(r.msg||'已清空', r.code===0);
 });
}

// ---- 积分排行 ----
function qqLoadPoints(){
 api('/api/qq/points').then(function(r){
  var el=$('qqPointsList'); if(!el) return;
  var list=(r&&r.data)||[];
  if(!list.length){ el.innerHTML='<div style="color:var(--muted);padding:16px">暂无积分记录，群里发「签到」即可产生。</div>'; return; }
  var rows=list.map(function(p,i){
   return '<tr><td>'+(i+1)+'</td><td><code style="font-size:11px">'+esc(p.openid.substring(0,14))+'…</code></td>'+
    '<td><b style="color:var(--amber)">'+(p.points||0)+'</b></td>'+
    '<td>'+(p.signCount||0)+'</td><td>'+esc(p.lastSignDate||'-')+'</td>'+
    '<td style="white-space:nowrap"><button class="btn-ghost btn-sm" onclick="qqPointsAdjust(\''+esc(p.openid)+'\',10)">+10</button> '+
    '<button class="btn-ghost btn-sm" onclick="qqPointsAdjust(\''+esc(p.openid)+'\',-10)">-10</button></td></tr>';
  }).join('');
  el.innerHTML='<div class="table-wrap"><table><thead><tr><th>#</th><th>openid</th><th>积分</th><th>签到次数</th><th>最近签到</th><th>调整</th></tr></thead><tbody>'+rows+'</tbody></table></div>';
 });
}
function qqPointsAdjust(openid,delta){
 api('/api/qq/points/adjust',{method:'POST',body:{openid:openid,delta:delta}}).then(function(r){
  toast(r.msg||'已调整', r.code===0); if(r.code===0) qqLoadPoints();
 });
}

// ---- 日志 ----
function qqLoadLogs(){
 api('/api/qq/logs').then(function(r){
  var el=$('qqLogList'); if(!el) return;
  var list=(r&&r.data)||[];
  if(!list.length){ el.innerHTML='<div style="color:var(--muted);padding:16px">暂无日志</div>'; return; }
  var rows=list.map(function(l){
   return '<tr><td><span class="badge">'+esc(l.type)+'</span></td>'+
    '<td style="max-width:380px">'+esc(l.content)+'</td>'+
    '<td>'+(l.latencyMs||0)+'ms</td><td>'+qqTime(l.createdAt)+'</td></tr>';
  }).join('');
  el.innerHTML='<div class="table-wrap"><table><thead><tr><th>类型</th><th>内容</th><th>耗时</th><th>时间</th></tr></thead><tbody>'+rows+'</tbody></table></div>';
 });
}
""".trimIndent()
}
