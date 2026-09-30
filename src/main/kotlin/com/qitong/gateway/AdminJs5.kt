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
   ['overview','动态概览'],['cmds','插件指令'],['plugins','📦插件包'],['groups','群配置'],
   ['users','独立用户'],['points','积分排行'],['games','🎮游戏'],['logs','运行日志']
  ].map(function(x){
   return '<button class="log-tab" style="'+(qqTab===x[0]?'color:var(--primary);border-bottom-color:var(--primary);font-weight:600':'')+'" onclick="qqTabBtn(\''+x[0]+'\')">'+x[1]+'</button>';
  }).join('');
  box.innerHTML = '<div class="card"><div class="qq-tabs">'+tabs+'</div>'+
   '<div id="qqPane">'+qqTabHtml()+'</div></div>';
  if(qqTab==='overview') qqLoadOverview();
  if(qqTab==='cmds') qqLoadCmds();
  if(qqTab==='plugins') qqLoadPlugins();
  if(qqTab==='groups') qqLoadGroups();
  if(qqTab==='users') qqLoadUsers();
  if(qqTab==='points') qqLoadPoints();
  if(qqTab==='games') qqLoadGames();
  if(qqTab==='logs') qqLoadLogs();
  };
function qqTabHtml(){
 if(qqTab==='overview') return '<div id="qqOverview"><div style="color:var(--muted);padding:20px">加载中…</div></div>';
 if(qqTab==='cmds') return '<div class="action-bar"><button class="btn" onclick="qqCmdForm()">+ 新建指令插件</button><button class="btn-ghost" onclick="qqCmdHelp()">插件开发说明</button><span style="font-size:12px;color:var(--muted)">小栗子式：触发词 -> 回复/HTTP/AI，按优先级匹配，命中即停</span></div><div id="qqCmdList"></div>';
 if(qqTab==='plugins') return '<div class="action-bar"><button class="btn" onclick="qqPluginUpload()">📦 上传插件包(zip)</button><span style="font-size:12px;color:var(--muted)">上传压缩包安装插件，可起名+菜单；QQ 发「菜单」查看，发插件命令触发</span></div><div id="qqPluginList"></div>';
 if(qqTab==='groups') return '<div id="qqGroupList"><div style="color:var(--muted);padding:20px">加载中…</div></div>';
 if(qqTab==='users') return '<div id="qqUserList"><div style="color:var(--muted);padding:20px">加载中…</div></div>';
 if(qqTab==='points') return '<div style="font-size:12px;color:var(--muted);margin-bottom:8px">群里发「签到」每日得 5-20 积分，「我的积分」查询。此处可查看排行并手动调整。</div><div id="qqPointsList"></div>';
 if(qqTab==='games') return '<div id="qqGames"><div style="color:var(--muted);padding:20px">加载中…</div></div>';
 return '<div class="action-bar"><button class="btn-ghost" onclick="qqLoadLogs()">刷新</button><button class="btn-ghost" onclick="qqClearGroupLogs()">🗑 按群清理</button><button class="btn-ghost danger" onclick="qqLogsClear()">清空全部</button><span style="font-size:12px;color:var(--muted)">实时动态最多保留最近 50 条，超出自动删除；按群清理可单独清某个群的聊天记录</span></div><div id="qqLogList"></div>';
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
    '<option value="reply"'+(c.action==='reply'?' selected':'')+'>💬 回复固定文案</option>'+
    '<option value="http"'+(c.action==='http'?' selected':'')+'>🌐 HTTP 接口（GET 返回内容）</option>'+
    '<option value="ai"'+(c.action==='ai'?' selected':'')+'>🤖 AI 角色扮演（内容为人设前缀）</option>'+
    '<option value="terminal"'+(c.action==='terminal'?' selected':'')+'>🖥 终端命令（内容为命令，管理级）</option>'+
    '<option value="skill"'+(c.action==='skill'?' selected':'')+'>⚙️ 网关技能（内容为技能编码如600001）</option>'+
    '<option value="workflow"'+(c.action==='workflow'?' selected':'')+'>⚡ 工作流（内容为工作流名称）</option>'+
    '<option value="image"'+(c.action==='image'?' selected':'')+'>🎨 AI 画图（内容为提示词前缀）</option></select></div>'+
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
function qqCmdHint(){
 var a = $('ccAction') ? $('ccAction').value : '';
 var hints = {reply:'内容：触发后原样发送的文案', http:'内容：要 GET 的接口 URL（返回纯文本）', ai:'内容：AI 人设前缀（拼接用户原话）', terminal:'内容：要执行的 Linux 命令（需管理级3+权限）', skill:'内容：网关技能编码（如 600001=查状态 / 600002=测速排行）', workflow:'内容：工作流名称（触发后执行整条工作流）', image:'内容：画图提示词前缀（拼接用户原话）'};
 var el = $('ccContentLabel'); if(el) el.textContent = hints[a] || '内容';
}
function qqCmdHelp(){
var html = '<div style="font-size:13px;line-height:1.8;color:var(--text)">'+
   '<p><b>七种动作（插件强化版）：</b></p>'+
   '<p>1. <b>💬 回复固定文案</b>：触发后把「内容」原样发到群里。例：触发词=帮助，内容="发任意问题@我即可聊天"</p>'+
   '<p>2. <b>🌐 HTTP 插件</b>：触发后 GET 你填的 URL，把返回文本（前500字）发到群里。例：接一个天气/签到/机器人接口。</p>'+
   '<p>3. <b>🤖 AI 角色扮演</b>：把「内容」当人设前缀，拼上用户原话交给大模型。例：内容="你是一个毒舌吐槽王，请简短吐槽："</p>'+
   '<p>4. <b>🖥 终端命令</b>：触发后执行「内容」里的 Linux 命令，返回输出（需用户管理级权限3+）。例：内容="df -h"</p>'+
   '<p>5. <b>⚙️ 网关技能</b>：触发后执行「内容」里的技能编码。例：内容="600001"（查状态）/"600002"（测速排行）</p>'+
   '<p>6. <b>⚡ 工作流</b>：触发后执行「内容」里的工作流名称。例：内容="每日早报"</p>'+
   '<p>7. <b>🎨 AI 画图</b>：触发后调画图模型生成图片（内容为提示词前缀+用户原话）。</p>'+
   '<p style="margin-top:8px"><b>匹配方式：</b>完全等于 / 包含关键词 / 正则表达式。</p>'+
   '<p><b>内置指令</b>（无需配置）：签到、我的积分、全员禁言、解除全员禁言。</p>'+
  '<div class="code-block" style="margin-top:8px">HTTP 插件示例 URL：<br>https://你的接口/api/qq?msg=用户原话<br>要求返回纯文本，会原样发到群里</div>'+
  '</div>';
 openModal('插件开发说明', html, null, {hideFooter:true});
}

// ---- 插件包（上传 zip 安装） ----
function qqLoadPlugins(){
 api('/api/qq/plugins').then(function(r){
  var el=$('qqPluginList'); if(!el) return;
  var list=(r&&r.data)||[];
  if(!list.length){ el.innerHTML='<div style="color:var(--muted);padding:16px">还没有插件。点「📦 上传插件包」安装第一个（压缩包含游戏/菜单 txt）。</div>'; return; }
  var rows=list.map(function(p){
   return '<tr><td><b>'+esc(p.name)+'</b> <span class="badge blue">v'+esc(p.version)+'</span></td>'+
    '<td style="max-width:220px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap">'+esc(p.description||'-')+'</td>'+
    '<td style="max-width:200px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;font-size:11px">'+esc((p.menu||'').slice(0,50))+'</td>'+
    '<td>'+(p.enabled?'<span class="badge green">启用</span>':'<span class="badge gray">停用</span>')+'</td>'+
    '<td><button class="btn-ghost btn-sm danger" onclick="qqPluginDel(\''+esc(p.name)+'\')">删</button></td></tr>';
  }).join('');
  el.innerHTML='<div class="table-wrap"><table><thead><tr><th>插件名</th><th>描述</th><th>菜单</th><th>状态</th><th>操作</th></tr></thead><tbody>'+rows+'</tbody></table></div>'+
   '<div style="font-size:12px;color:var(--muted);margin-top:8px">QQ 里发「菜单」查看已装插件，发插件命令（如 抽奖/打怪/猜数字）触发游戏</div>';
 });
}
function qqPluginUpload(){
 var html = '<div class="form-row"><label>插件名称（压缩包起个名字）</label><input class="input" id="plName" placeholder="如：小游戏合集"></div>'+
  '<div class="form-row"><label>描述</label><input class="input" id="plDesc" placeholder="如：含抽奖/打怪/猜数字等游戏"></div>'+
  '<div class="form-row"><label>版本</label><input class="input" id="plVer" value="1.0.0"></div>'+
  '<div class="form-row"><label>菜单说明（QQ发「菜单」显示）</label><textarea class="input" id="plMenu" rows="3" placeholder="抽奖-消耗积分抽奖\n打怪-打怪升级\n猜数字-猜数字游戏"></textarea></div>'+
  '<div class="form-row"><label>压缩包(zip)</label><input class="input" type="file" id="plFile" accept=".zip"></div>';
 openModal('📦 上传插件包', html, function(){
  var name=$('plName').value.trim();
  var f=$('plFile').files[0];
  if(!name){ toast('插件名必填',false); return; }
  if(!f){ toast('请选择zip压缩包',false); return; }
  var reader=new FileReader();
  reader.onload=function(){
   var b64=reader.result;
   api('/api/qq/plugins/upload',{method:'POST',body:{
    name:name, description:$('plDesc').value, version:$('plVer').value||'1.0.0',
    menu:$('plMenu').value, author:'', data:b64
   }}).then(function(r){
    if(r.code===0){ toast(r.msg,true); closeModal(); qqLoadPlugins(); } else toast(r.msg||'上传失败',false);
   });
  };
  reader.readAsDataURL(f);
 });
}
function qqPluginDel(name){
 if(!confirm('确认删除插件「'+name+'」？')) return;
 api('/api/qq/plugins/'+encodeURIComponent(name),{method:'DELETE'}).then(function(r){
  if(r.code===0){ toast('已删除',true); qqLoadPlugins(); } else toast(r.msg||'删除失败',false);
 });
}
function qqLoadGroups(){
 api('/api/qq/groups').then(function(r){
  var el=$('qqGroupList'); if(!el) return;
  var list=(r&&r.data)||[];
  window._qqGroups = list;
  if(!list.length){ el.innerHTML='<div style="color:var(--muted);padding:16px">暂无群记录。让机器人进群并@它一次即可。</div>'; return; }
  var rows=list.map(function(g){
return '<tr><td><b>'+esc(g.groupName||'-')+'</b><br><code style="font-size:11px">'+esc(g.groupOpenid)+'</code></td>'+
    '<td>'+(g.aiEnabled?'<span class="badge green">AI开</span>':'<span class="badge gray">AI关</span>')+'</td>'+
    '<td style="max-width:180px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap">'+esc(g.greeting||'-')+'</td>'+
    '<td style="white-space:nowrap"><button class="btn-ghost btn-sm" onclick="qqGroupEdit(\''+esc(g.groupOpenid)+'\')">配置</button> '+
    '<button class="btn-ghost btn-sm danger" onclick="qqGroupDel(\''+esc(g.groupOpenid)+'\')">删</button></td></tr>';
  }).join('');
  el.innerHTML='<div class="table-wrap"><table><thead><tr><th>群 openid</th><th>AI</th><th>备注</th><th>操作</th></tr></thead><tbody>'+rows+'</tbody></table></div>';
 });
}
function qqGroupDel(openid){
 if(!confirm('确认删除该群？将同时清空该群下所有用户记录与自动化任务。')) return;
 api('/api/qq/groups/'+openid,{method:'DELETE'}).then(function(r){
  if(r.code===0){ toast('群已删除',true); qqLoadGroups(); } else toast(r.msg||'删除失败',false);
 });
}
function qqGroupEdit(openid){
 // 从列表重新拉群数据（避免 JSON 传对象引号问题）
 api('/api/qq/groups').then(function(r){
  var list=(r&&r.data)||[];
  var g=list.filter(function(x){return x.groupOpenid===openid;})[0];
  if(!g){ toast('群不存在',false); return; }
  var html='<div class="form-row"><label>群 openid</label><input class="input" value="'+esc(g.groupOpenid)+'" readonly></div>'+
  '<div class="form-row"><label><input type="checkbox" id="ggAi" '+(g.aiEnabled?'checked':'')+'> 启用 AI 回复</label></div>'+
  '<div class="form-row"><label><input type="checkbox" id="ggWelcome" '+(g.welcomeEnabled?'checked':'')+'> 启用入群欢迎</label></div>'+
  '<div class="form-row"><label>欢迎语/备注</label><input class="input" id="ggGreeting" value="'+esc(g.greeting||'')+'"></div>'+
  '<div class="form-row"><label>群名称/备注</label><input class="input" id="ggName" value="'+esc(g.groupName||'')+'" placeholder="如：綦桐开发群"></div>'+
  '<div class="form-row"><label>群专属提示词（System Prompt，覆盖机器人默认人设，留空用默认）</label><textarea class="input" id="ggPrompt" rows="3">'+esc(g.groupPrompt||'')+'</textarea></div>'+
  '<hr style="border-color:var(--border);margin:10px 0"><div style="font-size:12px;color:var(--muted);margin-bottom:6px">🛠 群管权限开关（机器人需为本群管理员才生效）</div>'+
  '<div class="form-row"><label><input type="checkbox" id="ggMute" '+(g.adminMute!==false?'checked':'')+'> 允许全员禁言/解除</label></div>'+
  '<div class="form-row"><label><input type="checkbox" id="ggKick" '+(g.adminKick!==false?'checked':'')+'> 允许群员管理</label></div>'+
  '<div class="form-row"><label><input type="checkbox" id="ggManage" '+(g.adminManage!==false?'checked':'')+'> 允许管理操作</label></div>';
  openModal('群配置', html, function(){
   api('/api/qq/groups/update',{method:'POST',body:{groupOpenid:g.groupOpenid,
    aiEnabled:$('ggAi').checked, welcomeEnabled:$('ggWelcome').checked, greeting:$('ggGreeting').value,
    groupName:$('ggName').value, adminMute:$('ggMute').checked, adminKick:$('ggKick').checked, adminManage:$('ggManage').checked, groupPrompt:$('ggPrompt').value}})
   .then(function(r){ if(r.code===0){toast('已保存',true);closeModal();qqLoadGroups();}else toast(r.msg,false); });
  });
 });
}

// ---- 独立用户 ----
function qqLoadUsers(){
 api('/api/qq/users').then(function(r){
  var el=$('qqUserList'); if(!el) return;
  var list=(r&&r.data)||[];
  if(!list.length){ el.innerHTML='<div style="color:var(--muted);padding:16px">暂无用户。有人@机器人后自动登记。</div>'; return; }
  var rows=list.map(function(u){
   var permTxt = ['⛔禁止','👀查询','🔧操作','🛠管理','✅全部'][u.permLevel] || '查询';
   return '<tr><td><code style="font-size:11px">'+esc(u.openid.substring(0,12))+'…</code></td>'+
    '<td>'+esc(u.displayName||'-')+'</td>'+
    '<td>'+(u.aiEnabled?'<span class="badge green">AI</span>':'<span class="badge gray">禁言</span>')+'</td>'+
    '<td><span class="badge '+(u.permLevel>=3?'purple':(u.permLevel==0?'gray':'blue'))+'">'+permTxt+'</span></td>'+
    '<td>'+(u.totalMessages||0)+'</td>'+
    '<td>'+qqTime(u.lastActiveAt)+'</td>'+
'<td><button class="btn-ghost btn-sm" onclick="qqUserEdit(\''+esc(u.openid)+'\')">配置</button> '+
     '<button class="btn-ghost btn-sm" onclick="qqUserMemory(\''+esc(u.openid)+'\')">🧠记忆</button> '+
     '<button class="btn-ghost btn-sm danger" onclick="qqUserDel(\''+esc(u.openid)+'\')">删</button></td></tr>';
  }).join('');
  el.innerHTML='<div class="table-wrap"><table><thead><tr><th>openid</th><th>昵称</th><th>AI</th><th>权限</th><th>消息数</th><th>最后活跃</th><th>操作</th></tr></thead><tbody>'+rows+'</tbody></table></div>'+
   '<div style="font-size:12px;color:var(--muted);margin-top:8px">权限：禁止=不能操作机器人 · 查询=查状态/排行/余额 · 操作=切模型/测速 · 管理=启停/充值/改配置 · 全部=所有网关操作</div>';
 });
}
function qqUserDel(openid){
 if(!confirm('确认删除该用户？其所有群内的记录与权限将一并清除。')) return;
 api('/api/qq/users/'+openid,{method:'DELETE'}).then(function(r){
  if(r.code===0){ toast('用户已删除',true); qqLoadUsers(); } else toast(r.msg||'删除失败',false);
 });
}
function qqUserEdit(openid){
 // 从当前列表重新拉用户（避免 JSON 传对象引号问题）
 api('/api/qq/users').then(function(r){
  var list=(r&&r.data)||[];
  var u=list.filter(function(x){return x.openid===openid;})[0];
  if(!u){ toast('用户不存在',false); return; }
  var html='<div class="form-row"><label>openid</label><input class="input" value="'+esc(u.openid)+'" readonly></div>'+
  '<div class="form-row"><label>昵称备注</label><input class="input" id="uuName" value="'+esc(u.displayName||'')+'"></div>'+
  '<div class="form-row"><label>独立人设（覆盖该用户的 system prompt，留空用机器人默认）</label><textarea class="input" id="uuPersona" rows="3">'+esc(u.persona||'')+'</textarea></div>'+
  '<div class="form-row"><label><input type="checkbox" id="uuAi" '+(u.aiEnabled?'checked':'')+'> 允许此用户使用 AI</label></div>'+
  '<div class="form-row"><label>网关操作权限</label><select class="input" id="uuPerm">'+
   '<option value="0"'+(u.permLevel==0?' selected':'')+'>⛔ 禁止（不能操作机器人）</option>'+
   '<option value="1"'+(u.permLevel==1?' selected':'')+'>👀 查询（状态/排行/余额）</option>'+
   '<option value="2"'+(u.permLevel==2?' selected':'')+'>🔧 操作（切模型/测速）</option>'+
   '<option value="3"'+(u.permLevel==3?' selected':'')+'>🛠 管理（启停/充值/配置）</option>'+
   '<option value="4"'+(u.permLevel==4?' selected':'')+'>✅ 全部（所有网关操作）</option></select></div>'+
  '<div class="form-row" style="display:flex;gap:8px;margin-top:6px">'+
   '<button class="btn-ghost btn-sm" onclick="qqUserMemory(\''+esc(u.openid)+'\')">查看长期记忆</button>'+
   '<button class="btn-ghost btn-sm danger" onclick="qqUserMemoryClear(\''+esc(u.openid)+'\')">清空记忆</button></div>';
  openModal('用户独立配置', html, function(){
   var permLevel = parseInt($('uuPerm').value) || 1;
   api('/api/qq/users/perm',{method:'POST',body:{openid:u.openid, level:permLevel, flags:''}})
   .then(function(r){ if(r.code!==0){ toast(r.msg||'权限保存失败',false); return; } });
   api('/api/qq/users/update',{method:'POST',body:{openid:u.openid,
    displayName:$('uuName').value, persona:$('uuPersona').value, aiEnabled:$('uuAi').checked}})
   .then(function(r){ if(r.code===0){toast('已保存',true);closeModal();qqLoadUsers();}else toast(r.msg,false); });
  });
 });
}
function qqUserMemory(openid){
 api('/api/qq/users/memory/detail?openid='+encodeURIComponent(openid)).then(function(r){
  var list=(r&&r.data)||[];
  var body = list.length
   ? '<div style="max-height:60vh;overflow:auto">'+list.map(function(m){
      return '<div style="padding:6px 0;border-bottom:1px solid var(--border);font-size:13px;display:flex;gap:8px;align-items:flex-start">'+
       '<span style="flex:1">'+esc(m.content)+'<div style="font-size:11px;color:var(--muted);margin-top:2px">'+qqTime(m.timestamp)+' · '+esc(m.type||'short')+'</div></span>'+
       '<button class="btn-ghost btn-sm danger" onclick="qqMemoryDel('+(m.id||0)+',\''+esc(openid)+'\')">删</button></div>';
     }).join('')+'</div>'
   : '<div style="color:var(--muted);padding:12px">该用户还没有长期记忆。</div>';
  openModal('长期记忆 · '+openid.substring(0,12), body, null, {hideFooter:true});
 });
}
function qqMemoryDel(id,openid){
 if(!confirm('确认删除这条记忆？')) return;
 api('/api/qq/users/memory/'+id,{method:'DELETE'}).then(function(r){
  toast(r.msg||'', r.code===0); if(r.code===0) qqUserMemory(openid);
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

// ---- 游戏（QQ机器人内置文字游戏说明） ----
function qqLoadGames(){
 var el=$('qqGames'); if(!el) return;
 el.innerHTML = [
  '<div class="card" style="box-shadow:none"><h3>🎮 内置文字游戏（群里 @机器人 即可玩）</h3>',
  '<div style="display:grid;grid-template-columns:repeat(auto-fill,minmax(220px,1fr));gap:10px;margin-top:10px">',
  '<div class="stat"><div class="lbl" style="font-size:14px;font-weight:600;color:var(--text)">🎲 猜数字</div><div style="font-size:12px;color:var(--muted);margin-top:6px">发「开始猜数字」开局，回复「猜 50」试猜，大/小提示 + 次数统计</div></div>',
  '<div class="stat"><div class="lbl" style="font-size:14px;font-weight:600;color:var(--text)">🧩 成语接龙</div><div style="font-size:12px;color:var(--muted);margin-top:6px">发「开始成语接龙」开局，回复「成语 四字词」接龙（首尾字/谐音）</div></div>',
  '<div class="stat"><div class="lbl" style="font-size:14px;font-weight:600;color:var(--text)">🎲 骰子</div><div style="font-size:12px;color:var(--muted);margin-top:6px">发「骰子」/「掷骰子」，双骰 7/11 大赢</div></div>',
  '<div class="stat"><div class="lbl" style="font-size:14px;font-weight:600;color:var(--text)">🃏 抽卡</div><div style="font-size:12px;color:var(--muted);margin-top:6px">发「抽卡」/「抽奖」，SSR 3% / SR 15% / R 45% / N 卡</div></div>',
  '</div><div style="font-size:12px;color:var(--muted);margin-top:12px">💡 游戏状态按用户独立，每人互不干扰；无需权限门槛，纯娱乐</div></div>'
 ].join('');
}

// ---- 运行日志：删除/清空/按群清理 ----
function qqClearGroupLogs(){
 var groups = window._qqGroups || [];
 if(groups.length){
  // 有群列表，弹出可选群
  var opts = groups.map(function(g){ return '<option value="'+esc(g.groupOpenid)+'">'+esc(g.groupName||g.groupOpenid)+'</option>'; }).join('');
  openModal('按群清理聊天记录','<div class="form-row"><label>选择群</label><select id="clrGroupSel">'+opts+'</select></div>', function(){
   var sel=$('clrGroupSel'); if(!sel) return;
   var groupOpenid=sel.value; if(!groupOpenid){ toast('请选择群',false); return; }
   if(!confirm('确认清空该群的全部聊天记录？')) return;
   api('/api/qq/logs/clear-group',{method:'POST',body:{groupOpenid:groupOpenid}}).then(function(r){
    toast(r.msg||'', r.code===0); if(r.code===0){ closeModal(); qqLoadLogs(); }
   });
  });
  return;
 }
 // 无群列表，直接输入群号
 var gid = prompt('输入要清理的群 openid（可在群配置页查看）：');
 if(!gid) return;
 if(!confirm('确认清空该群的全部聊天记录？')) return;
 api('/api/qq/logs/clear-group',{method:'POST',body:{groupOpenid:gid.trim()}}).then(function(r){
  toast(r.msg||'', r.code===0); if(r.code===0) qqLoadLogs();
 });
}
function qqLogsClear(){
 if(!confirm('确定清空全部运行日志？')) return;
 api('/api/qq/logs/clear',{method:'POST'}).then(function(r){
  toast(r.msg||'', r.code===0); if(r.code===0) qqLoadLogs();
 });
}
function qqLogDel(id){
 if(!confirm('删除这条日志？')) return;
 api('/api/qq/logs/'+id,{method:'DELETE'}).then(function(r){
  toast(r.msg||'', r.code===0); if(r.code===0) qqLoadLogs();
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
    '<td>'+(l.latencyMs||0)+'ms</td><td>'+qqTime(l.createdAt)+'</td>'+
    '<td><button class="btn-ghost btn-sm danger" onclick="qqLogDel('+l.id+')">删</button></td></tr>';
  }).join('');
  el.innerHTML='<div class="table-wrap"><table><thead><tr><th>类型</th><th>内容</th><th>耗时</th><th>时间</th><th>操作</th></tr></thead><tbody>'+rows+'</tbody></table></div>'+
   '<div style="font-size:12px;color:var(--muted);margin-top:6px">共 '+list.length+' 条（实时动态自动保留最近 50 条）</div>';
 });
}

// ===== 沙盒 Linux 终端（临时会话） =====
var termCur = null;
loaders.terminal = function(){
 var box = $('view-terminal');
 box.innerHTML = '<div class="action-bar">'+
  '<button class="btn" onclick="termCreate()">+ 创建终端</button>'+
  '<button class="btn" onclick="termAiHelp()">🤖 AI 智能操作</button>'+
  '<button class="btn-ghost" onclick="loaders.terminal()">刷新</button>'+
  '<button class="btn-ghost danger" onclick="termCloseAll()">关闭全部</button>'+
  '<span style="font-size:12px;color:var(--muted)">临时会话：默认30分钟无操作自动清理；可设永久（ttl=0）</span></div>'+
  '<div id="termList"><div style="color:var(--muted);padding:20px">加载中…</div></div>';
 termLoad();
};
function termLoad(){
 api('/api/terminal/sessions').then(function(r){
  var el=$('termList'); if(!el) return;
  var list=(r&&r.data)||[];
  if(!list.length){ el.innerHTML='<div class="card" style="color:var(--muted);text-align:center;padding:30px">暂无终端会话，点「+ 创建终端」开始（自建沙盒 Linux，可跑任何命令）</div>'; return; }
  var rows=list.map(function(s){
   var ttlTxt = s.ttlMinutes===0 ? '<span class="badge purple">永久</span>' : '<span class="badge gray">'+s.ttlMinutes+'分钟</span>';
   return '<tr>'+
    '<td><b>'+esc(s.label)+'</b><br><code style="font-size:10px">'+esc(s.id)+'</code></td>'+
    '<td>'+ttlTxt+'</td>'+
    '<td>'+(s.commands||0)+'</td>'+
    '<td style="font-size:11px;color:var(--muted)">'+qqTime(s.lastActiveAt)+'</td>'+
    '<td style="white-space:nowrap">'+
     '<button class="btn-ghost btn-sm" onclick="termOpen(\''+esc(s.id)+'\')">打开</button> '+
     '<button class="btn-ghost btn-sm" onclick="termSetTtl(\''+esc(s.id)+'\')">时长</button> '+
     '<button class="btn-ghost btn-sm danger" onclick="termClose(\''+esc(s.id)+'\')">关闭</button></td></tr>';
  }).join('');
  el.innerHTML='<div class="card" style="box-shadow:none"><div class="table-wrap"><table><thead><tr><th>会话</th><th>时长</th><th>命令数</th><th>最后活跃</th><th>操作</th></tr></thead><tbody>'+rows+'</tbody></table></div></div>';
 });
}
function termAiHelp(){
 api('/api/terminal/sessions').then(function(r){
  var opts='<option value="">自动创建新会话</option>';
  (r&&r.data||[]).forEach(function(s){ opts+='<option value="'+s.id+'">'+esc(s.label)+' ('+s.id+')</option>'; });
  openModal('🤖 AI 智能操作终端', '<div style="font-size:13px;color:var(--muted);line-height:1.7;margin-bottom:10px">告诉 AI 你想干什么，它自动生成 Linux 命令并执行。例如：<br>· 看看磁盘占用<br>· 查看当前目录文件<br>· 装一个 python 库<br>· 查看 nginx 配置<br>· 查某个进程</div><div class="form-row"><label>选择会话（留空自动创建）</label><select class="input" id="aiTermSel">'+opts+'</select></div><div class="form-row"><label>你的需求（自然语言）</label><textarea class="input" id="aiTermReq" rows="3" placeholder="例如：查看当前目录所有文件大小排序"></textarea></div>', function(){
  var sel=$('aiTermSel'); var req=$('aiTermReq').value.trim();
  if(!req){ toast('请输入需求',false); return; }
  var sid = sel && sel.value ? sel.value : '';
  toast('🤖 AI 思考中…', false);
  api('/api/terminal/ai-exec',{method:'POST',body:{req:req, id:sid}}).then(function(r){
   if(r.code===0){
    openModal('AI 执行结果', '<div style="font-family:monospace;background:#0b1120;color:#e2e8f0;border-radius:6px;padding:10px;font-size:12px;max-height:280px;overflow-y:auto;white-space:pre-wrap">'+
     '<div style="color:var(--cyan)">$ '+esc(r.data.cmd)+'</div>\n\n'+esc(r.data.output)+'</div>'+
     '<div style="font-size:12px;color:var(--muted);margin-top:8px">会话：'+esc(r.data.sessionId)+'（可在下方终端列表打开继续操作）</div>');
   } else toast(r.msg||'执行失败', false);
  });
 });
 });
}
function termSetTtl(id){
 openModal('设置会话时长', '<div class="form-row"><label>无操作保留时长</label><select class="input" id="ttlSel">'+
  '<option value="0">永久（不自动清理）</option><option value="30" selected>30分钟</option><option value="60">1小时</option>'+
  '<option value="180">3小时</option><option value="720">12小时</option><option value="1440">24小时</option></select></div>', function(){
  api('/api/terminal/set-ttl',{method:'POST',body:{id:id, ttlMinutes:parseInt($('ttlSel').value)||30}}).then(function(r){
   toast(r.msg||'', r.code===0); if(r.code===0) loaders.terminal();
  });
 });
}
function termCreate(){
 openModal('创建终端', '<div class="form-row"><label>会话名称（可选）</label><input class="input" id="termLabel" placeholder="如：测试环境"></div>'+
  '<div class="form-row"><label>保留时长</label><select class="input" id="termTtl">'+
  '<option value="30" selected>30分钟（默认）</option><option value="0">永久（不清理）</option><option value="60">1小时</option>'+
  '<option value="180">3小时</option><option value="720">12小时</option><option value="1440">24小时</option></select></div>', function(){
  api('/api/terminal/create',{method:'POST',body:{label:$('termLabel').value.trim(), ttlMinutes:parseInt($('termTtl').value)||30}}).then(function(r){
   if(r.code===0){ toast(r.msg,true); closeModal(); loaders.terminal(); } else toast(r.msg,false);
  });
 });
}
function termOpen(id){
 termCur=id;
 api('/api/terminal/sessions').then(function(r){
  var list=(r&&r.data)||[]; var s=list.filter(function(x){return x.id===id;})[0];
  if(!s){ toast('会话不存在',false); return; }
  openModal('终端 - '+esc(s.label)+' <code>'+esc(s.id)+'</code>',
   '<div style="background:#0b1120;color:#e2e8f0;border-radius:6px;padding:10px;font-family:monospace;font-size:12px;height:220px;overflow-y:auto;white-space:pre-wrap" id="termOut">'+esc(s.output||'(新会话)')+'</div>'+
   '<div class="form-row" style="margin-top:10px"><input class="input" id="termCmd" placeholder="输入命令，Enter 执行，如 ls -la" style="font-family:monospace" onkeydown="if(event.key===\'Enter\')termExec()"></div>'+
   '<button class="btn" onclick="termExec()">执行</button>',
   function(){ termCur=null; });
 });
}
function termExec(){
 var c=$('termCmd').value.trim(); if(!c) return;
 api('/api/terminal/exec',{method:'POST',body:{id:termCur,cmd:c}}).then(function(r){
  if(r.code===0){
   var out=$('termOut'); if(out){ out.innerHTML = esc(r.data.output || '(无输出)'); out.scrollTop = out.scrollHeight; }
   $('termCmd').value='';
  } else toast(r.msg,false);
 });
}
function termClose(id){
 if(!confirm('关闭该终端会话？')) return;
 api('/api/terminal/close',{method:'POST',body:{id:id}}).then(function(r){
  toast(r.msg, r.code===0); loaders.terminal();
 });
}
function termCloseAll(){
 if(!confirm('关闭全部终端会话？')) return;
 api('/api/terminal/close',{method:'POST',body:{id:''}}).then(function(r){
  toast(r.msg, r.code===0); loaders.terminal();
 });
}
""".trimIndent()
}
