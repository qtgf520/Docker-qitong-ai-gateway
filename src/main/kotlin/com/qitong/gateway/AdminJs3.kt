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
      return '<tr><td style="font-family:monospace;font-size:12px">'+esc(k.key)+'</td><td>'+esc(k.label)+'</td><td><span class="badge '+(k.enabled?'green':'red')+'">'+(k.enabled?'启用':'停用')+'</span></td><td>'+mt+'</td><td><span class="badge '+(k.qtaiSjAccess?'purple':'gray')+'">'+(k.qtaiSjAccess?'允许':'禁止')+'</span></td><td><span class="badge blue">'+esc(k.ownerName||'')+'</span></td><td><button class="btn-ghost" onclick="copyKey(\''+keyJs+'\')">📋 复制</button> <button class="btn-ghost" onclick="editKey(\''+keyJs+'\')">编辑</button> <button class="btn-ghost" style="color:var(--red)" onclick="delKey(\''+keyJs+'\')">删除</button></td></tr>';
    }).join('');
    box.innerHTML = [
      '<div class="action-bar"><button class="btn" onclick="addKey()">＋ 添加密钥</button></div>',
      '<div class="card"><div class="table-wrap"><table><thead><tr><th>密钥</th><th>标签</th><th>状态</th><th>可用模型</th><th>qtai-sj</th><th>属主</th><th>操作</th></tr></thead><tbody>' +
      rows + '<tr><td colspan="7" style="text-align:center;color:var(--muted)">' + (state.keys.length ? '' : '暂无密钥') + '</td></tr>' +
      '</tbody></table></div></div>'
    ].join('');
  });
};
// 复制密钥
window.copyKey = function(k){
  var txt = k.replace(/&#39;/g, "'");
  if(navigator.clipboard){ navigator.clipboard.writeText(txt).then(function(){ toast('✅ 已复制密钥', true); }); }
  else { toast('✅ 已复制: ' + txt, true); }
};
window.addKey = function(){
  var modelOpts = state.models.map(function(m){ return '<option value="'+esc(m.modelId)+'">'+esc(m.displayName)+'</option>'; }).join('');
  var html = [
    '<div class="form-row"><label>密钥（留空自动生成）</label><input id="kKey" class="input"></div>',
    '<div class="form-row"><label>标签</label><input id="kLabel" class="input"></div>',
    '<div class="form-row"><label>允许的模型（Ctrl多选，留空=全部）</label><select id="kModels" class="input" multiple size="4">'+modelOpts+'</select></div>',
    '<div class="form-row"><label><input type="checkbox" id="kQtai" checked> 允许访问 qtai-sj</label></div>'
  ].join('');
  openModal('添加密钥', html, function(){
    var key = $('kKey').value.trim() || 'sk-qt-' + Math.random().toString(36).slice(2,12);
    var models = Array.from($('kModels').selectedOptions).map(function(o){ return o.value; });
    api('/api/keys', { method:'POST', body: { key: key, label: $('kLabel').value.trim(), allowedModels: models, qtaiSjAccess: $('kQtai').checked } }).then(function(r){
      if(r.code === 0){ toast('✅ 密钥: '+key, true); closeModal(); loaders.keys(); } else toast(r.msg, false);
    });
  });
};
window.delKey = function(k){
  if(!confirm('删除密钥 '+k+' ?')) return;
  api('/api/keys/' + encodeURIComponent(k), { method:'DELETE' }).then(function(r){
    if(r.code === 0){ toast('✅ 已删除', true); loaders.keys(); } else toast(r.msg, false);
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
        if(r.code === 0){ toast('✅ ' + r.msg, true); closeModal(); loaders.keys(); } else toast(r.msg, false);
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
      '<div class="action-bar"><button class="btn" onclick="addRule()">＋ 添加规则</button></div>',
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
      if(r.code === 0){ toast('✅ 已添加', true); closeModal(); loaders.rules(); } else toast(r.msg, false);
    });
  });
};
window.delRule = function(id){
  if(!confirm('删除该规则？')) return;
  api('/api/rules/' + id, { method:'DELETE' }).then(function(r){
    if(r.code === 0){ toast('✅ 已删除', true); loaders.rules(); } else toast(r.msg, false);
  });
};
// ===== 用量 =====
loaders.usage = function(){
  var box = $('view-usage');
  box.innerHTML = '<div style="text-align:center;color:var(--muted);padding:40px">加载中...</div>';
  api('/api/stats').then(function(r){
    var s = r.data;
    var rows = (s.usage || []).map(function(u,i){
      return '<tr><td>'+(i+1)+'</td><td>'+esc(u.model_key)+'</td><td>'+(u.calls||0)+'</td><td>'+fmtNum(u.total_tokens||0)+'</td><td>'+fmtBytes(u.upload_bytes||0)+'</td><td>'+fmtBytes(u.download_bytes||0)+'</td></tr>';
    }).join('');
    box.innerHTML = [
      '<div class="grid grid-3">',
        '<div class="stat"><div class="num">'+fmtBytes(s.totalUpload)+'</div><div class="lbl">总上行</div></div>',
        '<div class="stat"><div class="num">'+fmtBytes(s.totalDownload)+'</div><div class="lbl">总下行</div></div>',
        '<div class="stat"><div class="num">'+(s.usage||[]).length+'</div><div class="lbl">模型维度</div></div>',
      '</div>',
      '<div class="card" style="margin-top:14px"><h3>按模型用量</h3><div class="table-wrap"><table><thead><tr><th>#</th><th>模型Key</th><th>调用</th><th>Tokens</th><th>上行</th><th>下行</th></tr></thead><tbody>' +
      rows + '<tr><td colspan="6" style="text-align:center;color:var(--muted)">' + ((s.usage||[]).length ? '' : '暂无用量') + '</td></tr>' +
      '</tbody></table></div></div>'
    ].join('');
  });
};
// ===== 设置 =====
loaders.settings = function(){
  var box = $('view-settings');
  box.innerHTML = '<div style="text-align:center;color:var(--muted);padding:40px">加载中...</div>';
  api('/api/config').then(function(r){
    var cfg = r.data || {};
    box.innerHTML = [
      '<div class="card"><h3>⚙️ 网关设置</h3>',
      '<div class="form-row"><label><input type="checkbox" id="cfgRequireKey"'+(cfg.require_api_key==='true'?' checked':'')+'> 启用API密钥校验</label><small style="color:var(--muted);display:block;margin-top:4px">开启后本地除外，第三方请求需携带有效密钥</small></div>',
      '<div class="form-row"><label><input type="checkbox" id="cfgFailover"'+(cfg.auto_failover!=='false'?' checked':'')+'> 启用自动故障转移</label><small style="color:var(--muted);display:block;margin-top:4px">模型失败时自动切换池内下一个可用模型</small></div>',
      '<div class="form-row"><label>活跃模型Key（qtai-sj 解析目标）</label><input id="cfgActive" class="input" value="'+esc(cfg.active_model_key||'')+'" placeholder="如 1::gpt-4o"></div>',
      '<div class="form-row"><label>强制故障池（逗号分隔）</label><input id="cfgPool" class="input" value="'+esc(cfg.forced_pool_keys||'')+'" placeholder="如 1::gpt-4o,1::gpt-3.5-turbo"></div>',
      '<button class="btn" onclick="saveSettings()">保存设置</button>',
'<div class="card"><h3>🧠 个人人格配置</h3><small style="color:var(--muted);display:block;margin-bottom:12px">让 qtai-sj 回复时带上你设定的人设（按用户独立存储）</small>',
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
      '<div class="form-row"><label><input type="checkbox" id="psMem" checked> 启用记忆系统</label></div>',
      '<button class="btn" onclick="savePersona()">保存人格</button>',
      '</div>',
      '<div class="card"><h3>🔑 修改密码</h3>',
      '<div class="form-row"><label>旧密码</label><input id="chgOld" class="input" type="password" placeholder="输入旧密码"></div>',
      '<div class="form-row"><label>新密码</label><input id="chgNew" class="input" type="password" placeholder="至少6个字符"></div>',
      '<button class="btn" onclick="changePassword()">修改密码</button>',
      '</div>',
      '<div class="card" id="distCard"><h3>💎 分销中心</h3><div style="color:var(--muted);padding:10px;font-size:13px">加载中...</div></div>'
    ].join('');
    // 分销信息
    api('/api/me/distribution').then(function(dr){
      if(dr && dr.code === 0 && dr.data){
        var d = dr.data;
        var dc = $('distCard');
        if(dc) dc.innerHTML = '<h3>💎 分销中心</h3>' +
          '<div class="form-row"><label>我的邀请码</label><div class="addr-line" onclick="copyText(\''+esc(d.inviteCode)+'\')"><b style="font-family:monospace">'+esc(d.inviteCode)+'</b> <span class="copy-tag">📋 复制</span></div></div>' +
          '<div class="form-row"><label>邀请人数</label><div><b style="color:var(--cyan)">'+d.inviteCount+'</b> 人</div></div>' +
          '<div class="form-row"><label>佣金比例</label><div><b style="color:var(--green)">'+d.commissionRate+'%</b>（被邀请人充值时自动返佣到你的余额）</div></div>' +
          '<div class="form-row"><label>我的余额</label><div><b style="color:var(--green)">¥'+d.balance.toFixed(2)+'</b></div></div>' +
          '<div class="form-row"><label>注册链接</label><div class="addr-line" onclick="copyText(location.origin+\'/login\')">' + location.origin + '/login <span class="copy-tag">📋 复制</span></div></div>' +
          '<small style="color:var(--muted)">分享邀请码给朋友，朋友注册时填写你的邀请码，Ta 充值后你将获得 '+d.commissionRate+'% 佣金</small>';
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
    if(r.code === 0){ toast('✅ ' + r.msg, true); } else toast(r.msg, false);
  });
};
window.changePassword = function(){
  var oldPwd = $('chgOld').value;
  var newPwd = $('chgNew').value;
  if(!oldPwd || !newPwd){ toast('请填写旧密码和新密码', false); return; }
  if(newPwd.length < 6){ toast('新密码至少6个字符', false); return; }
  api('/api/auth/change-password', { method:'POST', body: { oldPassword: oldPwd, newPassword: newPwd } }).then(function(r){
    if(r.code === 0){
      toast('✅ ' + r.msg, true);
      setTimeout(function(){ localStorage.removeItem('qt_token'); location.href='/login'; }, 800);
    } else toast(r.msg, false);
  });
};
window.saveSettings = function(){
  api('/api/config', { method:'POST', body: { require_api_key: $('cfgRequireKey').checked?'true':'false', auto_failover: $('cfgFailover').checked?'true':'false', active_model_key: $('cfgActive').value.trim(), forced_pool_keys: $('cfgPool').value.trim() } }).then(function(r){
    if(r.code === 0){ toast('✅ 设置已保存', true); } else toast(r.msg, false);
  });
};
// ===== 用户（可编辑：额度/绑定模型/角色/权限/重置密码） =====
var PERM_OPTS = [
  { id:'provider.manage', label:'🔌 管理服务商（含系统）' },
  { id:'model.manage', label:'🤖 管理模型（含系统）' },
  { id:'system.config', label:'⚙️ 修改系统配置' },
  { id:'keys.manage', label:'🔑 管理API密钥（含系统）' },
  { id:'rules.manage', label:'🛡️ 管理路由规则（含系统）' },
  { id:'users.manage', label:'👥 用户管理' },
  { id:'system.speedtest', label:'⚡ 全局测速/强制池' },
  { id:'data.export', label:'📤 数据导出' }
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
      return '<tr><td>'+esc(u.username)+'</td><td>'+esc(u.displayName)+'</td><td><span class="badge '+(u.role==='admin'?'purple':'blue')+'">'+(u.role==='admin'?'管理员':'用户')+'</span></td><td>'+balTxt+' <button class="btn-ghost" style="padding:1px 8px;font-size:11px" onclick="rechargeUser('+u.id+',\''+esc(u.username)+'\')">充值</button> <button class="btn-ghost" style="padding:1px 8px;font-size:11px;color:var(--red)" onclick="deductUser('+u.id+',\''+esc(u.username)+'\')">扣款</button></td><td>'+quotaText+'</td><td>'+bindTxt+'</td><td style="font-size:11px">'+permTxt+'</td><td><button class="btn-ghost" onclick="editUser('+u.id+')">编辑</button> <button class="btn-ghost" style="color:var(--red)" onclick="delUser('+u.id+')">删除</button></td></tr>';
    }).join('');
    box.innerHTML = [
      '<div class="card"><h3>👥 用户管理</h3><div class="table-wrap"><table><thead><tr><th>用户名</th><th>昵称</th><th>角色</th><th>余额</th><th>额度使用</th><th>绑定模型</th><th>系统权限</th><th>操作</th></tr></thead><tbody>' +
      rows + '<tr><td colspan="8" style="text-align:center;color:var(--muted)">' + (users.length ? '' : '暂无用户') + '</td></tr>' +
      '</tbody></table></div><div style="margin-top:10px;color:var(--muted);font-size:12px">注册页开放注册；可编辑用户角色 / 额度 / 绑定模型 / 系统权限；余额用于按模型价格扣费</div></div>'
    ].join('');
  });
};
// ===== 用户扣款 =====
window.deductUser = function(id, name){
  openModal('扣款 - ' + name, '<div class="form-row"><label>扣款金额（元）</label><input id="dcAmount" class="input" type="number" step="0.01" min="0.01" placeholder="如 5.00"></div>', function(){
    var amount = parseFloat($('dcAmount').value);
    if(!amount || amount <= 0){ toast('请输入有效金额', false); return; }
    api('/api/users/deduct', { method:'POST', body: { id: id, amount: amount } }).then(function(r){
      if(r.code === 0){ toast('✅ ' + r.msg, true); closeModal(); loaders.users(); } else toast(r.msg, false);
    });
  });
};
// ===== 用户充值 =====
window.rechargeUser = function(id, name){
  openModal('充值 - ' + name, '<div class="form-row"><label>充值金额（元）</label><input id="rcAmount" class="input" type="number" step="0.01" min="0.01" placeholder="如 10.00"></div>', function(){
    var amount = parseFloat($('rcAmount').value);
    if(!amount || amount <= 0){ toast('请输入有效金额', false); return; }
    api('/api/users/recharge', { method:'POST', body: { id: id, amount: amount } }).then(function(r){
      if(r.code === 0){ toast('✅ ' + r.msg, true); closeModal(); loaders.users(); } else toast(r.msg, false);
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
        '<div class="form-row"><label>角色</label><select id="euRole" class="input"><option value="user"'+(u.role==='user'?' selected':'')+'>用户</option><option value="admin"'+(u.role==='admin'?' selected':'')+'>管理员</option></select></div>',
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
          if(rr.code === 0){ toast('✅ ' + rr.msg, true); closeModal(); loaders.users(); } else toast(rr.msg, false);
        });
      });
    });
  });
};
window.delUser = function(id){
  if(!confirm('确定删除该用户？')) return;
  api('/api/users/delete', { method:'POST', body: { id: id } }).then(function(r){
    if(r.code === 0){ toast('✅ ' + r.msg, true); loaders.users(); } else toast(r.msg, false);
  });
};
// ===== 启动 =====
api('/api/auth/me').then(function(r){
  if(r.code === 0){
    $('userInfo').textContent = r.data.displayName || r.data.username;
  } else { localStorage.removeItem('qt_token'); location.href='/login'; }
});
switchView('dashboard');
"""
}