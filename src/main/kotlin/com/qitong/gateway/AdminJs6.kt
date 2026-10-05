package com.qitong.gateway

/** 后台 JS 第六部分：技能库 / 工作流 / MCP 管理（爱马仕式 AI 网关） */
object AdminJs6 {
  fun js(): String = """
// ===== 技能库（独立管理：触发器 -> 动作，可执行任何网关技能/终端/HTTP/AI） =====
loaders.skills = function(){
 var box = $('view-skills');
 box.innerHTML = '<div class="action-bar">'+
  '<button class="btn" onclick="skillForm()">+ 新建技能</button>'+
  '<button class="btn-ghost" onclick="skillMarket()">🎯 技能市场</button>'+
  '<button class="btn-ghost" onclick="loaders.skills()">刷新</button>'+
  '<span style="font-size:12px;color:var(--muted)">技能 = 可执行动作（网关技能编码/终端命令/HTTP/AI/回复），可被 QQ 机器人、工作流、qtai-sj 调用</span></div>'+
  '<div id="skillList"><div style="color:var(--muted);padding:20px">加载中…</div></div>';
 skillLoad();
};
// ★ v94 技能市场：内置常用技能包，一键安装到技能库（触发词 -> 动作）
function skillMarket(){
 var pkgs=[
  {name:'查网关状态', trigger:'网关状态', content:'600001', action:'skill', desc:'快捷查网关运行状态（模型/端口/故障转移）'},
  {name:'查余额', trigger:'我的余额', content:'600009', action:'skill', desc:'查询绑定账号余额'},
  {name:'测速排行', trigger:'测速排行', content:'600002', action:'skill', desc:'查看模型测速排行'},
  {name:'活跃模型', trigger:'活跃模型', content:'600003', action:'skill', desc:'查看当前活跃模型'},
  {name:'全部模型', trigger:'模型列表', content:'600008', action:'skill', desc:'查看全部模型与状态'},
  {name:'网关体检', trigger:'体检', content:'sys_health', action:'skill', desc:'全功能一键体检'},
  {name:'待办列表', trigger:'我的待办', content:'todo_list', action:'skill', desc:'查看我的待办'},
  {name:'更新历史', trigger:'更新历史', content:'update_logs', action:'skill', desc:'查看最近更新了啥'},
  {name:'缓存统计', trigger:'缓存统计', content:'cache_stats', action:'skill', desc:'查看响应缓存命中/清理'},
  {name:'已学技能', trigger:'技能列表', content:'skill_list', action:'skill', desc:'查看已学习技能'}
 ];
 var rows=pkgs.map(function(p){
  return '<tr><td><b>'+p.name+'</b></td><td><code>'+p.trigger+'</code></td><td style="font-size:11px;color:var(--muted)">'+p.desc+'</td>'+
   '<td><button class="btn-ghost btn-sm" onclick="skillInstall(\''+p.name+'\',\''+p.trigger+'\',\''+p.content+'\',\''+p.action+'\')">一键安装</button></td></tr>';
 }).join('');
 openModal('🎯 技能市场（内置常用技能，一键安装）',
  '<div class="table-wrap"><table><thead><tr><th>技能</th><th>触发词</th><th>说明</th><th>操作</th></tr></thead><tbody>'+rows+'</tbody></table>'+
  '<small style="color:var(--muted)">安装后立即生效：QQ/微信发触发词即执行；也可在技能库编辑调整</small>', null);
}
function skillInstall(name, trigger, content, action){
 api('/api/skills',{method:'POST',body:{id:null, name:name, trigger:trigger, matchType:'exact', action:action, content:content, enabled:true}}).then(function(r){
  toast(r.msg||'已安装', r.code===0);
 });
}
function skillLoad(){
 api('/api/skills').then(function(r){
  var el=$('skillList'); if(!el) return;
  var list=(r&&r.data)||[];
  if(!list.length){ el.innerHTML='<div class="card" style="color:var(--muted);text-align:center;padding:30px">暂无技能，点「+ 新建技能」创建第一个（如：触发器"查状态"→ 技能600001）</div>'; return; }
  var rows=list.map(function(s){
   var actionTxt = {reply:'💬回复',skill:'⚙️技能',terminal:'🖥终端',http:'🌐HTTP',ai:'🤖AI'}[s.action]||s.action;
   return '<tr>'+
    '<td><b>'+esc(s.name)+'</b></td>'+
    '<td><code>'+esc(s.trigger||'-')+'</code></td>'+
    '<td><span class="badge blue">'+actionTxt+'</span> <code style="font-size:10px">'+esc((s.content||'').substring(0,30))+'</code></td>'+
    '<td>'+(s.enabled?'<span class="badge green">启用</span>':'<span class="badge gray">停用</span>')+'</td>'+
    '<td style="white-space:nowrap">'+
     '<button class="btn-ghost btn-sm" onclick="skillRun('+s.id+')">执行</button> '+
     '<button class="btn-ghost btn-sm" onclick="skillForm('+s.id+')">编辑</button> '+
     '<button class="btn-ghost btn-sm" onclick="skillToggle('+s.id+','+(s.enabled?'false':'true')+')">'+(s.enabled?'停用':'启用')+'</button> '+
     '<button class="btn-ghost btn-sm danger" onclick="skillDel('+s.id+')">删</button></td></tr>';
  }).join('');
  el.innerHTML='<div class="card" style="box-shadow:none"><div class="table-wrap"><table><thead><tr><th>名称</th><th>触发器</th><th>动作</th><th>状态</th><th>操作</th></tr></thead><tbody>'+rows+'</tbody></table></div></div>';
 });
}
function skillForm(id){
 if(!id){ // 新建
  openSkillModal({});
  return;
 }
 api('/api/skills').then(function(r){
  var list=(r&&r.data)||[];
  var s=list.filter(function(x){return x.id===id;})[0];
  if(!s){ toast('技能不存在',false); return; }
  openSkillModal(s);
 });
}
function openSkillModal(s){
 s=s||{};
 var html='<div class="form-row"><label>技能名称</label><input class="input" id="skName" value="'+esc(s.name||'')+'" placeholder="如：查状态"></div>'+
  '<div class="form-row"><label>触发器（QQ机器人发这个词触发）</label><input class="input" id="skTrigger" value="'+esc(s.trigger||'')+'" placeholder="如：网关状态"></div>'+
  '<div class="form-row"><label>动作类型</label><select class="input" id="skAction"><option value="skill"'+(s.action==='skill'?' selected':'')+'>⚙️ 网关技能（填技能编码）</option><option value="terminal"'+(s.action==='terminal'?' selected':'')+'>🖥 终端命令</option><option value="http"'+(s.action==='http'?' selected':'')+'>🌐 HTTP 请求</option><option value="ai"'+(s.action==='ai'?' selected':'')+'>🤖 AI 大模型</option><option value="reply"'+(s.action==='reply'?' selected':'')+'>💬 固定回复</option></select></div>'+
  '<div class="form-row"><label>内容（技能编码/命令/URL/提示词/回复文本）</label><textarea class="input" id="skContent" rows="3">'+esc(s.content||'')+'</textarea></div>'+
  '<div class="form-row"><label><input type="checkbox" id="skEnabled"'+(s.enabled===false?'':' checked')+'> 启用</label></div>';
 openModal(s.id?'编辑技能':'新建技能', html, function(){
  api('/api/skills',{method:'POST',body:{
   id:s.id||null, name:$('skName').value.trim(), trigger:$('skTrigger').value.trim(),
   matchType:'exact', action:$('skAction').value, content:$('skContent').value, enabled:$('skEnabled').checked
  }}).then(function(r){
   if(r.code===0){ toast(r.msg,true); closeModal(); loaders.skills(); } else toast(r.msg,false);
  });
 });
}
function skillRun(id){
 api('/api/skills/run',{method:'POST',body:{id:id}}).then(function(r){
  if(r.code===0) openModal('技能执行结果', '<div style="font-family:monospace;background:#0b1120;color:#e2e8f0;border-radius:6px;padding:10px;font-size:12px;max-height:300px;overflow-y:auto;white-space:pre-wrap">'+esc((r.data&&r.data.result)||'(空)')+'</div>');
  else toast(r.msg||'执行失败',false);
 });
}
function skillDel(id){
 if(!confirm('删除该技能？')) return;
 api('/api/skills/'+id,{method:'DELETE'}).then(function(r){ toast(r.msg,r.code===0); loaders.skills(); });
}
// ★ v48 技能启用/停用切换
function skillToggle(id,enabled){
 api('/api/skills',{method:'POST',body:{id:id, enabled:enabled}}).then(function(r){
  toast(r.msg||'已更新', r.code===0); if(r.code===0) loaders.skills();
 });
}

// ===== 工作流（可做任何事的自动化：多步骤序列，qtai-sj 可创建/修改/执行） =====
loaders.workflows = function(){
 var box = $('view-workflows');
 box.innerHTML = '<div class="action-bar">'+
  '<button class="btn" onclick="wfForm()">+ 新建工作流</button>'+
  '<button class="btn-ghost" onclick="wfTemplates()">📦 模板市场</button>'+
  '<button class="btn-ghost" onclick="loaders.workflows()">刷新</button>'+
  '<span style="font-size:12px;color:var(--muted)">工作流 = 按顺序执行的自动化任务（可组合技能/终端/HTTP/AI），手动或触发词运行</span></div>'+
  '<div id="wfList"><div style="color:var(--muted);padding:20px">加载中…</div></div>';
 wfLoad();
};
// ★ v95 工作流模板市场：内置常用自动化模板，一键创建
function wfTemplates(){
 var tmpls=[
  {name:'每日网关体检', desc:'自动查状态+体检+更新历史，一条命令看全', triggerType:'manual', triggerText:'', steps:[
   {type:'skill', content:'600001'},{type:'skill', content:'sys_health'},{type:'skill', content:'update_logs'}
  ]},
  {name:'余额+用量报告', desc:'查余额+流量+token，财务日报', triggerType:'trigger', triggerText:'我的报告', steps:[
   {type:'skill', content:'600009'},{type:'skill', content:'600004'},{type:'skill', content:'600005'}
  ]},
  {name:'模型速查', desc:'测速排行+全部模型+活跃模型，模型状态一览', triggerType:'trigger', triggerText:'模型速查', steps:[
   {type:'skill', content:'600002'},{type:'skill', content:'600008'},{type:'skill', content:'600003'}
  ]}
 ];
 var rows=tmpls.map(function(t,i){
  var stepTxt=t.steps.map(function(s){return s.type+':'+s.content;}).join(' → ');
  return '<tr><td><b>'+t.name+'</b></td><td style="font-size:11px;color:var(--muted)">'+t.desc+'</td><td><code style="font-size:10px">'+stepTxt+'</code></td>'+
   '<td><button class="btn-ghost btn-sm" onclick="wfInstall('+i+')">一键创建</button></td></tr>';
 }).join('');
 window._wfTmpls=tmpls;
 openModal('📦 工作流模板（一键创建）',
  '<div class="table-wrap"><table><thead><tr><th>模板</th><th>说明</th><th>步骤</th><th>操作</th></tr></thead><tbody>'+rows+'</tbody></table>'+
  '<small style="color:var(--muted)">创建后可在工作流列表编辑/执行，也可在 QQ/微信发触发词运行</small>', null);
}
function wfInstall(idx){
 var t=window._wfTmpls[idx]; if(!t) return;
 api('/api/workflows',{method:'POST',body:{
  id:null, name:t.name, description:t.desc, triggerType:t.triggerType, triggerText:t.triggerText,
  steps:JSON.stringify(t.steps), enabled:true
 }}).then(function(r){
  if(r.code===0){ toast('工作流「'+t.name+'」已创建', true); closeModal(); loaders.workflows(); } else toast(r.msg,false);
 });
}
function wfLoad(){
 api('/api/workflows').then(function(r){
  var el=$('wfList'); if(!el) return;
  var list=(r&&r.data)||[];
  if(!list.length){ el.innerHTML='<div class="card" style="color:var(--muted);text-align:center;padding:30px">暂无工作流，点「+ 新建工作流」创建第一个自动化任务</div>'; return; }
  var rows=list.map(function(w){
   var steps=[]; try{ steps=JSON.parse(w.steps||'[]'); }catch(e){}
   return '<tr>'+
    '<td><b>'+esc(w.name)+'</b><br><small style="color:var(--muted)">'+esc((w.description||'').substring(0,40))+'</small></td>'+
    '<td><span class="badge blue">'+esc(w.triggerType||'manual')+'</span>'+(w.triggerText?' <code>'+esc(w.triggerText)+'</code>':'')+'</td>'+
    '<td>'+(steps.length||0)+' 步</td>'+
    '<td>'+(w.enabled?'<span class="badge green">启用</span>':'<span class="badge gray">停用</span>')+'</td>'+
    '<td style="white-space:nowrap">'+
     '<button class="btn-ghost btn-sm" onclick="wfRun('+w.id+')">运行</button> '+
     '<button class="btn-ghost btn-sm" onclick="wfForm('+w.id+')">编辑</button> '+
     '<button class="btn-ghost btn-sm danger" onclick="wfDel('+w.id+')">删</button></td></tr>';
  }).join('');
  el.innerHTML='<div class="card" style="box-shadow:none"><div class="table-wrap"><table><thead><tr><th>名称</th><th>触发</th><th>步骤</th><th>状态</th><th>操作</th></tr></thead><tbody>'+rows+'</tbody></table></div></div>';
 });
}
function wfForm(id){
 if(!id){ openWfModal({}); return; }
 api('/api/workflows').then(function(r){
  var list=(r&&r.data)||[];
  var w=list.filter(function(x){return x.id===id;})[0];
  if(!w){ toast('工作流不存在',false); return; }
  openWfModal(w);
 });
}
function openWfModal(w){
 w=w||{};
 var steps=[]; try{ steps=JSON.parse(w.steps||'[]'); }catch(e){}
 var stepHtml = steps.map(function(st,i){
  return '<div style="display:flex;gap:6px;margin-bottom:6px;align-items:center">'+
   '<span class="badge">'+(i+1)+'</span>'+
   '<select class="input" style="width:130px;padding:5px" onchange="wfStepType(this,'+i+')">'+
    '<option value="reply"'+(st.type==='reply'?' selected':'')+'>💬回复</option>'+
    '<option value="skill"'+(st.type==='skill'?' selected':'')+'>⚙️技能</option>'+
    '<option value="terminal"'+(st.type==='terminal'?' selected':'')+'>🖥终端</option>'+
    '<option value="http"'+(st.type==='http'?' selected':'')+'>🌐HTTP</option>'+
    '<option value="ai"'+(st.type==='ai'?' selected':'')+'>🤖AI</option></select>'+
   '<input class="input" style="flex:1;padding:5px" value="'+esc(st.content||'')+'" placeholder="内容（回复文本/技能编码/命令/URL/提示词）">'+
   '<button class="btn-ghost btn-sm danger" onclick="this.parentNode.remove()">×</button></div>';
 }).join('');
 var html = '<div class="form-row"><label>工作流名称</label><input class="input" id="wfName" value="'+esc(w.name||'')+'" placeholder="如：早间巡检"></div>'+
  '<div class="form-row"><label>描述</label><input class="input" id="wfDesc" value="'+esc(w.description||'')+'" placeholder="这个工作流做什么"></div>'+
  '<div class="form-row"><label>触发类型</label><select class="input" id="wfTriggerType"><option value="manual"'+(w.triggerType!=='keyword'?' selected':'')+'>手动运行</option><option value="keyword"'+(w.triggerType==='keyword'?' selected':'')+'>关键词触发（QQ）</option></select></div>'+
  '<div class="form-row" id="wfTrigRow" style="display:'+(w.triggerType==='keyword'?'':'none')+'"><label>触发词（QQ机器人发这个词运行）</label><input class="input" id="wfTriggerText" value="'+esc(w.triggerText||'')+'" placeholder="如：早间巡检"></div>'+
  '<div class="form-row"><label>执行步骤（按顺序）</label><div id="wfSteps">'+stepHtml+'</div>'+
  '<button class="btn-ghost btn-sm" onclick="wfAddStep()">+ 添加步骤</button></div>'+
  '<div class="form-row"><label><input type="checkbox" id="wfEnabled"'+(w.enabled===false?'':' checked')+'> 启用</label></div>';
 openModal(w.id?'编辑工作流':'新建工作流', html, function(){
  var steps=[];
  document.querySelectorAll('#wfSteps > div').forEach(function(d){
   var sel=d.querySelector('select'); var inp=d.querySelector('input');
   if(inp && inp.value) steps.push({type:sel?sel.value:'reply', content:inp.value});
  });
  api('/api/workflows',{method:'POST',body:{
   id:w.id||null, name:$('wfName').value.trim(), description:$('wfDesc').value,
   triggerType:$('wfTriggerType').value, triggerText:$('wfTriggerText').value.trim(),
   steps:JSON.stringify(steps), enabled:$('wfEnabled').checked
  }}).then(function(r){
   if(r.code===0){ toast(r.msg,true); closeModal(); loaders.workflows(); } else toast(r.msg,false);
  });
 });
}
function wfAddStep(){
 var d=document.createElement('div');
 d.style.cssText='display:flex;gap:6px;margin-bottom:6px;align-items:center';
 d.innerHTML='<span class="badge">新</span>'+
  '<select class="input" style="width:130px;padding:5px"><option value="reply">💬回复</option><option value="skill">⚙️技能</option><option value="terminal">🖥终端</option><option value="http">🌐HTTP</option><option value="ai">🤖AI</option></select>'+
  '<input class="input" style="flex:1;padding:5px" placeholder="内容（回复文本/技能编码/命令/URL/提示词）">'+
  '<button class="btn-ghost btn-sm danger" onclick="this.parentNode.remove()">×</button>';
 document.getElementById('wfSteps').appendChild(d);
}
function wfStepType(sel,i){ /* 保持简单 */ }
function wfRun(id){
 api('/api/workflows/run',{method:'POST',body:{id:id}}).then(function(r){
  if(r.code===0){
   var results=(r.data&&r.data.results)||[];
   var html=results.map(function(x,i){ return '<div style="margin-bottom:8px"><div class="badge">'+(i+1)+' · '+esc(x.type)+'</div><pre style="background:#0b1120;color:#e2e8f0;border-radius:6px;padding:8px;font-size:11px;overflow-x:auto;white-space:pre-wrap">'+esc(x.output)+'</pre></div>'; }).join('');
   openModal('工作流执行结果（'+results.length+'步）', html);
  } else toast(r.msg||'执行失败',false);
 });
}
function wfDel(id){
 if(!confirm('删除该工作流？')) return;
 api('/api/workflows/'+id,{method:'DELETE'}).then(function(r){ toast(r.msg,r.code===0); loaders.workflows(); });
}

// ===== MCP 服务器管理（独立界面：对接外部 MCP，编辑/删除/启停/测试） =====
loaders.mcp = function(){
 var box = $('view-mcp');
 box.innerHTML = '<div class="action-bar">'+
  '<button class="btn" onclick="mcpForm()">+ 对接 MCP 服务器</button>'+
  '<button class="btn-ghost" onclick="loaders.mcp()">刷新</button>'+
  '<span style="font-size:12px;color:var(--muted)">对接外部 MCP（Model Context Protocol）服务器，扩展网关能力</span></div>'+
  '<div id="mcpList"><div style="color:var(--muted);padding:20px">加载中…</div></div>';
 mcpLoad();
};
function mcpLoad(){
 api('/api/mcp').then(function(r){
  var el=$('mcpList'); if(!el) return;
  var list=(r&&r.data)||[];
  if(!list.length){ el.innerHTML='<div class="card" style="color:var(--muted);text-align:center;padding:30px">暂无 MCP 服务器，点「+ 对接 MCP 服务器」接入第一个</div>'; return; }
  var rows=list.map(function(m){
   return '<tr>'+
    '<td><b>'+esc(m.name)+'</b></td>'+
    '<td><span class="badge blue">'+esc(m.serverType||'http')+'</span></td>'+
    '<td><code style="font-size:11px">'+esc(m.url)+'</code></td>'+
    '<td>'+(m.enabled?'<span class="badge green">启用</span>':'<span class="badge gray">停用</span>')+' <span id="mcpState'+m.id+'" class="badge gray">未握手</span></td>'+
    '<td style="white-space:nowrap">'+
     '<button class="btn-ghost btn-sm" onclick="mcpTools('+m.id+')">🔍工具</button> '+
     '<button class="btn-ghost btn-sm" onclick="mcpTest('+m.id+')">测试</button> '+
     '<button class="btn-ghost btn-sm" onclick="mcpForm('+m.id+')">编辑</button> '+
     '<button class="btn-ghost btn-sm danger" onclick="mcpDel('+m.id+')">删</button></td></tr>'+
    '<tr id="mcpToolsRow'+m.id+'" style="display:none"><td colspan="5"><div style="padding:10px 14px;font-size:12px;white-space:pre-wrap;color:var(--muted);background:rgba(255,255,255,.03);border-radius:6px">加载中…</div></td></tr>';
  }).join('');
  el.innerHTML='<div class="card" style="box-shadow:none"><div class="table-wrap"><table><thead><tr><th>名称</th><th>类型</th><th>地址</th><th>状态</th><th>操作</th></tr></thead><tbody>'+rows+'</tbody></table></div></div>';
 });
}
// ★ v79 展开 MCP 工具列表：握手后显示可调用工具（含入参 schema / 返回结构 / 连接状态，对齐 Kai）
function mcpTools(id){
 var row=$('mcpToolsRow'+id);
 if(!row) return;
 if(row.style.display!=='none'){ row.style.display='none'; return; }
 row.style.display='';
 row.innerHTML='<div style="padding:10px 14px;font-size:12px;color:var(--muted)">加载中…</div>';
 api('/api/mcp/'+id+'/tools').then(function(r){
  var data=(r&&r.data)||{};
  if(data.ok===false){
   mcpSetState(id, false, data.error);
   row.innerHTML='<div style="padding:10px 14px;font-size:12px;color:var(--red)">❌ 连接失败：'+esc(data.error||'未知错误')+'</div>';
   return;
  }
  mcpSetState(id, true, '');
  var tools=[];
  try{ tools=JSON.parse(data.tools||'[]')||[]; }catch(e){ tools=[]; }
  if(!tools.length){
   row.innerHTML='<div style="padding:10px 14px;font-size:12px;color:var(--muted)">📋 已连接（无工具）</div>';
   return;
  }
  // 渲染每个工具：名称 / 描述 / 入参 schema（required 标红）/ 返回结构
  var cards=tools.map(function(t){
   var name=esc(t.name||'?');
   var desc=esc(t.description||'');
   // 入参 schema
   var inSchema=t.inputSchema||{};
   var props=inSchema.properties||{};
   var reqArr=inSchema.required||[];
   var propKeys=Object.keys(props);
   var paramsHtml=propKeys.length?'<div style="margin-top:6px"><div style="font-size:11px;color:var(--cyan)">入参：</div>'+
    propKeys.map(function(k){
     var p=props[k]||{};
     var req=reqArr.indexOf(k)>=0;
     return '<div style="font-size:11px;color:var(--muted);margin-left:10px">· <code>'+esc(k)+'</code>'+(req?' <span style="color:var(--red)">必填</span>':'')+' — '+esc(p.type||'')+esc(p.description?(' '+p.description):'')+'</div>';
    }).join('')+'</div>':'';
   // 返回结构（简化为 JSON 预览）
   var outSchema=t.outputSchema||{};
   var outHtml=Object.keys(outSchema).length?'<div style="margin-top:4px;font-size:11px;color:var(--muted)">返回：<code>'+esc(JSON.stringify(outSchema).slice(0,120))+'</code></div>':'';
   return '<div style="padding:8px 10px;border:1px solid var(--border);border-radius:8px;margin-bottom:6px;background:rgba(255,255,255,.03)">'+
    '<div style="font-weight:600;color:var(--text);font-family:monospace">🔧 '+name+'</div>'+
    (desc?'<div style="font-size:11px;color:var(--muted);margin-top:2px">'+desc+'</div>':'')+
    paramsHtml+outHtml+'</div>';
  }).join('');
  row.innerHTML='<div style="padding:10px 14px;font-size:12px"><div style="margin-bottom:8px;color:var(--green)">✅ 已连接，共 '+tools.length+' 个工具</div>'+cards+'</div>';
 });
}
function mcpForm(id){
 if(!id){ openMcpModal({}); return; }
 api('/api/mcp').then(function(r){
  var list=(r&&r.data)||[];
  var m=list.filter(function(x){return x.id===id;})[0];
  if(!m){ toast('MCP不存在',false); return; }
  openMcpModal(m);
 });
}
function openMcpModal(m){
 m=m||{};
 var html='<div class="form-row"><label>服务器名称</label><input class="input" id="mcpName" value="'+esc(m.name||'')+'" placeholder="如：数据库 MCP"></div>'+
  '<div class="form-row"><label>类型</label><select class="input" id="mcpType"><option value="http"'+(m.serverType!=='sse'?' selected':'')+'>HTTP</option><option value="sse"'+(m.serverType==='sse'?' selected':'')+'>SSE 流式</option></select></div>'+
  '<div class="form-row"><label>服务器地址（URL）</label><input class="input" id="mcpUrl" value="'+esc(m.url||'')+'" placeholder="https://mcp.example.com"></div>'+
  '<div class="form-row"><label>认证 Token（可选）</label><input class="input" id="mcpToken" type="password" value="'+esc(m.authToken||'')+'"></div>'+
  '<div class="form-row"><label><input type="checkbox" id="mcpEnabled"'+(m.enabled===false?'':' checked')+'> 启用</label></div>';
 openModal(m.id?'编辑 MCP':'对接 MCP 服务器', html, function(){
  api('/api/mcp',{method:'POST',body:{
   id:m.id||null, name:$('mcpName').value.trim(), serverType:$('mcpType').value,
   url:$('mcpUrl').value.trim(), authToken:$('mcpToken').value, enabled:$('mcpEnabled').checked
  }}).then(function(r){
   if(r.code===0){ toast(r.msg,true); closeModal(); loaders.mcp(); } else toast(r.msg,false);
  });
 });
}
function mcpTest(id){
 api('/api/mcp').then(function(r){
  var list=(r&&r.data)||[]; var m=list.filter(function(x){return x.id===id;})[0];
  if(!m){ toast('未找到',false); return; }
  api('/api/mcp/test',{method:'POST',body:{url:m.url}}).then(function(rr){
   var st=$('mcpState'+id); if(st){ st.className='badge '+(rr.code===0?'green':'red'); st.textContent=rr.code===0?'已连接':'连接失败'; }
   toast(rr.msg||'', rr.code===0);
  });
 });
}
// ★ v79 工具展开成功后同步状态标识
function mcpSetState(id, ok, err){
 var st=$('mcpState'+id); if(!st) return;
 st.className='badge '+(ok?'green':'red');
 st.textContent=ok?'已连接':('失败'+(err?'：'+err:''));
}
function mcpDel(id){
 if(!confirm('删除该 MCP 服务器？')) return;
 api('/api/mcp/'+id,{method:'DELETE'}).then(function(r){ toast(r.msg,r.code===0); loaders.mcp(); });
}
""".trimIndent()
}