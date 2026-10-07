package com.qitong.gateway

/** 后台 JS 第三部分：密钥 + 规则 + 用量 + 设置 + 用户 */
object AdminJs3 {
  fun js(): String = """
// ===== API密钥 =====
loaders.keys = function(){
 var box = $('view-keys');
 box.innerHTML = '<div style="text-align:center;color:var(--muted);padding:40px">加载中...</div>';
 api('/api/keys').then(function(r){
  state.keys = r.data || [];
  var rows = state.keys.map(function(k){
   var mt = (k.allowedModels && k.allowedModels.length) ? k.allowedModels.map(function(m){ return '<span class="badge blue">'+esc(m)+'</span>'; }).join(' ') : '<span style="color:var(--muted)">全部</span>';
   var keyJs = esc(k.key).replace(/'/g, '&#39;');
   return '<tr><td style="font-family:monospace;font-size:12px">'+esc(k.key)+'</td><td>'+esc(k.label)+'</td><td><span class="badge '+(k.enabled?'green':'red')+'">'+(k.enabled?'启用':'停用')+'</span></td><td>'+mt+'</td><td><span class="badge '+(k.qtaiSjAccess?'purple':'gray')+'">'+(k.qtaiSjAccess?'允许':'禁止')+'</span></td><td><span class="badge blue">'+esc(k.ownerName||'')+'</span></td><td><button class="btn-ghost" onclick="copyKey(\''+keyJs+'\')">复制</button> <button class="btn-ghost" onclick="editKey(\''+keyJs+'\')">编辑</button> <button class="btn-ghost" style="color:var(--red)" onclick="delKey(\''+keyJs+'\')">删除</button></td></tr>';
  }).join('');
  box.innerHTML = [
   '<div class="action-bar"><button class="btn" onclick="addKey()">添加密钥</button></div>',
   '<div class="card"><div class="table-wrap"><table><thead><tr><th>密钥</th><th>标签</th><th>状态</th><th>可用模型</th><th>qtai-sj</th><th>属主</th><th>操作</th></tr></thead><tbody>' +
   rows + '<tr><td colspan="7" style="text-align:center;color:var(--muted)">' + (state.keys.length ? '' : '暂无密钥') + '</td></tr>' +
   '</tbody></table></div></div>'
  ].join('');
 });
};
// 复制密钥
window.copyKey = function(k){
 var txt = k.replace(/&#39;/g, "'");
 copyText(txt, '已复制密钥');
};
window.addKey = function(){
 // ★ v1.105 动态拉取当前用户可见模型（用户隔离：管理员=全部，普通用户=公用+自己的+被授权的），不再用全局缓存
 api('/api/models').then(function(mr){
  var mlist = (mr && mr.data) || [];
  var modelOpts = mlist.map(function(m){ return '<option value="'+esc(m.modelId)+'">'+esc((m.providerLabel? m.providerLabel+' · ':'')+m.displayName)+'</option>'; }).join('');
  var html = [
   '<div class="form-row"><label>密钥（留空自动生成）</label><input id="kKey" class="input"></div>',
   '<div class="form-row"><label>标签</label><input id="kLabel" class="input"></div>',
   '<div class="form-row"><label>允许的模型（Ctrl多选，留空=全部，仅当前用户可见模型）</label><select id="kModels" class="input" multiple size="4">'+modelOpts+'</select></div>',
   '<div class="form-row"><label><input type="checkbox" id="kQtai" checked>允许访问 qtai-sj</label></div>'
  ].join('');
  openModal('添加密钥', html, function(){
   var key = $('kKey').value.trim() || 'sk-qt-' + Math.random().toString(36).slice(2,12);
   var models = Array.from($('kModels').selectedOptions).map(function(o){ return o.value; });
   api('/api/keys', { method:'POST', body: { key: key, label: $('kLabel').value.trim(), allowedModels: models, qtaiSjAccess: $('kQtai').checked } }).then(function(r){
    if(r.code === 0){ toast('密钥: '+key, true); closeModal(); loaders.keys(); } else toast(r.msg, false);
   });
  });
 });
};
window.delKey = function(k){
 if(!confirm('删除密钥 '+k+' ?')) return;
 api('/api/keys/' + encodeURIComponent(k), { method:'DELETE' }).then(function(r){
  if(r.code === 0){ toast('已删除', true); loaders.keys(); } else toast(r.msg, false);
 });
};
// ===== 密钥编辑 =====
window.editKey = function(k){
 var entry = state.keys.find(function(x){ return x.key === k; });
 if(!entry){ toast('密钥不存在', false); return; }
 var modelOpts = '';
 api('/api/models').then(function(mr){
  (mr.data || []).forEach(function(m){ modelOpts += '<option value="'+esc(m.modelId)+'"'+(entry.allowedModels.indexOf(m.modelId)>=0?' selected':'')+'>'+esc(m.modelId)+'</option>'; });
  var html = [
   '<div class="form-row"><label>密钥</label><input class="input" value="'+esc(k)+'" disabled></div>',
   '<div class="form-row"><label>标签</label><input id="ekLabel" class="input" value="'+esc(entry.label||'')+'"></div>',
   '<div class="form-row"><label>状态</label><select id="ekEnabled" class="input"><option value="true"'+(entry.enabled?' selected':'')+'>启用</option><option value="false"'+(entry.enabled?'':' selected')+'>停用</option></select></div>',
   '<div class="form-row"><label>允许的模型（Ctrl多选，留空=全部）</label><select id="ekModels" class="input" multiple size="5">'+modelOpts+'</select></div>',
   '<div class="form-row"><label>qtai-sj 访问</label><select id="ekQtai" class="input"><option value="true"'+(entry.qtaiSjAccess?' selected':'')+'>允许</option><option value="false"'+(entry.qtaiSjAccess?'':' selected')+'>禁止</option></select></div>'
  ].join('');
  openModal('编辑密钥', html, function(){
   var models = Array.from($('ekModels').selectedOptions).map(function(o){ return o.value; });
   var body = { key: k, label: $('ekLabel').value.trim(), enabled: $('ekEnabled').value === 'true', allowedModels: models, qtaiSjAccess: $('ekQtai').value === 'true' };
   api('/api/keys/update', { method:'POST', body: body }).then(function(r){
    if(r.code === 0){ toast(r.msg, true); closeModal(); loaders.keys(); } else toast(r.msg, false);
   });
  });
 });
};
// ===== 路由规则 =====
loaders.rules = function(){
 var box = $('view-rules');
 box.innerHTML = '<div style="text-align:center;color:var(--muted);padding:40px">加载中...</div>';
 api('/api/rules').then(function(r){
  state.rules = r.data || [];
  var rows = state.rules.map(function(rule){
   return '<tr><td>'+esc(rule.name)+' <span class="badge '+(rule.enabled?'green':'red')+'">'+(rule.enabled?'启用':'停用')+'</span></td><td style="font-size:12px">'+esc(rule.pathPattern||'*')+'</td><td style="font-size:12px">'+esc(rule.modelPattern||'*')+'</td><td style="font-size:12px">'+esc(rule.apiKeyPattern||'*')+'</td><td><span class="badge '+(rule.action==='block'?'red':'blue')+'">'+(rule.action==='block'?'拒绝':'转发')+'</span></td><td><button class="btn-ghost" style="color:var(--red)" onclick="delRule('+rule.id+')">删除</button></td></tr>';
  }).join('');
  box.innerHTML = [
   '<div class="action-bar"><button class="btn" onclick="addRule()">添加规则</button></div>',
   '<div class="card"><div class="table-wrap"><table><thead><tr><th>名称</th><th>路径</th><th>模型</th><th>密钥</th><th>动作</th><th>操作</th></tr></thead><tbody>' +
   rows + '<tr><td colspan="6" style="text-align:center;color:var(--muted)">' + (state.rules.length ? '' : '暂无路由规则') + '</td></tr>' +
   '</tbody></table></div></div>'
  ].join('');
 });
};
window.addRule = function(){
 var html = [
  '<div class="form-row"><label>规则名称</label><input id="rName" class="input" value="新规则"></div>',
  '<div class="form-row"><label>路径匹配（*通配）</label><input id="rPath" class="input" value="*"></div>',
  '<div class="form-row"><label>模型匹配（*通配）</label><input id="rModel" class="input" value="*"></div>',
  '<div class="form-row"><label>API密钥匹配（*通配）</label><input id="rKey" class="input" value="*"></div>',
  '<div class="form-row"><label>动作</label><select id="rAction" class="input"><option value="route">转发</option><option value="block">拒绝</option></select></div>',
  '<div class="form-row"><label>优先级</label><input id="rPrio" class="input" type="number" value="0"></div>'
 ].join('');
 openModal('添加路由规则', html, function(){
  api('/api/rules', { method:'POST', body: { name: $('rName').value.trim()||'新规则', pathPattern: $('rPath').value.trim(), modelPattern: $('rModel').value.trim(), apiKeyPattern: $('rKey').value.trim(), action: $('rAction').value, priority: parseInt($('rPrio').value)||0, enabled: true } }).then(function(r){
   if(r.code === 0){ toast('已添加', true); closeModal(); loaders.rules(); } else toast(r.msg, false);
  });
 });
};
window.delRule = function(id){
 if(!confirm('删除该规则？')) return;
 api('/api/rules/' + id, { method:'DELETE' }).then(function(r){
  if(r.code === 0){ toast('已删除', true); loaders.rules(); } else toast(r.msg, false);
 });
};
// ===== 用量 =====
function fmtTime(ts){
 if(!ts) return '—';
 var d = new Date(ts);
 return d.toLocaleString('zh-CN', { hour12:false });
}
loaders.usage = function(){
 var box = $('view-usage');
 box.innerHTML = '<div style="text-align:center;color:var(--muted);padding:40px">加载中...</div>';
 api('/api/stats').then(function(r){
  var s = r.data;
  var rows = (s.usage || []).map(function(u,i){
   return '<tr><td>'+(i+1)+'</td><td>'+esc(u.model_key)+'</td><td>'+(u.calls||0)+'</td><td>'+fmtNum(u.total_tokens||0)+'</td><td>'+fmtBytes(u.upload_bytes||0)+'</td><td>'+fmtBytes(u.download_bytes||0)+'</td><td>¥'+(u.cost||0).toFixed(4)+'</td><td><button class="btn btn-danger btn-sm" onclick="delModelUsage(\''+esc(u.model_key).replace(/'/g,'&#39;')+'\')">删除</button></td></tr>';
  }).join('');
  // 传输明细（每次调用一条）
  api('/api/usage/recent').then(function(rr){
   var recent = (rr.data || []);
   var rrows = recent.map(function(u){
    var up = u.uploadBytes || 0, down = u.downloadBytes || 0, tt = u.totalTokens || 0;
    return '<tr><td>'+esc(u.modelKey||u.modelName||'')+'</td><td>'+fmtNum(u.promptTokens||0)+'</td><td>'+fmtNum(u.completionTokens||0)+'</td><td>'+fmtNum(tt)+'</td><td>'+fmtBytes(up)+'</td><td>'+fmtBytes(down)+'</td><td>¥'+(u.cost||0).toFixed(4)+'</td><td>'+esc(u.apiKeyLabel||'本地')+'</td><td style="font-size:12px;color:var(--muted)">'+fmtTime(u.createdAt)+'</td><td><button class="btn btn-danger btn-sm" onclick="delUsageRow('+(u.id||0)+')">删除</button></td></tr>';
   }).join('');
// 按密钥分组（对齐原APP apiKeyUsageRows；★v1.107 可单独删除该密钥全部用量）
   var keyRows = (s.apiKeyUsage || []).map(function(u,i){
    return '<tr><td>'+(i+1)+'</td><td>'+esc(u.api_key_label)+'</td><td>'+(u.calls||0)+'</td><td>'+fmtNum(u.total_tokens||0)+'</td><td>'+fmtBytes(u.upload_bytes||0)+'</td><td>'+fmtBytes(u.download_bytes||0)+'</td><td>¥'+(u.cost||0).toFixed(4)+'</td><td><button class="btn btn-danger btn-sm" onclick="clearKeyUsage(\''+esc(u.api_key_label).replace(/'/g,'&#39;')+'\')">删除</button></td></tr>';
  }).join('');
  // 模型下拉（用于按模型清理，来自汇总）
  var modelOpts = (s.usage || []).map(function(u){ return '<option value="'+esc(u.model_key)+'">'+esc(u.model_key)+'（'+(u.calls||0)+'次）</option>'; }).join('');
   box.innerHTML = [
    '<div class="action-bar" style="margin-top:2px">',
     '<button class="btn btn-danger" onclick="clearUsageAll()">清理全部用量</button>',
     '<span style="display:inline-flex;gap:6px;align-items:center;margin-left:4px"><select id="usageModelSel" class="input" style="width:200px;margin:0;padding:7px 10px">'+(modelOpts||'<option value="">暂无模型数据</option>')+'</select><button class="btn btn-ghost" onclick="clearUsageByModel()">按模型清理</button></span>',
     '<span style="font-size:12px;color:var(--muted);margin-left:auto">仅清理当前登录用户自己的数据</span>',
    '</div>',
    '<div class="grid grid-3">',
     '<div class="stat"><div class="num">'+fmtBytes(s.totalUpload)+'</div><div class="lbl">总上行</div></div>',
     '<div class="stat"><div class="num">'+fmtBytes(s.totalDownload)+'</div><div class="lbl">总下行</div></div>',
     '<div class="stat"><div class="num">'+(s.usage||[]).length+'</div><div class="lbl">模型维度</div></div>',
    '</div>',
    '<div class="card" style="margin-top:14px"><h3>按模型用量汇总 <span style="font-size:12px;color:var(--muted)">可单独删除该模型全部用量</span></h3><div class="table-wrap"><table><thead><tr><th>#</th><th>模型Key</th><th>调用</th><th>Tokens</th><th>上行</th><th>下行</th><th>费用</th><th>操作</th></tr></thead><tbody>' +
    rows + '<tr><td colspan="8" style="text-align:center;color:var(--muted)">' + ((s.usage||[]).length ? '' : '暂无用量') + '</td></tr>' +
    '</tbody></table></div></div>',
    '<div class="card" style="margin-top:14px"><h3>按API密钥用量 <span style="font-size:12px;color:var(--muted)">每个Key的调用汇总，可单独删除该Key全部用量</span></h3><div class="table-wrap"><table><thead><tr><th>#</th><th>密钥Label</th><th>调用</th><th>Tokens</th><th>上行</th><th>下行</th><th>费用</th><th>操作</th></tr></thead><tbody>' +
    keyRows + '<tr><td colspan="8" style="text-align:center;color:var(--muted)">' + ((s.apiKeyUsage||[]).length ? '' : '暂无密钥用量，发起API调用后显示') + '</td></tr>' +
    '</tbody></table></div></div>',
    '<div class="card" style="margin-top:14px"><h3>传输明细（每次调用）<span style="font-size:12px;color:var(--muted)">对齐原APP TokenUsage，每条=一次API传输</span></h3><div class="table-wrap"><table><thead><tr><th>模型</th><th>Prompt</th><th>输出</th><th>总Token</th><th>上行</th><th>下行</th><th>费用</th><th>密钥</th><th>时间</th><th>操作</th></tr></thead><tbody>' +
    rrows + '<tr><td colspan="10" style="text-align:center;color:var(--muted)">' + (recent.length ? '' : '暂无传输记录，发起API调用后显示') + '</td></tr>' +
    '</tbody></table></div></div>'
   ].join('');
  });
 });
};
// ===== 用量清理 / 删除（用户独立管理，管理员=全部） =====
window.clearUsageAll = function(){
 if(!confirm('确定清理全部用量记录？\n普通用户只清自己的，管理员清全部。')) return;
 api('/api/usage/clear', { method:'POST' }).then(function(r){
  if(r.code === 0){ toast('已清理', true); loaders.usage(); } else toast(r.msg, false);
 });
};
window.delUsageRow = function(id){
 if(!id){ toast('记录ID无效', false); return; }
 if(!confirm('确定删除这条用量记录？')) return;
 api('/api/usage/delete', { method:'POST', body: { id: id } }).then(function(r){
  if(r.code === 0){ toast('已删除', true); loaders.usage(); } else toast(r.msg, false);
 });
};
window.clearUsageByModel = function(){
 var sel = $('usageModelSel'); if(!sel || !sel.value){ toast('请选择模型', false); return; }
 if(!confirm('确定清理该模型的全部用量记录？')) return;
 api('/api/usage/clear-model', { method:'POST', body: { modelKey: sel.value } }).then(function(r){
  if(r.code === 0){ toast('已清理 ' + (r.data && r.data.deleted != null ? r.data.deleted : '') + ' 条', true); loaders.usage(); } else toast(r.msg, false);
 });
};
// ★ v1.108 按模型删除全部用量（按模型汇总表行内按钮）
window.delModelUsage = function(modelKey){
 var mk = String(modelKey||'').replace(/&#39;/g, "'");
 if(!mk){ toast('模型Key无效', false); return; }
 if(!confirm('确定删除该模型「'+mk+'」的全部用量统计？')) return;
 api('/api/usage/clear-model', { method:'POST', body: { modelKey: mk } }).then(function(r){
  if(r.code === 0){ toast('已删除 ' + (r.data && r.data.deleted != null ? r.data.deleted : '') + ' 条', true); loaders.usage(); } else toast(r.msg, false);
 });
};
// ★ v1.107 按 API Key 删除全部用量（单独删某个Key的统计）
window.clearKeyUsage = function(label){
 var lbl = String(label||'').replace(/&#39;/g, "'");
 if(!lbl){ toast('密钥标识无效', false); return; }
 if(!confirm('确定删除该密钥「'+lbl+'」的全部用量统计？')) return;
 api('/api/usage/clear-key', { method:'POST', body: { label: lbl } }).then(function(r){
  if(r.code === 0){ toast('已删除 ' + (r.data && r.data.deleted != null ? r.data.deleted : '') + ' 条', true); loaders.usage(); } else toast(r.msg, false);
 });
};
// ===== 记忆独立页（大脑记忆 + 记忆配置） =====
loaders.memory = function(){
 var box = $('view-memory');
 box.innerHTML = '<div class="action-bar"><button class="btn" onclick="addMemory()">+ 添加记忆</button><button class="btn-ghost" onclick="loaders.memory()">刷新</button><button class="btn-ghost" style="color:var(--red)" onclick="clearMemories()">清空全部</button><span style="font-size:12px;color:var(--muted)">🧠 记忆已默认启用（未建人格也生效，qtai-sj 同样有记忆）。记忆会在 AI 对话时自动注入参考。</span></div>' +
  '<div class="card" id="memListCard"><div style="color:var(--muted);padding:20px">加载中...</div></div>' +
  '<div class="card" id="memCfgCard2" style="margin-top:14px"><div style="color:var(--muted);padding:20px">加载配置中...</div></div>';
 // 记忆列表
 api('/api/memory').then(function(mr){
  var mc = $('memListCard'); if(!mc) return;
  var mems = (mr && mr.data) || [];
  if(!mems.length){ mc.innerHTML = '<h3>🧠 大脑记忆</h3><div style="color:var(--muted);padding:12px">暂无记忆。AI 对话会自动保存关键信息到这里。</div>'; return; }
  var rows = mems.map(function(m){
   return '<tr><td><b>'+esc(m.title||'(无标题)')+'</b></td><td style="font-size:12px;max-width:320px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap">'+esc(m.content)+'</td><td><span class="badge '+(m.emotion==='happy'?'green':m.emotion==='sad'?'red':'purple')+'">'+esc(m.emotion)+'</span></td><td>'+m.importance+'</td><td>'+new Date(m.timestamp).toLocaleString('zh-CN',{hour12:false})+'</td><td><button class="btn-ghost" style="color:var(--red)" onclick="delMemory('+m.id+')">删</button></td></tr>';
  }).join('');
  mc.innerHTML = '<h3>🧠 大脑记忆（'+mems.length+'条）</h3><div class="table-wrap"><table><thead><tr><th>标题</th><th>内容</th><th>情感</th><th>重要</th><th>时间</th><th>操作</th></tr></thead><tbody>'+rows+'</tbody></table></div>';
 });
 // 记忆配置
 api('/api/memory/config').then(function(cr){
  var cc = $('memCfgCard2'); if(!cc) return;
  var c = (cr && cr.data) || {};
  cc.innerHTML = '<h3>⚙️ 记忆配置</h3>' +
   '<div class="form-row"><label><input type="checkbox" id="mc2Enabled"'+(c.enabled?' checked':'')+'> 启用记忆系统</label></div>' +
   '<div class="form-row"><label><input type="checkbox" id="mc2Independent"'+(c.modelIndependent?' checked':'')+'> 模型独立记忆</label><small style="color:var(--muted);display:block;margin-top:4px">开启后各模型记忆互相隔离</small></div>' +
   '<div class="form-row"><label>保存模式</label><select class="input" id="mc2Mode"><option value="frequent"'+(c.saveMode==='frequent'?' selected':'')+'>频繁保存</option><option value="normal"'+(c.saveMode==='normal'||!c.saveMode?' selected':'')+'>正常保存</option><option value="occasional"'+(c.saveMode==='occasional'?' selected':'')+'>偶尔保存</option></select></div>' +
   '<div class="form-row"><label>沟通风格</label><input class="input" id="mc2Style" value="'+esc(c.communicationStyle||'自然亲切、像朋友聊天')+'"></div>' +
   '<button class="btn" onclick="saveMemoryCfg2()">保存配置</button>';
 });
};
window.saveMemoryCfg2 = function(){
 api('/api/memory/config', { method:'POST', body: {
  enabled: $('mc2Enabled').checked ? 'true' : 'false',
  saveMode: $('mc2Mode').value,
  modelIndependent: $('mc2Independent').checked ? 'true' : 'false',
  communicationStyle: $('mc2Style').value
 } }).then(function(r){ if(r.code===0){ toast(r.msg, true); } else toast(r.msg, false); });
};
loaders.profile = function(){
 var box = $('view-profile');
 box.innerHTML = '<div style="text-align:center;color:var(--muted);padding:40px">加载中...</div>';
 api('/api/me/profile').then(function(r){
  if(r.code !== 0){ toast(r.msg, false); return; }
  var p = r.data || {};
  var isAdmin = p.role === 'admin';
  box.innerHTML = [
   '<div class="card" style="text-align:center;padding:26px">',
    '<div class="avatar">' + esc((p.displayName || p.username || '?').substring(0,1).toUpperCase()) + '</div>',
    '<h2 style="color:var(--text);font-size:18px">' + esc(p.displayName || p.username) + '</h2>',
    '<div><span class="badge ' + (isAdmin ? 'purple' : 'blue') + '">' + (isAdmin ? '管理员' : '普通用户') + '</span> <span style="color:var(--muted);font-size:12px">@' + esc(p.username) + '</span></div>',
   '</div>',
   '<div class="card"><h3>我的信息</h3>',
    '<div class="form-row"><label>用户名</label><div class="input" style="background:var(--inset)">' + esc(p.username) + '</div></div>',
    '<div class="form-row"><label>昵称</label><input id="pfName" class="input" value="' + esc(p.displayName || '') + '"></div>',
    '<div class="form-row"><label>绑定邮箱（用于提醒通知）</label><input id="pfEmail" class="input" value="' + esc(p.email || '') + '" placeholder="example@qq.com"></div>',
    '<div class="form-row"><label><input type="checkbox" id="pfNotify"' + (p.notifyEnabled ? ' checked' : '') + '>开启邮件提醒</label><small style="color:var(--muted);display:block;margin-top:4px">余额变动 / 工单回复等发送邮件通知</small></div>',
'<button class="btn" onclick="saveProfile()">保存个人资料</button>',
    '</div>',
    '<div class="card"><h3>QQ 绑定码 <span style="font-size:12px;color:var(--muted)">在 QQ 群里发绑定码即可登录绑定，无需私聊</span></h3>' +
     '<div class="form-row"><label>生成一次性绑定码（5分钟内有效）</label><button class="btn" style="width:auto;padding:8px 20px;margin-top:0" onclick="genBindCode()">生成绑定码</button></div>' +
     '<div id="bindCodeBox" style="color:var(--muted);font-size:12px">点击「生成绑定码」→ 得到 6 位码 → 在 QQ 群里发「绑定码 123456」→ 自动绑定到当前账号</div></div>',
   '<div class="grid grid-2">',
    '<div class="card"><h3>账户</h3><div class="form-row"><label>余额</label><div style="font-size:20px;font-weight:700;color:var(--green)">¥' + (p.balance || 0).toFixed(2) + '</div></div><div class="form-row"><label>累计充值</label><div style="font-size:16px;color:var(--cyan)">¥' + (p.totalRecharge || 0).toFixed(2) + '</div></div></div>',
    '<div class="card"><h3>邀请</h3><div class="form-row"><label>我的邀请码</label><div class="addr-line" onclick="copyText(\'' + esc(p.inviteCode || '') + '\')"><b style="font-family:monospace">' + esc(p.inviteCode || '-') + '</b> <span class="copy-tag"></span></div></div><div class="form-row"><label>注册时间</label><div style="color:var(--muted);font-size:13px">' + new Date(p.createdAt).toLocaleString('zh-CN', {hour12:false}) + '</div></div></div>',
   '</div>',
    '<div class="card"><h3>余额账单 <button class="btn-ghost" style="padding:1px 8px;font-size:11px" onclick="loadBalanceLogs()">刷新</button></h3><div id="balLogBox" style="color:var(--muted);font-size:12px">加载中...</div></div>',
    '<div class="card"><h3>模型扣费记录 <button class="btn-ghost" style="padding:1px 8px;font-size:11px" onclick="loadUsageLogs()">刷新</button> <button class="btn-ghost" style="padding:1px 8px;font-size:11px;color:var(--red)" onclick="clearUsageLogs()">清空全部</button></h3><div id="usageLogBox" style="color:var(--muted);font-size:12px">加载中...</div></div>',
    '<div class="card"><h3>限流设置 <span style="font-size:12px;color:var(--muted)">QPS + 每日配额，0=不限</span></h3><div id="rateBox">加载中...</div></div>'
  ].join('');

  // 加载余额账单
  loadBalanceLogs();
  // ★ v77 加载模型扣费记录
  loadUsageLogs();
  // 加载限流配置
  api('/api/me/rate').then(function(rr){
   if(rr.code === 0 && rr.data){
    var rq = $('rateBox'); if(!rq) return;
    rq.innerHTML = '<div style="display:flex;gap:12px;align-items:flex-end;flex-wrap:wrap">' +
     '<div><label>每秒 QPS</label><input id="rateQps" class="input" type="number" min="0" value="' + (rr.data.qps || 60) + '" style="width:100px"></div>' +
     '<div><label>每日配额（次）</label><input id="rateDaily" class="input" type="number" min="0" value="' + (rr.data.daily || 10000) + '" style="width:140px"></div>' +
     '<button class="btn" style="width:auto;padding:8px 20px;margin-top:0" onclick="saveRate()">保存限流</button>' +
     '</div>';
   }
  });
 });
 };
 window.genBindCode = function(){
 api('/api/me/bind-code',{method:'POST'}).then(function(r){
  var el=$('bindCodeBox'); if(!el) return;
  if(r.code===0){
   var code=r.data&&r.data.code;
   el.innerHTML = '<div style="padding:10px;background:var(--inset);border-radius:6px;text-align:center">' +
    '<div style="font-size:11px;color:var(--muted)">你的绑定码（5分钟内有效，仅用一次）</div>' +
    '<div style="font-size:28px;font-weight:800;letter-spacing:6px;color:var(--cyan);font-family:monospace;padding:8px 0">'+esc(code)+'</div>' +
    '<div style="font-size:12px;color:var(--muted)">在 QQ 群里发：<b>绑定码 '+esc(code)+'</b> 即可完成绑定</div>' +
    '<button class="btn" style="width:auto;padding:6px 16px;margin-top:8px" onclick="copyText(\''+esc(code)+'\')">复制</button>' +
    '<button class="btn-ghost" style="margin-top:8px" onclick="genBindCode()">重新生成</button></div>';
   toast(r.msg, true);
  } else toast(r.msg||'生成失败', false);
 });
};
window.loadBalanceLogs = function(){
  api('/api/me/balance-logs').then(function(r){
   var el = $('balLogBox'); if(!el) return;
   var list = (r && r.data) || [];
   if(!list.length){ el.innerHTML = '<div style="color:var(--muted);padding:8px">暂无账单记录。充值/模型扣费后会显示在这里。</div>'; return; }
   var rows = list.map(function(b){
    var sign = (b.type === 'recharge' || b.type === 'commission') ? '+' : '';
    var color = (b.type === 'recharge' || b.type === 'commission') ? 'var(--green)' : 'var(--red)';
    var t = {recharge:'充值', consume:'扣费', commission:'返佣', admin_deduct:'管理员扣款'}[b.type] || b.type;
    return '<tr><td>' + t + '</td><td style="color:' + color + ';font-weight:600">' + sign + '¥' + (b.amount||0).toFixed(2) + '</td><td style="color:var(--muted)">' + esc(b.remark||'') + '</td><td style="font-size:11px;color:var(--muted)">' + new Date(b.createdAt).toLocaleString('zh-CN',{hour12:false}) + '</td></tr>';
   }).join('');
   el.innerHTML = '<div class="table-wrap"><table style="min-width:520px"><thead><tr><th>类型</th><th>金额</th><th>说明</th><th>时间</th></tr></thead><tbody>' + rows + '</tbody></table></div>';
  });
 };
 // ★ v77 模型扣费记录（/api/usage/recent 明细 + 清理 + 单删 + 按模型清理）
window.loadUsageLogs = function(){
  var el = $('usageLogBox'); if(!el) return;
  el.innerHTML = '<div style="color:var(--muted);padding:8px">加载中...</div>';
  api('/api/usage/recent?limit=100').then(function(r){
   var list = (r && r.data) || [];
   if(!list.length){ el.innerHTML = '<div style="color:var(--muted);padding:8px">暂无模型扣费记录。调用模型后会显示在这里。</div>'; return; }
   var rows = list.map(function(u){
    var cost = (u.cost||0);
    return '<tr>' +
     '<td style="font-family:monospace">' + esc(u.modelName || u.modelKey || '-') + '</td>' +
     '<td>¥' + cost.toFixed(4) + '</td>' +
     '<td>' + (u.totalTokens||0) + '</td>' +
     '<td style="font-size:11px;color:var(--muted)">' + (u.createdAt ? new Date(u.createdAt).toLocaleString('zh-CN',{hour12:false}) : '-') + '</td>' +
     '<td style="text-align:right"><button class="btn-ghost" style="padding:1px 8px;font-size:11px" onclick="delUsageLog(' + (u.id||0) + ')">删</button></td>' +
     '</tr>';
   }).join('');
   el.innerHTML = '<div style="font-size:11px;color:var(--muted);margin-bottom:6px">共 ' + list.length + ' 条（仅显示最近 100 条）。可单独删除某条，也可按模型清理。</div>' +
    '<div class="table-wrap"><table style="min-width:620px"><thead><tr><th>模型</th><th>扣费</th><th>Token</th><th>时间</th><th></th></tr></thead><tbody>' + rows + '</tbody></table></div>';
  });
 };
window.clearUsageLogs = function(){
  if(!confirm('确认清空全部模型扣费记录？此操作不可恢复')) return;
  api('/api/usage/clear', { method:'POST' }).then(function(r){
   if(r.code === 0){ toast(r.msg, true); loadUsageLogs(); } else toast(r.msg, false);
  });
 };
window.delUsageLog = function(id){
  if(!confirm('确认删除这条扣费记录？')) return;
  api('/api/usage/delete', { method:'POST', body: { id: id } }).then(function(r){
   if(r.code === 0){ toast(r.msg, true); loadUsageLogs(); } else toast(r.msg, false);
  });
 };
window.clearUsageModel = function(modelKey){
  if(!confirm('确认清空该模型全部扣费记录？')) return;
  api('/api/usage/clear-model', { method:'POST', body: { modelKey: modelKey } }).then(function(r){
   if(r.code === 0){ toast(r.msg, true); loadUsageLogs(); } else toast(r.msg, false);
  });
 };
window.saveProfile = function(){
 var email = $('pfEmail').value.trim();
 if(email && email.indexOf('@') < 0){ toast('邮箱格式不正确', false); return; }
 api('/api/me/profile', { method:'POST', body: { email: email, displayName: $('pfName').value.trim(), notifyEnabled: $('pfNotify').checked ? 'true' : 'false' } }).then(function(r){
  if(r.code === 0){ toast(r.msg, true); loaders.profile(); } else toast(r.msg, false);
 });
};
window.saveRate = function(){
 var qps = parseInt($('rateQps').value) || 0;
 var daily = parseInt($('rateDaily').value) || 0;
 api('/api/me/rate', { method:'POST', body: { qps: qps, daily: daily } }).then(function(r){
  if(r.code === 0){ toast(r.msg, true); } else toast(r.msg, false);
 });
};
// ===== 设置 =====
loaders.settings = function(){
 var box = $('view-settings');
 box.innerHTML = '<div style="text-align:center;color:var(--muted);padding:40px">加载中...</div>';
 api('/api/auth/me').then(function(me){
  var isAdmin = me && me.code === 0 && me.data && me.data.role === 'admin';
  api('/api/config').then(function(r){
  var cfg = r.data || {};
  box.innerHTML = [
   (isAdmin ? '<div class="card"><h3>网关设置</h3>' +
   '<div class="form-row"><label><input type="checkbox" id="cfgRequireKey"'+(cfg.require_api_key==='true'?' checked':'')+'>启用API密钥校验</label><small style="color:var(--muted);display:block;margin-top:4px">开启后本地除外，第三方请求需携带有效密钥</small></div>' +
   '<div class="form-row"><label><input type="checkbox" id="cfgFailover"'+(cfg.auto_failover!=='false'?' checked':'')+'>启用自动故障转移</label><small style="color:var(--muted);display:block;margin-top:4px">模型失败时自动切换池内下一个可用模型</small></div>' +
   '<div class="form-row"><label>活跃模型Key（qtai-sj 解析目标）</label><input id="cfgActive" class="input" value="'+esc(cfg.active_model_key||'')+'" placeholder="如 1::gpt-4o"></div>' +
'<div class="form-row"><label>强制故障池（逗号分隔）</label><input id="cfgPool" class="input" value="'+esc(cfg.forced_pool_keys||'')+'" placeholder="如 1::gpt-4o,1::gpt-3.5-turbo"></div>' +
    '<div class="form-row"><label><input type="checkbox" id="cfgHeartbeat"'+(cfg.heartbeat_enabled!=='false'?' checked':'')+'>启用自主心跳（qtai-sj 自检）</label><small style="color:var(--muted);display:block;margin-top:4px">无用户输入时检查记忆和任务，在活跃时段每 N 分钟自动自检一次，发现问题推送 QQ/微信</small></div>' +
    '<div class="form-row"><label>心跳间隔（分钟，5-1440，建议60）</label><input id="cfgHbm" class="input" type="number" min="5" max="1440" value="'+esc(cfg.heartbeat_interval_minutes||'60')+'" style="width:120px"></div>' +
    '<div class="form-row"><label>活跃时段开始（24h制）</label><input id="cfgHbStart" class="input" type="number" min="0" max="23" value="'+esc(cfg.heartbeat_active_start||'5')+'" style="width:80px"></div>' +
    '<div class="form-row"><label>活跃时段结束（24h制）</label><input id="cfgHbEnd" class="input" type="number" min="0" max="23" value="'+esc(cfg.heartbeat_active_end||'23')+'" style="width:80px"></div>' +
    '<div class="form-row"><label>心跳提示词（可选）</label><input id="cfgHbPrompt" class="input" value="'+esc(cfg.heartbeat_prompt||'')+'" placeholder="如：记得检查待办任务和重要记忆"></div>' +
    '<div class="form-row"><label><input type="checkbox" id="cfgMemory"'+(cfg.memory_enabled!=='false'?' checked':'')+'>启用记忆系统（AI 对话期间存储记忆，每条消息带上作为上下文）</label></div>' +
    '<div class="form-row"><label><input type="checkbox" id="cfgResponseCache"'+(cfg.response_cache!=='false'?' checked':'')+'>启用响应缓存（相同请求5分钟内直接返回，省token省延迟）</label><small style="color:var(--muted);display:block;margin-top:4px">实测：同请求首次 10.8s → 命中 0.02s，快 480 倍；普通闲聊/查询重复率高时收益最大</small></div>' +
    '<div class="form-row"><button class="btn-ghost btn-sm" onclick="loadCacheStats()">⚡ 缓存统计</button><span id="cacheStatsBox" style="font-size:12px;color:var(--muted);margin-left:8px"></span></div>' +
    '<div class="form-row"><label><input type="checkbox" id="cfgScheduledTasks"'+(cfg.scheduled_tasks_enabled!=='false'?' checked':'')+'>启用计划任务（AI 安排未来执行任务/提醒）</label></div>' +
    '<button class="btn" onclick="saveSettings()">保存设置</button></div>' : '') +
'<div class="card"><h3>个人人格配置</h3><small style="color:var(--muted);display:block;margin-bottom:12px">让 qtai-sj 回复时带上你设定的人设（按用户独立存储）</small>',
   '<div class="form-row"><label>名字</label><input id="psName" class="input" placeholder="如：綦小桐"></div>',
   '<div class="form-row"><label>年龄</label><input id="psAge" class="input" placeholder="如：18岁"></div>',
   '<div class="form-row"><label>性格</label><input id="psPersonality" class="input" placeholder="如：开朗、幽默、乐于助人"></div>',
   '<div class="form-row"><label>语气</label><input id="psTone" class="input" placeholder="如：亲切、像朋友一样"></div>',
   '<div class="form-row"><label>背景设定（超级文本，多行）</label><textarea id="psBg" class="input" rows="5" placeholder="如：你是綦桐AI网关的智能助手，擅长帮助用户使用AI网关、解答问题、管理记忆，像一个真实的朋友一样陪伴用户..."></textarea></div>',
   '<div class="form-row"><label>大五人格维度</label>',
   '<div style="font-size:12px;color:var(--muted)">开放度 <input id="psO" type="range" min="0" max="1" step="0.05" value="0.5" style="width:120px"> <span id="psOv">0.5</span></div>',
   '<div style="font-size:12px;color:var(--muted)">尽责性 <input id="psC" type="range" min="0" max="1" step="0.05" value="0.5" style="width:120px"> <span id="psCv">0.5</span></div>',
   '<div style="font-size:12px;color:var(--muted)">外向性 <input id="psE" type="range" min="0" max="1" step="0.05" value="0.5" style="width:120px"> <span id="psEv">0.5</span></div>',
   '<div style="font-size:12px;color:var(--muted)">宜人性 <input id="psA" type="range" min="0" max="1" step="0.05" value="0.5" style="width:120px"> <span id="psAv">0.5</span></div>',
   '<div style="font-size:12px;color:var(--muted)">神经质 <input id="psN" type="range" min="0" max="1" step="0.05" value="0.5" style="width:120px"> <span id="psNv">0.5</span></div></div>',
   '<div class="form-row"><label><input type="checkbox" id="psMem" checked>启用记忆系统</label></div>',
   '<button class="btn" onclick="savePersona()">保存人格</button>',
   '</div>',
   '<div class="card"><h3>修改密码</h3>',
   '<div class="form-row"><label>旧密码</label><input id="chgOld" class="input" type="password" placeholder="输入旧密码"></div>',
   '<div class="form-row"><label>新密码</label><input id="chgNew" class="input" type="password" placeholder="至少6个字符"></div>',
   '<button class="btn" onclick="changePassword()">修改密码</button>',
   '</div>',
'<div class="card" id="memCard"><h3>大脑记忆</h3><div style="color:var(--muted);padding:10px;font-size:13px">加载中...</div></div>',
    '<div class="card" id="memCfgCard"><h3>记忆配置</h3><div style="color:var(--muted);padding:10px;font-size:13px">加载中...</div></div>',
    '<div class="card" id="brainCard"><h3>qtai-sj 大脑绑定</h3><div style="color:var(--muted);padding:10px;font-size:13px">加载中...</div></div>',
    '<div class="card" id="langCard"><h3>界面语言</h3><div style="color:var(--muted);padding:10px;font-size:13px">加载中...</div></div>',
    '<div class="card" id="bakCard"><h3>数据备份/恢复</h3><div style="color:var(--muted);padding:10px;font-size:13px">加载中...</div></div>',
    (isAdmin ? '<div class="card" id="notifyCard"><h3>通知设置</h3><div style="color:var(--muted);padding:10px;font-size:13px">加载中...</div></div>' : '')
   ].join('');
  // 通知设置（仅管理员）
  if(isAdmin){
   api('/api/notify/config').then(function(nr){
    if(nr && nr.code === 0){
     var n = nr.data || {};
     var nc = $('notifyCard');
     if(nc){
      nc.innerHTML = '<h3>通知设置（钉钉 / 邮箱）</h3>' +
       '<div class="form-row"><label><input type="checkbox" id="ntDing"'+((n.enable_dingtalk==="true")?' checked':'')+'>启用钉钉通知</label><small style="color:var(--muted);display:block;margin-top:4px">新用户注册 / 新工单时推送到钉钉群</small></div>' +
       '<div class="form-row"><label>钉钉 Webhook 地址</label><input id="ntWebhook" class="input" value="'+esc(n.dingtalk_webhook||'')+'" placeholder="https://oapi.dingtalk.com/robot/send?access_token=..."></div>' +
       '<hr style="border-color:var(--border);margin:10px 0">' +
       '<div class="form-row"><label><input type="checkbox" id="ntEmail"'+((n.enable_email==="true")?' checked':'')+'>启用邮箱通知</label></div>' +
       '<div class="form-row"><label>SMTP 服务器</label><input id="ntSmtpHost" class="input" value="'+esc(n.email_smtp_host||'')+'" placeholder="smtp.qq.com"></div>' +
       '<div class="form-row"><label>SMTP 端口（SSL默认465）</label><input id="ntSmtpPort" class="input" value="'+esc(n.email_smtp_port||'465')+'"></div>' +
       '<div class="form-row"><label>邮箱账号</label><input id="ntEmailUser" class="input" value="'+esc(n.email_username||'')+'" placeholder="xxx@qq.com"></div>' +
       '<div class="form-row"><label>邮箱授权码/密码</label><input id="ntEmailPwd" class="input" type="password" value="'+esc(n.email_password||'')+'" placeholder="SMTP 授权码"></div>' +
       '<div class="form-row"><label>收件人（管理员）</label><input id="ntEmailTo" class="input" value="'+esc(n.email_to||'')+'" placeholder="admin@example.com"></div>' +
       '<div class="action-bar"><button class="btn" onclick="saveNotifyCfg()">保存通知设置</button>&nbsp;<button class="btn-ghost" onclick="testNotifyCfg()">发送测试通知</button></div>' +
       '<small style="color:var(--muted)">通知事件：新用户注册、新工单提交（对齐 dingtalk-notification.php）</small>';
     }
    }
   });
  }
  // 大脑记忆
  api('/api/memory').then(function(mr){
   if(mr && mr.code === 0){
    var mems = mr.data || [];
    var mc = $('memCard');
    if(mc){
     var rows = mems.map(function(m){
      return '<tr><td>'+esc(m.title||'(无标题)')+'</td><td style="font-size:12px;max-width:260px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap">'+esc(m.content)+'</td><td><span class="badge '+(m.emotion==='happy'?'green':m.emotion==='sad'?'red':'purple')+'">'+esc(m.emotion)+'</span></td><td>'+m.importance+'</td><td>'+new Date(m.timestamp).toLocaleString('zh-CN')+'</td><td><button class="btn-ghost" style="color:var(--red)" onclick="delMemory('+m.id+')">删</button></td></tr>';
     }).join('');
     mc.innerHTML = '<h3>大脑记忆 <button class="btn-ghost" style="padding:1px 8px;font-size:11px" onclick="addMemory()">添加</button> <button class="btn-ghost" style="padding:1px 8px;font-size:11px;color:var(--red)" onclick="clearMemories()">清空</button></h3>' +
      '<div class="table-wrap"><table><thead><tr><th>标题</th><th>内容</th><th>情感</th><th>重要</th><th>时间</th><th>操作</th></tr></thead><tbody>' +
      rows + '<tr><td colspan="6" style="text-align:center;color:var(--muted)">' + (mems.length ? '' : '暂无记忆') + '</td></tr></tbody></table></div>';
    }
   }
  });
  // 记忆配置（模型独立记忆开关，对齐原APP MemoryConfig）
  api('/api/memory/config').then(function(cr){
   if(cr && cr.code === 0){
    var c = cr.data || {};
    var cc = $('memCfgCard');
    if(cc){
     cc.innerHTML = '<h3>记忆配置</h3>' +
      '<div class="form-row"><label><input type="checkbox" id="mcEnabled"'+(c.enabled?' checked':'')+'>启用记忆系统</label></div>' +
      '<div class="form-row"><label><input type="checkbox" id="mcIndependent"'+(c.modelIndependent?' checked':'')+'>模型独立记忆</label><small style="color:var(--muted);display:block;margin-top:4px">开启后各模型记忆互相隔离，模型间互不干扰（对齐原APP）</small></div>' +
      '<div class="form-row"><label>保存模式</label><select id="mcMode" class="input"><option value="frequent"'+(c.saveMode==='frequent'?' selected':'')+'>频繁保存</option><option value="normal"'+(c.saveMode==='normal'||!c.saveMode?' selected':'')+'>正常保存</option><option value="occasional"'+(c.saveMode==='occasional'?' selected':'')+'>偶尔保存</option></select></div>' +
      '<div class="form-row"><label>共情力(1-10)：<b id="mcEmpV">'+(c.empathyLevel||8)+'</b></label><input id="mcEmp" type="range" min="1" max="10" value="'+(c.empathyLevel||8)+'" style="width:200px" oninput="$(\'mcEmpV\').textContent=this.value"></div>' +
      '<div class="form-row"><label>思考深度(1-5)：<b id="mcThinkV">'+(c.thinkingDepth||3)+'</b></label><input id="mcThink" type="range" min="1" max="5" value="'+(c.thinkingDepth||3)+'" style="width:200px" oninput="$(\'mcThinkV\').textContent=this.value"></div>' +
      '<div class="form-row"><label>口头禅（逗号分隔）</label><input id="mcCatch" class="input" value="'+esc(c.catchphrases||'')+'" placeholder="好嘞~,搞定了！"></div>' +
      '<div class="form-row"><label>禁用词（逗号分隔）</label><input id="mcForbid" class="input" value="'+esc(c.forbiddenWords||'')+'" placeholder="作为一个AI,AI语言模型"></div>' +
      '<div class="form-row"><label>专业领域</label><input id="mcExpert" class="input" value="'+esc(c.expertise||'全栈通用')+'"></div>' +
      '<div class="form-row"><label>沟通风格</label><input id="mcStyle" class="input" value="'+esc(c.communicationStyle||'自然亲切、像朋友聊天')+'"></div>' +
      '<button class="btn" onclick="saveMemoryCfg()">保存记忆配置</button>';
    }
   }
  });
  // qtai-sj 大脑绑定
  api('/api/qtai/brain').then(function(br){
   if(br && br.code === 0){
    var bc = $('brainCard');
    if(bc){
     var brain = (br.data && br.data.brain) || '';
     bc.innerHTML = '<h3>qtai-sj 大脑绑定</h3>' +
      '<div class="form-row"><label>绑定模型Key（如 2::deepseek-flash，留空=自动）</label><input id="qtBrain" class="input" value="'+esc(brain)+'" placeholder="如 2::deepseek-flash"></div>' +
      '<button class="btn" onclick="saveBrain()">保存绑定</button>' +
      '<div style="margin-top:8px;font-size:12px;color:var(--muted)">绑定后 qtai-sj 优先使用该模型作为大脑回复</div>';
    }
   }
  });
  // 界面语言
  api('/api/me/language').then(function(lr){
   if(lr && lr.code === 0){
    var lc = $('langCard');
    if(lc){
     var cur = (lr.data && lr.data.language) || 'zh';
     var LANGS = [
      ['zh','简体中文'],['zh-tw','繁體中文'],['en','English'],['ja','日本語'],['ko','한국어'],
      ['es','Español'],['fr','Français'],['de','Deutsch'],['ru','Русский'],['pt','Português'],
      ['vi','Tiếng Việt'],['th','ภาษาไทย'],['ar','العربية'],['hi','हिन्दी'],['id','Bahasa Indonesia']
     ];
     var opts = LANGS.map(function(l){ return '<option value="'+l[0]+'"'+(cur===l[0]?' selected':'')+'>'+l[1]+'</option>'; }).join('');
     lc.innerHTML = '<h3>界面语言</h3>' +
      '<div class="form-row"><label>选择语言</label><select id="langSel" class="input" onchange="saveLang()">'+opts+'</select></div>' +
      '<small style="color:var(--muted)">按用户独立存储，下次登录保留</small>';
    }
   }
  });
  // 数据备份/恢复
  api('/api/auth/me').then(function(me){
   var isAdmin = me && me.code===0 && me.data && me.data.role === 'admin';
   var bc = $('bakCard');
   if(bc){
    bc.innerHTML = '<h3>数据备份/恢复</h3>' +
     '<div style="display:flex;gap:8px;flex-wrap:wrap">' +
     '<button class="btn" onclick="exportData()">导出备份</button>' +
     '<button class="btn-ghost" onclick="importData()">导入恢复</button>' +
     '</div>' +
     '<small style="color:var(--muted);display:block;margin-top:6px">导出：服务商/模型/人格/记忆/自定义技能；导入：JSON 文件互传（APP 与线上互传）</small>';
   }
  });
  // 回填人格配置
  api('/api/persona').then(function(pr){
   if(pr && pr.code === 0 && pr.data && pr.data.name !== undefined){
    var p = pr.data;
    $('psName').value = p.name || '';
    $('psAge').value = p.age || '';
    $('psPersonality').value = p.personality || '';
    $('psTone').value = p.tone || '';
    $('psBg').value = p.background || '';
    $('psO').value = p.openness || 0.5; $('psOv').textContent = p.openness || 0.5;
    $('psC').value = p.conscientiousness || 0.5; $('psCv').textContent = p.conscientiousness || 0.5;
    $('psE').value = p.extraversion || 0.5; $('psEv').textContent = p.extraversion || 0.5;
    $('psA').value = p.agreeableness || 0.5; $('psAv').textContent = p.agreeableness || 0.5;
    $('psN').value = p.neuroticism || 0.5; $('psNv').textContent = p.neuroticism || 0.5;
    $('psMem').checked = p.memoryEnabled !== false;
   }
  });
  // 滑块实时显示
  ['psO','psC','psE','psA','psN'].forEach(function(id){ $('view-settings').querySelector('#'+id).addEventListener('input', function(){ $(''+id+'v').textContent = this.value; }); });
 });
 });
};
window.savePersona = function(){
 var body = {
  name: $('psName').value.trim(), age: $('psAge').value.trim(), personality: $('psPersonality').value.trim(),
  tone: $('psTone').value.trim(), background: $('psBg').value.trim(),
  openness: parseFloat($('psO').value)||0.5, conscientiousness: parseFloat($('psC').value)||0.5,
  extraversion: parseFloat($('psE').value)||0.5, agreeableness: parseFloat($('psA').value)||0.5,
  neuroticism: parseFloat($('psN').value)||0.5, memoryEnabled: $('psMem').checked
 };
 api('/api/persona', { method:'POST', body: body }).then(function(r){
  if(r.code === 0){ toast(r.msg, true); } else toast(r.msg, false);
 });
};
// ===== 大脑记忆操作 =====
window.addMemory = function(){
 openModal('添加记忆', '<div class="form-row"><label>标题</label><input id="mmTitle" class="input"></div><div class="form-row"><label>内容</label><textarea id="mmContent" class="input" rows="4"></textarea></div><div class="form-row"><label>情感</label><select id="mmEmotion" class="input"><option value="neutral">中性</option><option value="happy">开心</option><option value="sad">难过</option><option value="surprised">惊讶</option><option value="angry">生气</option></select></div><div class="form-row"><label>重要性(0-10)</label><input id="mmImp" class="input" type="number" min="0" max="10" value="5"></div>', function(){
  var title = $('mmTitle').value.trim();
  var content = $('mmContent').value.trim();
  if(!content){ toast('请输入内容', false); return; }
  api('/api/memory', { method:'POST', body: { title: title, content: content, emotion: $('mmEmotion').value, importance: parseInt($('mmImp').value)||5 } }).then(function(r){
   if(r.code === 0){ toast('记忆已保存', true); closeModal(); loaders.settings(); } else toast(r.msg, false);
  });
 });
};
window.delMemory = function(id){
 if(!confirm('删除这条记忆？')) return;
 api('/api/memory/' + id, { method:'DELETE' }).then(function(r){
  if(r.code === 0){ toast('已删除', true); loaders.settings(); } else toast(r.msg, false);
 });
};
window.clearMemories = function(){
 if(!confirm('清空全部记忆？')) return;
 api('/api/memory/clear', { method:'POST' }).then(function(r){
  if(r.code === 0){ toast('已清空', true); loaders.settings(); } else toast(r.msg, false);
 });
};
window.saveBrain = function(){
 var brain = $('qtBrain').value.trim();
 api('/api/qtai/brain', { method:'POST', body: { brain: brain } }).then(function(r){
  if(r.code === 0){ toast(r.msg, true); } else toast(r.msg, false);
 });
};
window.saveLang = function(){
 var lang = $('langSel').value;
 api('/api/me/language', { method:'POST', body: { language: lang } }).then(function(r){
  if(r.code === 0){
   saveLangLocal(lang); // 立即应用本地菜单翻译
   toast(r.msg, true);
   // 刷新当前页使其内容跟随新语言
   var cur = document.querySelector('.view.active');
   if(cur){
    var pg = cur.id.replace('view-','');
    if(loaders[pg]) loaders[pg]();
   }
  } else toast(r.msg, false);
 });
};
window.saveMemoryCfg = function(){
 var body = {
  enabled: $('mcEnabled').checked ? 'true' : 'false',
  saveMode: $('mcMode').value,
  empathyLevel: $('mcEmp').value,
  thinkingDepth: $('mcThink').value,
  catchphrases: $('mcCatch').value.trim(),
  forbiddenWords: $('mcForbid').value.trim(),
  expertise: $('mcExpert').value.trim(),
  communicationStyle: $('mcStyle').value.trim(),
  modelIndependent: $('mcIndependent').checked ? 'true' : 'false'
 };
 api('/api/memory/config', { method:'POST', body: body }).then(function(r){
  if(r.code === 0){ toast(r.msg, true); } else toast(r.msg, false);
 });
};
window.saveNotifyCfg = function(){
 var body = {
  dingtalk_webhook: $('ntWebhook').value.trim(),
  enable_dingtalk: $('ntDing').checked ? 'true' : 'false',
  email_smtp_host: $('ntSmtpHost').value.trim(),
  email_smtp_port: $('ntSmtpPort').value.trim(),
  email_username: $('ntEmailUser').value.trim(),
  email_password: $('ntEmailPwd').value.trim(),
  email_to: $('ntEmailTo').value.trim(),
  email_tls: 'true',
  enable_email: $('ntEmail').checked ? 'true' : 'false'
 };
 api('/api/notify/config', { method:'POST', body: body }).then(function(r){
  if(r.code === 0){ toast(r.msg, true); } else toast(r.msg, false);
 });
};
window.testNotifyCfg = function(){
 api('/api/notify/test', { method:'POST', body: { message: '【綦桐AI网关】测试通知，配置成功！' } }).then(function(r){
  if(r.code === 0){ toast(r.msg, true); } else toast(r.msg, false);
 });
};
// ===== 数据备份/恢复 =====
window.exportData = function(){
 api('/api/backup/export').then(function(r){
  if(r.code === 0){
   var data = JSON.stringify(r.data, null, 2);
   var blob = new Blob([data], { type: 'application/json' });
   var url = URL.createObjectURL(blob);
   var a = document.createElement('a');
   a.href = url;
   a.download = 'qitong-backup-' + new Date().toISOString().slice(0,10) + '.json';
   a.click();
   URL.revokeObjectURL(url);
   toast('备份已导出', true);
  } else toast(r.msg, false);
 });
};
window.importData = function(){
 var inp = document.createElement('input');
 inp.type = 'file';
 inp.accept = '.json';
 inp.onchange = function(){
  var f = inp.files[0];
  if(!f) return;
  var reader = new FileReader();
  reader.onload = function(){
   try {
    var data = JSON.parse(reader.result);
    api('/api/backup/import', { method:'POST', body: data }).then(function(r){
     if(r.code === 0){ toast(r.msg, true); loaders.settings(); } else toast(r.msg, false);
    });
   } catch(e){ toast('文件格式错误', false); }
  };
  reader.readAsText(f);
 };
 inp.click();
};
window.changePassword = function(){
 var oldPwd = $('chgOld').value;
 var newPwd = $('chgNew').value;
 if(!oldPwd || !newPwd){ toast('请填写旧密码和新密码', false); return; }
 if(newPwd.length < 6){ toast('新密码至少6个字符', false); return; }
 api('/api/auth/change-password', { method:'POST', body: { oldPassword: oldPwd, newPassword: newPwd } }).then(function(r){
  if(r.code === 0){
   toast(r.msg, true);
   setTimeout(function(){ localStorage.removeItem('qt_token'); location.href='/login'; }, 800);
  } else toast(r.msg, false);
 });
};
window.saveSettings = function(){
 api('/api/config', { method:'POST', body: { require_api_key: $('cfgRequireKey').checked?'true':'false', auto_failover: $('cfgFailover').checked?'true':'false', active_model_key: $('cfgActive').value.trim(), forced_pool_keys: $('cfgPool').value.trim(), heartbeat_enabled: $('cfgHeartbeat').checked?'true':'false', heartbeat_interval_minutes: ($('cfgHbm').value||'60'), heartbeat_active_start: ($('cfgHbStart').value||'5'), heartbeat_active_end: ($('cfgHbEnd').value||'23'), heartbeat_prompt: ($('cfgHbPrompt')?$('cfgHbPrompt').value.trim():''), memory_enabled: $('cfgMemory').checked?'true':'false', response_cache: ($('cfgResponseCache')?$('cfgResponseCache').checked?'true':'false':'true'), scheduled_tasks_enabled: $('cfgScheduledTasks').checked?'true':'false' } }).then(function(r){
  if(r.code === 0){ toast('设置已保存', true); } else toast(r.msg, false);
 });
};
// ★ v93 缓存统计：后台设置页直接看缓存命中/清理（管理员）
window.loadCacheStats = function(){
 api('/api/config/cache-stats').then(function(r){
  var box = $('cacheStatsBox'); if(!box) return;
  if(r.code !== 0 || !r.data){ box.textContent = '获取失败'; return; }
  box.textContent = '共 '+r.data.total+' 条缓存，累计命中 '+r.data.hits+' 次';
  if(r.data.total > 0 && confirm('是否清空全部缓存？')){
   api('/api/config/cache-clear', { method:'POST' }).then(function(cr){
    if(cr.code === 0){ box.textContent = '已清空'; toast('缓存已清空', true); } else toast(cr.msg, false);
   });
  }
 });
};
// ===== 用户（可编辑：额度/绑定模型/角色/权限/重置密码） =====
var PERM_OPTS = [
 { id:'provider.manage', label:'管理服务商（含系统）' },
 { id:'model.manage', label:'管理模型（含系统）' },
 { id:'system.config', label:'修改系统配置' },
 { id:'keys.manage', label:'管理API密钥（含系统）' },
 { id:'rules.manage', label:'管理路由规则（含系统）' },
 { id:'users.manage', label:'用户管理' },
 { id:'system.speedtest', label:'全局测速/强制池' },
 { id:'data.export', label:'数据导出' }
];
loaders.users = function(){
 var box = $('view-users');
 box.innerHTML = '<div style="text-align:center;color:var(--muted);padding:40px">加载中...</div>';
 api('/api/users').then(function(r){
  var users = r.data || [];
  var rows = users.map(function(u){
   var quotaText = u.quotaLimit > 0 ? (fmtNum(u.quotaUsed) + ' / ' + fmtNum(u.quotaLimit) + ' tok') : (u.quotaUsed > 0 ? fmtNum(u.quotaUsed) + ' tok' : '不限');
   var bindTxt = (u.bindModels && u.bindModels.length) ? u.bindModels.map(function(m){ return '<span class="badge blue">'+esc(m)+'</span>'; }).join(' ') : '<span style="color:var(--muted)">全部模型</span>';
   var permTxt = (u.permissions && u.permissions.length) ? u.permissions.map(function(p){ return '<span class="badge purple">'+esc(p)+'</span>'; }).join(' ') : '<span style="color:var(--muted)">仅私有</span>';
   if(u.role === 'admin') permTxt = '<span class="badge green">全部权限</span>';
var balTxt = '<span style="color:var(--green);font-weight:700">¥'+(u.balance||0).toFixed(2)+'</span>';
    var roleBadge = u.role==='admin' ? '<span class="badge purple">管理员</span>' : (u.role==='agent' ? '<span class="badge amber">代理</span>' : '<span class="badge blue">用户</span>');
    return '<tr><td>'+esc(u.username)+'</td><td>'+esc(u.displayName)+'</td><td>'+roleBadge+'</td><td>'+balTxt+' <button class="btn-ghost" style="padding:1px 8px;font-size:11px" onclick="rechargeUser('+u.id+',\''+esc(u.username)+'\')">充值</button> <button class="btn-ghost" style="padding:1px 8px;font-size:11px;color:var(--red)" onclick="deductUser('+u.id+',\''+esc(u.username)+'\')">扣款</button> <button class="btn-ghost" style="padding:1px 8px;font-size:11px;color:var(--cyan)" onclick="setBalanceUser('+u.id+',\''+esc(u.username)+'\','+(u.balance||0)+','+(u.totalRecharge||0)+')">调整</button></td><td>'+quotaText+'</td><td>'+bindTxt+'</td><td style="font-size:11px">'+permTxt+'</td><td style="font-size:11px;color:var(--muted)">'+(u.email?esc(u.email):'—')+'</td><td><button class="btn-ghost" onclick="editUser('+u.id+')">编辑</button> <button class="btn-ghost" style="color:var(--red)" onclick="delUser('+u.id+')">删除</button></td></tr>';
   }).join('');
   // 代理登录时提示管理自己的下级
   api('/api/auth/me').then(function(me){
    var isAgent = me && me.data && me.data.role === 'agent';
    box.innerHTML = [
     '<div class="card"><h3 style="display:flex;align-items:center;justify-content:space-between"> ' + (isAgent ? '我的下级用户' : '用户管理') + (isAgent ? '' : '<button class="btn" onclick="openUserCreate()">+ 添加用户</button>') + '</h3><div class="table-wrap"><table><thead><tr><th>用户名</th><th>昵称</th><th>角色</th><th>余额</th><th>额度使用</th><th>绑定模型</th><th>系统权限</th><th>邮箱</th><th>操作</th></tr></thead><tbody>' +
     rows + '<tr><td colspan="9" style="text-align:center;color:var(--muted)">' + (users.length ? '' : (isAgent ? '暂无下级用户，邀请注册或让客户填你的邀请码' : '暂无用户')) + '</td></tr>' +
     '</tbody></table></div><div style="margin-top:10px;color:var(--muted);font-size:12px">' + (isAgent ? '代理只能管理自己邀请/开通的下级客户；余额用于按模型价格扣费' : '注册页开放注册；可编辑用户角色 / 额度 / 绑定模型 / 系统权限；余额用于按模型价格扣费') + '</div></div>'
    ].join('');
   });
  });
 };
// ===== 管理员直接添加用户 =====
window.openUserCreate = function(){
 var html = [
  '<div class="form-row"><label>用户名 *</label><input id="cuName" class="input" placeholder="至少3个字符"></div>',
  '<div class="form-row"><label>初始密码 *</label><input id="cuPwd" class="input" type="text" placeholder="至少6个字符，登录后可改"></div>',
  '<div class="form-row"><label>昵称</label><input id="cuDisplay" class="input" placeholder="选填"></div>',
  '<div class="form-row"><label>角色</label><select id="cuRole" class="input"><option value="user">普通用户</option><option value="agent">代理</option><option value="admin">管理员</option></select></div>',
  '<div class="form-row"><label>初始余额（元）</label><input id="cuBalance" class="input" type="number" step="0.01" value="0"></div>',
  '<div class="form-row"><label>额度上限（token，0=不限）</label><input id="cuQuota" class="input" type="number" value="0"></div>'
 ].join('');
 openModal('添加用户', html, function(){
  var body = { username: $('cuName').value.trim(), password: $('cuPwd').value, displayName: $('cuDisplay').value.trim(),
   role: $('cuRole').value, balance: parseFloat($('cuBalance').value)||0, quotaLimit: parseInt($('cuQuota').value)||0 };
  if(body.username.length < 3){ toast('用户名至少3个字符', false); return; }
  if(body.password.length < 6){ toast('密码至少6个字符', false); return; }
  api('/api/users/create', { method:'POST', body: body }).then(function(r){
   if(r.code === 0){ toast(r.msg, true); closeModal(); loaders.users(); } else toast(r.msg, false);
  });
 });
};
// ===== 用户扣款 =====
window.deductUser = function(id, name){
 openModal('扣款 - ' + name, '<div class="form-row"><label>扣款金额（元）</label><input id="dcAmount" class="input" type="number" step="0.01" min="0.01" placeholder="如 5.00"></div>', function(){
  var amount = parseFloat($('dcAmount').value);
  if(!amount || amount <= 0){ toast('请输入有效金额', false); return; }
  api('/api/users/deduct', { method:'POST', body: { id: id, amount: amount } }).then(function(r){
   if(r.code === 0){ toast(r.msg, true); closeModal(); loaders.users(); } else toast(r.msg, false);
  });
 });
};
// ★ v1.107 管理员调整余额 + 累计充值
window.setBalanceUser = function(id, name, curBal, curTotal){
 openModal('调整余额/累计充值 - ' + name,
  '<div class="form-row"><label>当前余额</label><div style="color:var(--green);font-weight:700">¥'+(curBal||0).toFixed(2)+'</div></div>'+
  '<div class="form-row"><label>设置余额（元）</label><input id="sbBal" class="input" type="number" step="0.01" min="0" value="'+(curBal||0)+'"></div>'+
  '<div class="form-row"><label>累计充值（元，供统计展示）</label><input id="sbTotal" class="input" type="number" step="0.01" min="0" value="'+(curTotal||0)+'"></div>'+
  '<div style="font-size:12px;color:var(--muted)">设置余额=直接改到该值；累计充值=改到该值（不影响余额）</div>', function(){
  var bal = parseFloat($('sbBal').value);
  var total = parseFloat($('sbTotal').value);
  if(isNaN(bal) || bal < 0){ toast('余额无效', false); return; }
  if(isNaN(total) || total < 0){ toast('累计充值无效', false); return; }
  api('/api/users/set-balance', { method:'POST', body: { id: id, balance: bal } }).then(function(r){
   if(r.code !== 0){ toast(r.msg, false); return; }
   api('/api/users/set-total-recharge', { method:'POST', body: { id: id, totalRecharge: total } }).then(function(r2){
    if(r2.code === 0){ toast('余额与累计充值已更新', true); closeModal(); loaders.users(); } else toast(r2.msg, false);
   });
  });
 });
};
// ===== 用户充值 =====
window.rechargeUser = function(id, name){
 openModal('充值 - ' + name, '<div class="form-row"><label>充值金额（元）</label><input id="rcAmount" class="input" type="number" step="0.01" min="0.01" placeholder="如 10.00"></div>', function(){
  var amount = parseFloat($('rcAmount').value);
  if(!amount || amount <= 0){ toast('请输入有效金额', false); return; }
  api('/api/users/recharge', { method:'POST', body: { id: id, amount: amount } }).then(function(r){
   if(r.code === 0){ toast(r.msg, true); closeModal(); loaders.users(); } else toast(r.msg, false);
  });
 });
};
// ===== 用户编辑（含权限设置） =====
window.editUser = function(id){
 api('/api/users').then(function(r){
  var users = r.data || [];
  var u = users.find(function(x){ return x.id===id; });
  if(!u){ toast('用户不存在', false); return; }
  var modelOpts = '';
  api('/api/models').then(function(mr){
   var models = mr.data || [];
   // 仅列出系统级模型（ownerId=0）作为绑定选项
   var sysModels = models.filter(function(m){ return m.ownerId === 0; });
   if(!sysModels.length) sysModels = models;
   sysModels.forEach(function(m){ modelOpts += '<option value="'+esc(m.modelId)+'"'+(u.bindModels.indexOf(m.modelId)>=0?' selected':'')+'>'+esc(m.modelId)+'</option>'; });
   var permCheck = PERM_OPTS.map(function(p){
    var checked = (u.role === 'admin') || (u.permissions||[]).indexOf(p.id) >= 0;
    return '<label style="display:flex;align-items:center;gap:6px;margin:3px 0;font-size:13px"><input type="checkbox" class="usPermCb" value="'+p.id+'"'+(checked?' checked':'')+(u.role==='admin'?' disabled':'')+'> '+p.label+'</label>';
   }).join('');
   var html = [
    '<div class="form-row"><label>用户名</label><input class="input" value="'+esc(u.username)+'" disabled></div>',
    '<div class="form-row"><label>昵称</label><input id="euName" class="input" value="'+esc(u.displayName||'')+'"></div>',
    '<div class="form-row"><label>角色</label><select id="euRole" class="input"><option value="user"'+(u.role==='user'?' selected':'')+'>用户</option><option value="agent"'+(u.role==='agent'?' selected':'')+'>代理</option><option value="admin"'+(u.role==='admin'?' selected':'')+'>管理员</option></select></div>',
    '<div class="form-row"><label>额度上限（token，0=不限）</label><input id="euQuota" class="input" type="number" value="'+(u.quotaLimit||0)+'"></div>',
    '<div class="form-row"><label>已用额度（token）</label><input id="euUsed" class="input" type="number" value="'+(u.quotaUsed||0)+'"></div>',
    '<div class="form-row"><label>绑定模型（Ctrl多选，留空=全部）</label><select id="euModels" class="input" multiple size="4">'+modelOpts+'</select></div>',
    '<div class="form-row"><label>系统权限（勾选=可管理该系统资源）</label><div style="border:1px solid var(--border);border-radius:8px;padding:8px 12px">'+permCheck+'</div></div>',
    '<div class="form-row"><label>重置密码（留空不改）</label><input id="euPwd" class="input" type="password" placeholder="至少6个字符"></div>'
   ].join('');
   openModal('编辑用户 - ' + u.username, html, function(){
    var models = Array.from($('euModels').selectedOptions).map(function(o){ return o.value; });
    var perms = Array.from(document.querySelectorAll('.usPermCb')).filter(function(c){ return c.checked && !c.disabled; }).map(function(c){ return c.value; });
    var body = { id: id, displayName: $('euName').value.trim(), role: $('euRole').value, quotaLimit: parseInt($('euQuota').value)||0, quotaUsed: parseInt($('euUsed').value)||0, bindModels: models, permissions: perms };
    var pwd = $('euPwd').value;
    if(pwd){ if(pwd.length < 6){ toast('密码至少6个字符', false); return; } body.newPassword = pwd; }
    api('/api/users/update', { method:'POST', body: body }).then(function(rr){
     if(rr.code === 0){ toast(rr.msg, true); closeModal(); loaders.users(); } else toast(rr.msg, false);
    });
   });
  });
 });
};
window.delUser = function(id){
 if(!confirm('确定删除该用户？')) return;
 api('/api/users/delete', { method:'POST', body: { id: id } }).then(function(r){
  if(r.code === 0){ toast(r.msg, true); loaders.users(); } else toast(r.msg, false);
 });
};
// ===== 工单中心（用户提交，管理员反馈，聊天式） =====
var currentTicketId = 0;
loaders.tickets = function(){
 var box = $('view-tickets');
 box.innerHTML = '<div class="action-bar"><button class="btn" onclick="openNewTicket()">提交新工单</button><span style="color:var(--muted);font-size:13px">遇到问题提交工单，管理员会尽快回复</span></div><div class="card" id="ticketListCard"><div style="text-align:center;color:var(--muted);padding:30px">加载中...</div></div>';
 api('/api/tickets').then(function(r){
  if(r.code !== 0){ toast(r.msg, false); return; }
  var list = r.data || [];
  var rows = list.map(function(t){
   var badge = t.status === 'open' ? '<span class="badge green">进行中</span>' : (t.status === 'closed' ? '<span class="badge gray">已关闭</span>' : '<span class="badge blue">'+esc(t.status)+'</span>');
   return '<tr><td><b>'+esc(t.title)+'</b><div style="font-size:12px;color:var(--muted);margin-top:4px;max-width:300px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap">'+esc(t.lastMsg||'')+'</div></td><td>'+badge+'</td><td style="font-size:12px;color:var(--muted)">'+new Date(t.createdAt).toLocaleString('zh-CN',{hour12:false})+'</td><td><button class="btn-ghost" onclick="openTicket('+t.id+')">查看/回复</button> <button class="btn-ghost" style="color:var(--red)" onclick="delTicket('+t.id+')">删除</button></td></tr>';
  }).join('');
  $('ticketListCard').innerHTML = '<h3>我的工单</h3><div class="table-wrap"><table><thead><tr><th>标题</th><th>状态</th><th>时间</th><th>操作</th></tr></thead><tbody>' +
   rows + '<tr><td colspan="4" style="text-align:center;color:var(--muted)">' + (list.length ? '' : '暂无工单，点击上方按钮提交') + '</td></tr></tbody></table></div>';
 });
};
window.openNewTicket = function(){
 openModal('提交工单', '<div class="form-row"><label>工单标题</label><input id="tkTitle" class="input" placeholder="简要描述问题，如：某个模型不可用"></div>', function(){
  var title = $('tkTitle').value.trim();
  if(!title){ toast('请填写标题', false); return; }
  api('/api/tickets', { method:'POST', body: { title: title } }).then(function(r){
   if(r.code === 0){ toast('工单已提交', true); closeModal(); loaders.tickets(); } else toast(r.msg, false);
  });
 });
};
window.openTicket = function(id){
 currentTicketId = id;
 api('/api/tickets/' + id + '/messages').then(function(r){
  if(r.code !== 0){ toast(r.msg, false); return; }
  var msgs = r.data || [];
  var rows = msgs.map(function(m){
   var side = (m.role === 'admin') ? 'assistant' : 'user';
   return '<div class="msg-row '+side+'"><div class="bubble"><small style="color:var(--muted);display:block">'+esc(m.role==='admin'?'管理员':'我')+' · '+new Date(m.createdAt).toLocaleString('zh-CN',{hour12:false})+'</small>'+esc(m.content)+'</div></div>';
  }).join('');
  openModal('工单对话', '<div class="chat-box" style="height:340px"><div class="chat-msgs" id="tkMsgs">' + (rows || '<div class="msg-row system"><div class="bubble">暂无消息</div></div>') + '</div><div class="chat-input"><input id="tkInput" class="input" placeholder="输入回复... (Enter发送)" onkeydown="if(event.key===\'Enter\')sendTicketMsg()"><button class="btn" onclick="sendTicketMsg()">发送</button></div></div>' + (r.role==='admin' ? '<div style="margin-top:8px"><button class="btn-ghost" onclick="closeTicket('+id+')">关闭工单</button></div>' : ''), function(){}, { hideFooter:true });
  var el = $('tkMsgs'); if(el) el.scrollTop = el.scrollHeight;
 });
};
window.sendTicketMsg = function(){
 var input = $('tkInput'); if(!input) return;
 var content = input.value.trim(); if(!content || !currentTicketId) return;
 api('/api/tickets/' + currentTicketId + '/messages', { method:'POST', body: { content: content } }).then(function(r){
  if(r.code === 0){ openTicket(currentTicketId); } else toast(r.msg, false);
 });
};
window.closeTicket = function(id){
 if(!confirm('关闭该工单？')) return;
 api('/api/tickets/' + id + '/status', { method:'POST', body: { status: 'closed' } }).then(function(r){
  if(r.code === 0){ toast('工单已关闭', true); closeModal(); loaders.tickets(); } else toast(r.msg, false);
 });
};
window.delTicket = function(id){
 if(!confirm('删除该工单？')) return;
 api('/api/tickets/' + id + '/delete', { method:'POST' }).then(function(r){
  if(r.code === 0){ toast('工单已删除', true); loaders.tickets(); } else toast(r.msg, false);
 });
};
// ===== 日志中心（登录日志 + 操作日志；管理员=全部，普通用户=自己的，独立可见） =====
function fmtLogTime(ts){
 if(!ts) return '—';
 var d = new Date(ts);
 return d.toLocaleString('zh-CN',{hour12:false});
}
loaders.logs = function(){
 var box = $('view-logs');
 box.innerHTML = '<div class="action-bar"><button class="btn-ghost" style="color:var(--red)" onclick="clearLoginLogs()">清空登录日志</button><button class="btn-ghost" style="color:var(--red)" onclick="clearLogs()">清空操作日志</button><span style="color:var(--muted);font-size:13px">登录日志：登录IP/成功失败/时间；操作日志：登录/公告/工单/余额变动等</span></div>' +
  '<div style="display:flex;gap:6px;margin-bottom:14px;border-bottom:1px solid var(--border)">' +
   '<button class="log-tab on" id="logTabLogin" onclick="switchLogTab(\'login\')">🔐 登录日志</button>' +
   '<button class="log-tab" id="logTabOp" onclick="switchLogTab(\'op\')">📜 操作日志</button>' +
  '</div>' +
  '<div class="card" id="logsCard"><div style="text-align:center;color:var(--muted);padding:30px">加载中...</div></div>';
 loadLoginLogs();
};
window.switchLogTab = function(t){
 var lt = $('logTabLogin'), ot = $('logTabOp');
 if(lt) lt.className = 'log-tab' + (t === 'login' ? ' on' : '');
 if(ot) ot.className = 'log-tab' + (t === 'op' ? ' on' : '');
 if(t === 'login') loadLoginLogs(); else loadOpLogs();
};
function loadLoginLogs(){
 api('/api/logs/login').then(function(r){
  if(r.code !== 0){ toast(r.msg, false); return; }
  var list = r.data || [];
  var rows = list.map(function(l){
   var st = l.success ? '<span class="badge green">成功</span>' : '<span class="badge red">失败</span>';
   return '<tr><td>'+esc(l.username||'-')+'</td><td>'+st+'</td><td style="font-size:12px;color:var(--muted)">'+esc(l.ip||'')+'</td><td>'+esc(l.detail||'')+'</td><td style="font-size:12px;color:var(--muted)">'+fmtLogTime(l.createdAt)+'</td></tr>';
  }).join('');
  var card = $('logsCard'); if(!card) return;
  card.innerHTML = '<h3>🔐 登录日志</h3><div class="table-wrap"><table><thead><tr><th>用户</th><th>状态</th><th>IP</th><th>详情</th><th>时间</th></tr></thead><tbody>' +
   rows + '<tr><td colspan="5" style="text-align:center;color:var(--muted)">' + (list.length ? '' : '暂无登录日志') + '</td></tr></tbody></table></div>';
 });
}
function loadOpLogs(){
 api('/api/logs').then(function(r){
  if(r.code !== 0){ toast(r.msg, false); return; }
  var list = r.data || [];
  var rows = list.map(function(l){
   return '<tr><td>'+esc(l.username||'-')+'</td><td><span class="badge blue">'+esc(l.action)+'</span></td><td style="max-width:300px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap">'+esc(l.detail||'')+'</td><td style="font-size:12px;color:var(--muted)">'+esc(l.ip||'')+'</td><td style="font-size:12px;color:var(--muted)">'+fmtLogTime(l.createdAt)+'</td></tr>';
  }).join('');
  var card = $('logsCard'); if(!card) return;
  card.innerHTML = '<h3>📜 操作日志</h3><div class="table-wrap"><table><thead><tr><th>用户</th><th>操作</th><th>详情</th><th>IP</th><th>时间</th></tr></thead><tbody>' +
   rows + '<tr><td colspan="5" style="text-align:center;color:var(--muted)">' + (list.length ? '' : '暂无日志') + '</td></tr></tbody></table></div>';
 });
}
window.clearLogs = function(){
 if(!confirm('清空全部操作日志？')) return;
 api('/api/logs/clear', { method:'POST' }).then(function(r){
  if(r.code === 0){ toast('已清空', true); loadOpLogs(); } else toast(r.msg, false);
 });
};
window.clearLoginLogs = function(){
 if(!confirm('清空全部登录日志？')) return;
 api('/api/logs/login/clear', { method:'POST' }).then(function(r){
  if(r.code === 0){ toast('已清空', true); loadLoginLogs(); } else toast(r.msg, false);
 });
};
// ===== 公告管理（仅管理员） =====
loaders.announcements = function(){
 var box = $('view-announcements');
 box.innerHTML = '<div class="action-bar"><button class="btn" onclick="openNewAnnouncement()">发布公告</button><span style="color:var(--muted);font-size:13px">公告将显示在首页顶部</span></div><div class="card" id="annCard"><div style="text-align:center;color:var(--muted);padding:30px">加载中...</div></div>';
 api('/api/announcements').then(function(r){
  if(r.code !== 0){ toast(r.msg, false); return; }
  var list = r.data || [];
  var rows = list.map(function(a){
   return '<tr><td>'+(a.isPinned?'<span class="badge red">置顶</span>':'')+' <b>'+esc(a.title)+'</b></td><td style="max-width:300px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap">'+esc(a.content)+'</td><td style="font-size:12px;color:var(--muted)">'+new Date(a.createdAt).toLocaleString('zh-CN',{hour12:false})+'</td><td><button class="btn-ghost" onclick="editAnnouncement('+a.id+')">编辑</button> <button class="btn-ghost" style="color:var(--red)" onclick="delAnnouncement('+a.id+')">删除</button></td></tr>';
  }).join('');
  $('annCard').innerHTML = '<h3>公告管理</h3><div class="table-wrap"><table><thead><tr><th>标题</th><th>内容</th><th>时间</th><th>操作</th></tr></thead><tbody>' +
   rows + '<tr><td colspan="4" style="text-align:center;color:var(--muted)">' + (list.length ? '' : '暂无公告') + '</td></tr></tbody></table></div>';
 });
};
window.openNewAnnouncement = function(){
 openModal('发布公告', '<div class="form-row"><label>公告标题</label><input id="anTitle" class="input" placeholder="如：服务升级通知"></div><div class="form-row"><label>公告内容</label><textarea id="anContent" class="input" rows="4" placeholder="公告详情"></textarea></div><div class="form-row"><label><input type="checkbox" id="anPinned">置顶显示</label></div>', function(){
  var title = $('anTitle').value.trim();
  var content = $('anContent').value.trim();
  if(!title || !content){ toast('请填写标题和内容', false); return; }
  api('/api/announcements', { method:'POST', body: { title: title, content: content, isPinned: $('anPinned').checked ? 'true' : 'false' } }).then(function(r){
   if(r.code === 0){ toast('公告已发布', true); closeModal(); loaders.announcements(); } else toast(r.msg, false);
  });
 });
};
window.editAnnouncement = function(id){
 var all = null;
 api('/api/announcements').then(function(r){
  var a = (r.data || []).find(function(x){ return x.id === id; });
  if(!a){ toast('公告不存在', false); return; }
  openModal('编辑公告', '<div class="form-row"><label>公告标题</label><input id="anTitle" class="input" value="'+esc(a.title)+'"></div><div class="form-row"><label>公告内容</label><textarea id="anContent" class="input" rows="4">'+esc(a.content)+'</textarea></div><div class="form-row"><label><input type="checkbox" id="anPinned"'+(a.isPinned?' checked':'')+'>置顶显示</label></div>', function(){
   var title = $('anTitle').value.trim();
   var content = $('anContent').value.trim();
   if(!title || !content){ toast('请填写标题和内容', false); return; }
   api('/api/announcements/update', { method:'POST', body: { id: id, title: title, content: content, isPinned: $('anPinned').checked ? 'true' : 'false' } }).then(function(rr){
    if(rr.code === 0){ toast('公告已更新', true); closeModal(); loaders.announcements(); } else toast(rr.msg, false);
   });
  });
 });
};
window.delAnnouncement = function(id){
 if(!confirm('删除该公告？')) return;
 api('/api/announcements/delete', { method:'POST', body: { id: id } }).then(function(r){
  if(r.code === 0){ toast('公告已删除', true); loaders.announcements(); } else toast(r.msg, false);
 });
};
// ===== 关于我们（对齐原APP AboutScreen） =====
loaders.about = function(){
 var box = $('view-about');
 var ver = (typeof APP_VER !== 'undefined') ? APP_VER : '';
 box.innerHTML = [
  '<div class="card" style="text-align:center;padding:30px">',
   '<div class="avatar" style="margin:0 auto 12px;width:64px;height:64px;font-size:26px">綦</div>',
   '<h2 style="font-size:20px;color:var(--text)">綦桐AI网关</h2>',
   '<p style="color:var(--cyan);margin:6px 0">Docker Server v' + ver + '</p>',
   '<p style="color:var(--muted);font-size:13px">AI 网关管理工具 · 多租户 · 商业化 · 公益站</p>',
   '<hr style="border-color:var(--border);margin:16px 0">',
   '<div style="text-align:left;font-size:13px;line-height:2;color:var(--text)">',
    '<div><b>应用信息</b></div>',
    '<div>名称：綦桐AI网关 Docker版</div>',
    '<div>版本：v' + ver + '</div>',
    '<div>协议：Apache 2.0 开源</div>',
    '<div style="margin-top:10px"><b>核心功能</b></div>',
    '<div>· 多租户权限管控 / API密钥私有化</div>',
    '<div>· 商业化：余额/充值/扣款/模型定价</div>',
    '<div>· 三指标测速（TTFT/TPS/总耗时）+ 故障转移</div>',
    '<div>· AI大脑记忆 / 人格配置 / 技能系统</div>',
    '<div>· 分销返佣 / 公告 / 工单 / 操作日志</div>',
    '<div style="margin-top:10px"><b>联系我们</b></div>',
    '<div>GitHub：github.com/qtgf520/Docker-qitong-ai-gateway</div>',
   '</div>',
  '</div>'
 ].join('');
};
// ===== 启动 =====
// 先加载用户语言并应用菜单翻译
api('/api/me/language').then(function(lr){
 if(lr && lr.code === 0 && lr.data && lr.data.language){
  curLang = lr.data.language;
  localStorage.setItem('qt_lang', curLang);
  applyLang();
 }
});
api('/api/auth/me').then(function(r){
 if(r.code === 0){
  var me = r.data;
  localStorage.setItem('qt_user', me.displayName || me.username || '');
  $('userInfo').textContent = me.displayName || me.username;
  var isAdmin = me.role === 'admin';
  var isAgent = me.role === 'agent';
  // 权限菜单显示：管理员=admin-only全显示；代理=agent-only显示；普通用户=都隐藏
  var adminItems = document.querySelectorAll('.admin-only');
  for(var i=0;i<adminItems.length;i++){ adminItems[i].style.display = isAdmin ? '' : 'none'; }
  var agentItems = document.querySelectorAll('.agent-only');
  for(var j=0;j<agentItems.length;j++){ agentItems[j].style.display = (isAdmin || isAgent) ? '' : 'none'; }
  // 工单中心：管理员看到全部工单（含管理回复）
 } else { localStorage.removeItem('qt_token'); location.href='/login'; }
});
applyLang();
switchView('dashboard');
"""
}