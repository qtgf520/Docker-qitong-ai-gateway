package com.qitong.gateway

/** 后台 JS 第五部分：QQ 开放平台机器人管理（多号/群配置） */
object AdminJs5 {
  fun js(): String = """
// ===== QQ 机器人管理 =====
loaders.qqbot = function(){
 var box = $('view-qqbot');
 box.innerHTML = '<div class="card"><h3>QQ 开放平台机器人</h3><div class="action-bar">'+
  '<button class="btn" onclick="qqOpenForm()">+ 添加机器人</button>'+
  '<button class="btn-ghost" onclick="loaders.qqbot()">刷新</button></div>'+
  '<div id="qqBotList"><div style="color:var(--muted);padding:20px">加载中...</div></div></div>'+
  '<div class="card"><h3>群配置（收到消息后自动登记）</h3><div id="qqGroupList"><div style="color:var(--muted);padding:20px">加载中...</div></div></div>';
 qqLoadBots();
 qqLoadGroups();
};

function qqLoadBots(){
 api('/api/qq/bots').then(function(r){
  var el = $('qqBotList'); if(!el) return;
  var list = (r && r.data) || [];
  if(!list.length){ el.innerHTML = '<div style="color:var(--muted);padding:16px">还没有机器人，点上方「添加机器人」接入第一个。</div>'; return; }
  var rows = list.map(function(b){
   var badge = b.online ? '<span class="badge green">在线</span>'
    : (b.status==='ERROR' ? '<span class="badge red">异常</span>' : '<span class="badge gray">离线</span>');
   var err = b.lastError ? '<div style="font-size:11px;color:var(--red);margin-top:4px">'+esc(b.lastError)+'</div>' : '';
   return '<tr>'+
    '<td><b>'+esc(b.name||'未命名')+'</b>'+err+'</td>'+
    '<td><code style="font-size:11px">'+esc(b.appid)+'</code></td>'+
    '<td>'+badge+'</td>'+
    '<td>'+esc(b.aiModel||'qtai-sj')+'</td>'+
    '<td>'+(b.messagesHandled||0)+'</td>'+
    '<td style="white-space:nowrap">'+
     '<button class="btn-ghost btn-sm" onclick="qqToggle('+b.id+','+(!b.enabled)+')">'+(b.enabled?'停用':'启用')+'</button> '+
     '<button class="btn-ghost btn-sm" onclick="qqRestart('+b.id+')">重连</button> '+
     '<button class="btn-ghost btn-sm" onclick="qqOpenForm('+JSON.stringify(b).replace(/"/g,'&quot;')+')">编辑</button> '+
     '<button class="btn-ghost btn-sm danger" onclick="qqDel('+b.id+')">删除</button>'+
    '</td></tr>';
  }).join('');
  el.innerHTML = '<div class="table-wrap"><table><thead><tr>'+
   '<th>名称</th><th>AppID</th><th>状态</th><th>模型</th><th>已处理消息</th><th>操作</th>'+
   '</tr></thead><tbody>'+rows+'</tbody></table></div>';
 });
}

function qqLoadGroups(){
 api('/api/qq/groups').then(function(r){
  var el = $('qqGroupList'); if(!el) return;
  var list = (r && r.data) || [];
  if(!list.length){ el.innerHTML = '<div style="color:var(--muted);padding:16px">暂无群记录。让机器人进群并 @它一次，群会自动出现在这里。</div>'; return; }
  var rows = list.map(function(g){
   return '<tr>'+
    '<td><code style="font-size:11px">'+esc(g.groupOpenid)+'</code></td>'+
    '<td><span class="badge '+(g.aiEnabled?'green':'gray')+'">'+(g.aiEnabled?'AI开':'AI关')+'</span></td>'+
    '<td style="max-width:220px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap">'+esc(g.greeting||'-')+'</td>'+
    '<td><button class="btn-ghost btn-sm" onclick="qqGroupEdit('+JSON.stringify(g).replace(/"/g,'&quot;')+')">配置</button></td>'+
   '</tr>';
  }).join('');
  el.innerHTML = '<div class="table-wrap"><table><thead><tr>'+
   '<th>群 openid</th><th>AI</th><th>欢迎语</th><th>操作</th>'+
   '</tr></thead><tbody>'+rows+'</tbody></table></div>';
 });
}

function qqOpenForm(b){
 b = b || {};
 var html =
  '<div class="form-row"><label>机器人名称（备注）</label><input class="input" id="qqName" value="'+esc(b.name||'')+'" placeholder="例如：客服小号1"></div>'+
  '<div class="form-row"><label>AppID（在 QQ 开放平台机器人详情页获取）</label><input class="input" id="qqAppid" value="'+esc(b.appid||'')+'" '+(b.appid?'readonly':'')+'></div>'+
  '<div class="form-row"><label>Token（机器人 Token）</label><input class="input" id="qqToken" value="'+esc(b.token||'')+'" placeholder="'+(b.token?'保存时留空则不修改':'')+'"></div>'+
  '<div class="form-row"><label>使用模型（留空用 qtai-sj 自动选最快）</label><input class="input" id="qqModel" value="'+esc(b.aiModel||'')+'" placeholder="qtai-sj"></div>'+
  '<div class="form-row"><label>系统人设 / System Prompt（可选）</label><textarea class="input" id="qqPrompt" rows="3" placeholder="例如：你是綦桐AI，语气活泼…">'+esc(b.systemPrompt||'')+'</textarea></div>'+
  '<div class="form-row"><label>入群欢迎语（可选，预留）</label><input class="input" id="qqWelcome" value="'+esc(b.welcome||'')+'"></div>';
 openModal(b.appid?'编辑机器人':'添加机器人', html, function(){
  var appid = $('qqAppid').value.trim();
  var token = $('qqToken').value.trim();
  if(b.appid && !token) token = b.token || '';
  if(!appid || !token){ toast('AppID 和 Token 必填', false); return; }
  api('/api/qq/bots', {method:'POST', body:{
   appid:appid, token:token, name:$('qqName').value.trim(),
   aiModel:$('qqModel').value.trim()||'qtai-sj',
   systemPrompt:$('qqPrompt').value, welcome:$('qqWelcome').value.trim(),
   enabled:true
  }}).then(function(r){
   if(r.code===0){ toast('保存成功', true); closeModal(); qqLoadBots(); }
   else toast(r.msg||'保存失败', false);
  });
 });
}

function qqToggle(id, turnOn){
 api('/api/qq/bots/'+id+'/toggle', {method:'POST'}).then(function(r){
  if(r.code===0){ toast(r.msg, true); qqLoadBots(); } else toast(r.msg, false);
 });
}
function qqRestart(id){
 api('/api/qq/bots/'+id+'/restart', {method:'POST'}).then(function(r){
  toast(r.msg||'已重启', r.code===0);
 });
}
function qqDel(id){
 if(!confirm('确定删除该机器人？会同时断开它的连接。')) return;
 api('/api/qq/bots/'+id, {method:'DELETE'}).then(function(r){
  if(r.code===0){ toast('已删除', true); qqLoadBots(); } else toast(r.msg, false);
 });
}

function qqGroupEdit(g){
 var html =
  '<div class="form-row"><label>群 openid</label><input class="input" value="'+esc(g.groupOpenid)+'" readonly></div>'+
  '<div class="form-row"><label><input type="checkbox" id="ggAi" '+(g.aiEnabled?'checked':'')+'> 启用 AI 回复（群里@机器人时）</label></div>'+
  '<div class="form-row"><label><input type="checkbox" id="ggWelcome" '+(g.welcomeEnabled?'checked':'')+'> 启用入群欢迎</label></div>'+
  '<div class="form-row"><label>欢迎语 / 群备注</label><input class="input" id="ggGreeting" value="'+esc(g.greeting||'')+'"></div>';
 openModal('群配置', html, function(){
  api('/api/qq/groups/update', {method:'POST', body:{
   groupOpenid:g.groupOpenid,
   aiEnabled:$('ggAi').checked, welcomeEnabled:$('ggWelcome').checked,
   greeting:$('ggGreeting').value
  }}).then(function(r){
   if(r.code===0){ toast('已保存', true); closeModal(); qqLoadGroups(); }
   else toast(r.msg, false);
  });
 });
}
""".trimIndent()
}
