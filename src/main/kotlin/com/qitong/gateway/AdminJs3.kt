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
      return '<tr><td>'+(i+1)+'</td><td>'+esc(u.model_key)+'</td><td>'+(u.calls||0)+'</td><td>'+fmtNum(u.total_tokens||0)+'</td><td>'+fmtBytes(u.upload_bytes||0)+'</td><td>'+fmtBytes(u.download_bytes||0)+'</td><td>¥'+(u.cost||0).toFixed(4)+'</td></tr>';
    }).join('');
    // 传输明细（每次调用一条）
    api('/api/usage/recent').then(function(rr){
      var recent = (rr.data || []);
      var rrows = recent.map(function(u){
        var up = u.uploadBytes || 0, down = u.downloadBytes || 0, tt = u.totalTokens || 0;
        return '<tr><td>'+esc(u.modelKey||u.modelName||'')+'</td><td>'+fmtNum(u.promptTokens||0)+'</td><td>'+fmtNum(u.completionTokens||0)+'</td><td>'+fmtNum(tt)+'</td><td>'+fmtBytes(up)+'</td><td>'+fmtBytes(down)+'</td><td>¥'+(u.cost||0).toFixed(4)+'</td><td>'+esc(u.apiKeyLabel||'本地')+'</td><td style="font-size:12px;color:var(--muted)">'+fmtTime(u.createdAt)+'</td></tr>';
      }).join('');
      box.innerHTML = [
        '<div class="grid grid-3">',
          '<div class="stat"><div class="num">'+fmtBytes(s.totalUpload)+'</div><div class="lbl">总上行</div></div>',
          '<div class="stat"><div class="num">'+fmtBytes(s.totalDownload)+'</div><div class="lbl">总下行</div></div>',
          '<div class="stat"><div class="num">'+(s.usage||[]).length+'</div><div class="lbl">模型维度</div></div>',
        '</div>',
        '<div class="card" style="margin-top:14px"><h3>按模型用量汇总</h3><div class="table-wrap"><table><thead><tr><th>#</th><th>模型Key</th><th>调用</th><th>Tokens</th><th>上行</th><th>下行</th><th>费用</th></tr></thead><tbody>' +
        rows + '<tr><td colspan="7" style="text-align:center;color:var(--muted)">' + ((s.usage||[]).length ? '' : '暂无用量') + '</td></tr>' +
        '</tbody></table></div></div>',
        '<div class="card" style="margin-top:14px"><h3>📋 传输明细（每次调用）<span style="font-size:12px;color:var(--muted)">对齐原APP TokenUsage，每条=一次API传输</span></h3><div class="table-wrap"><table><thead><tr><th>模型</th><th>Prompt</th><th>输出</th><th>总Token</th><th>上行</th><th>下行</th><th>费用</th><th>密钥</th><th>时间</th></tr></thead><tbody>' +
        rrows + '<tr><td colspan="9" style="text-align:center;color:var(--muted)">' + (recent.length ? '' : '暂无传输记录，发起API调用后显示') + '</td></tr>' +
        '</tbody></table></div></div>'
      ].join('');
    });
  });
};
// ===== 个人中心（用户+管理员各有自己的） =====
loaders.profile = function(){
  var box = $('view-profile');
  box.innerHTML = '<div style="text-align:center;color:var(--muted);padding:40px">加载中...</div>';
  api('/api/me/profile').then(function(r){
    if(r.code !== 0){ toast(r.msg, false); return; }
    var p = r.data || {};
    var isAdmin = p.role === 'admin';
    box.innerHTML = [
      '<div class="card" style="text-align:center;padding:26px">',
        '<div style="font-size:46px;margin-bottom:8px">' + (isAdmin ? '👑' : '👤') + '</div>',
        '<h2 style="color:var(--text);font-size:18px">' + esc(p.displayName || p.username) + '</h2>',
        '<div><span class="badge ' + (isAdmin ? 'purple' : 'blue') + '">' + (isAdmin ? '管理员' : '普通用户') + '</span> <span style="color:var(--muted);font-size:12px">@' + esc(p.username) + '</span></div>',
      '</div>',
      '<div class="card"><h3>📋 我的信息</h3>',
        '<div class="form-row"><label>用户名</label><div class="input" style="background:rgba(15,23,42,.4)">' + esc(p.username) + '</div></div>',
        '<div class="form-row"><label>昵称</label><input id="pfName" class="input" value="' + esc(p.displayName || '') + '"></div>',
        '<div class="form-row"><label>绑定邮箱（用于提醒通知）</label><input id="pfEmail" class="input" value="' + esc(p.email || '') + '" placeholder="example@qq.com"></div>',
        '<div class="form-row"><label><input type="checkbox" id="pfNotify"' + (p.notifyEnabled ? ' checked' : '') + '> 开启邮件提醒</label><small style="color:var(--muted);display:block;margin-top:4px">余额变动 / 工单回复等发送邮件通知</small></div>',
        '<button class="btn" onclick="saveProfile()">保存个人资料</button>',
      '</div>',
      '<div class="grid grid-2">',
        '<div class="card"><h3>💰 账户</h3><div class="form-row"><label>余额</label><div style="font-size:20px;font-weight:700;color:var(--green)">¥' + (p.balance || 0).toFixed(2) + '</div></div><div class="form-row"><label>累计充值</label><div style="font-size:16px;color:var(--cyan)">¥' + (p.totalRecharge || 0).toFixed(2) + '</div></div></div>',
        '<div class="card"><h3>🎁 邀请</h3><div class="form-row"><label>我的邀请码</label><div class="addr-line" onclick="copyText(\'' + esc(p.inviteCode || '') + '\')"><b style="font-family:monospace">' + esc(p.inviteCode || '-') + '</b> <span class="copy-tag">📋</span></div></div><div class="form-row"><label>注册时间</label><div style="color:var(--muted);font-size:13px">' + new Date(p.createdAt).toLocaleString('zh-CN', {hour12:false}) + '</div></div></div>',
      '</div>'
    ].join('');
  });
};
window.saveProfile = function(){
  var email = $('pfEmail').value.trim();
  if(email && email.indexOf('@') < 0){ toast('邮箱格式不正确', false); return; }
  api('/api/me/profile', { method:'POST', body: { email: email, displayName: $('pfName').value.trim(), notifyEnabled: $('pfNotify').checked ? 'true' : 'false' } }).then(function(r){
    if(r.code === 0){ toast('✅ ' + r.msg, true); loaders.profile(); } else toast(r.msg, false);
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
      (isAdmin ? '<div class="card"><h3>⚙️ 网关设置</h3>' +
      '<div class="form-row"><label><input type="checkbox" id="cfgRequireKey"'+(cfg.require_api_key==='true'?' checked':'')+'> 启用API密钥校验</label><small style="color:var(--muted);display:block;margin-top:4px">开启后本地除外，第三方请求需携带有效密钥</small></div>' +
      '<div class="form-row"><label><input type="checkbox" id="cfgFailover"'+(cfg.auto_failover!=='false'?' checked':'')+'> 启用自动故障转移</label><small style="color:var(--muted);display:block;margin-top:4px">模型失败时自动切换池内下一个可用模型</small></div>' +
      '<div class="form-row"><label>活跃模型Key（qtai-sj 解析目标）</label><input id="cfgActive" class="input" value="'+esc(cfg.active_model_key||'')+'" placeholder="如 1::gpt-4o"></div>' +
      '<div class="form-row"><label>强制故障池（逗号分隔）</label><input id="cfgPool" class="input" value="'+esc(cfg.forced_pool_keys||'')+'" placeholder="如 1::gpt-4o,1::gpt-3.5-turbo"></div>' +
      '<button class="btn" onclick="saveSettings()">保存设置</button></div>' : '') +
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
      '<div class="card" id="distCard"><h3>💎 分销中心</h3><div style="color:var(--muted);padding:10px;font-size:13px">加载中...</div></div>',
      '<div class="card" id="memCard"><h3>🧠 大脑记忆</h3><div style="color:var(--muted);padding:10px;font-size:13px">加载中...</div></div>',
      '<div class="card" id="memCfgCard"><h3>🧠 记忆配置</h3><div style="color:var(--muted);padding:10px;font-size:13px">加载中...</div></div>',
      '<div class="card" id="brainCard"><h3>🧩 qtai-sj 大脑绑定</h3><div style="color:var(--muted);padding:10px;font-size:13px">加载中...</div></div>',
      '<div class="card" id="langCard"><h3>🌐 界面语言</h3><div style="color:var(--muted);padding:10px;font-size:13px">加载中...</div></div>',
      '<div class="card" id="bakCard"><h3>💾 数据备份/恢复</h3><div style="color:var(--muted);padding:10px;font-size:13px">加载中...</div></div>',
      '<div class="card" id="skillCard"><h3>🧰 自定义技能</h3><div style="color:var(--muted);padding:10px;font-size:13px">加载中...</div></div>',
      (isAdmin ? '<div class="card" id="notifyCard"><h3>🔔 通知设置</h3><div style="color:var(--muted);padding:10px;font-size:13px">加载中...</div></div>' : '')
    ].join('');
    // 通知设置（仅管理员）
    if(isAdmin){
      api('/api/notify/config').then(function(nr){
        if(nr && nr.code === 0){
          var n = nr.data || {};
          var nc = $('notifyCard');
          if(nc){
            nc.innerHTML = '<h3>🔔 通知设置（钉钉 / 邮箱）</h3>' +
              '<div class="form-row"><label><input type="checkbox" id="ntDing"'+((n.enable_dingtalk==="true")?' checked':'')+'> 启用钉钉通知</label><small style="color:var(--muted);display:block;margin-top:4px">新用户注册 / 新工单时推送到钉钉群</small></div>' +
              '<div class="form-row"><label>钉钉 Webhook 地址</label><input id="ntWebhook" class="input" value="'+esc(n.dingtalk_webhook||'')+'" placeholder="https://oapi.dingtalk.com/robot/send?access_token=..."></div>' +
              '<hr style="border-color:var(--border);margin:10px 0">' +
              '<div class="form-row"><label><input type="checkbox" id="ntEmail"'+((n.enable_email==="true")?' checked':'')+'> 启用邮箱通知</label></div>' +
              '<div class="form-row"><label>SMTP 服务器</label><input id="ntSmtpHost" class="input" value="'+esc(n.email_smtp_host||'')+'" placeholder="smtp.qq.com"></div>' +
              '<div class="form-row"><label>SMTP 端口（SSL默认465）</label><input id="ntSmtpPort" class="input" value="'+esc(n.email_smtp_port||'465')+'"></div>' +
              '<div class="form-row"><label>邮箱账号</label><input id="ntEmailUser" class="input" value="'+esc(n.email_username||'')+'" placeholder="xxx@qq.com"></div>' +
              '<div class="form-row"><label>邮箱授权码/密码</label><input id="ntEmailPwd" class="input" type="password" value="'+esc(n.email_password||'')+'" placeholder="SMTP 授权码"></div>' +
              '<div class="form-row"><label>收件人（管理员）</label><input id="ntEmailTo" class="input" value="'+esc(n.email_to||'')+'" placeholder="admin@example.com"></div>' +
              '<div class="action-bar"><button class="btn" onclick="saveNotifyCfg()">保存通知设置</button>&nbsp;<button class="btn-ghost" onclick="testNotifyCfg()">📨 发送测试通知</button></div>' +
              '<small style="color:var(--muted)">通知事件：新用户注册、新工单提交（对齐 dingtalk-notification.php）</small>';
          }
        }
      });
    }
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
    // 大脑记忆
    api('/api/memory').then(function(mr){
      if(mr && mr.code === 0){
        var mems = mr.data || [];
        var mc = $('memCard');
        if(mc){
          var rows = mems.map(function(m){
            return '<tr><td>'+esc(m.title||'(无标题)')+'</td><td style="font-size:12px;max-width:260px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap">'+esc(m.content)+'</td><td><span class="badge '+(m.emotion==='happy'?'green':m.emotion==='sad'?'red':'purple')+'">'+esc(m.emotion)+'</span></td><td>'+m.importance+'</td><td>'+new Date(m.timestamp).toLocaleString('zh-CN')+'</td><td><button class="btn-ghost" style="color:var(--red)" onclick="delMemory('+m.id+')">删</button></td></tr>';
          }).join('');
          mc.innerHTML = '<h3>🧠 大脑记忆 <button class="btn-ghost" style="padding:1px 8px;font-size:11px" onclick="addMemory()">＋ 添加</button> <button class="btn-ghost" style="padding:1px 8px;font-size:11px;color:var(--red)" onclick="clearMemories()">清空</button></h3>' +
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
          cc.innerHTML = '<h3>🧠 记忆配置</h3>' +
            '<div class="form-row"><label><input type="checkbox" id="mcEnabled"'+(c.enabled?' checked':'')+'> 启用记忆系统</label></div>' +
            '<div class="form-row"><label><input type="checkbox" id="mcIndependent"'+(c.modelIndependent?' checked':'')+'> 模型独立记忆</label><small style="color:var(--muted);display:block;margin-top:4px">开启后各模型记忆互相隔离，模型间互不干扰（对齐原APP）</small></div>' +
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
          bc.innerHTML = '<h3>🧩 qtai-sj 大脑绑定</h3>' +
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
          lc.innerHTML = '<h3>🌐 界面语言</h3>' +
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
        bc.innerHTML = '<h3>💾 数据备份/恢复</h3>' +
          '<div style="display:flex;gap:8px;flex-wrap:wrap">' +
          '<button class="btn" onclick="exportData()">📤 导出备份</button>' +
          '<button class="btn-ghost" onclick="importData()">📥 导入恢复</button>' +
          '</div>' +
          '<small style="color:var(--muted);display:block;margin-top:6px">导出：服务商/模型/人格/记忆/自定义技能；导入：JSON 文件互传（APP↔线上）</small>';
      }
    });
    // 自定义技能（按用户隔离）
    api('/api/skills').then(function(sr){
      if(sr && sr.code === 0){
        var skills = sr.data || [];
        var sc = $('skillCard');
        if(sc){
          var rows = (skills || []).map(function(s,i){
            return '<tr><td>'+(i+1)+'</td><td>'+esc(s.name||'')+'</td><td>'+esc(s.description||'')+'</td><td>' + ((s.triggers||[]).length ? '<span class="badge purple">'+esc((s.triggers||[]).join('、'))+'</span>' : '') + '</td><td><button class="btn-ghost" style="color:var(--red)" onclick="delSkill('+i+')">删</button></td></tr>';
          }).join('');
          sc.innerHTML = '<h3>🧰 自定义技能 <button class="btn-ghost" style="padding:1px 8px;font-size:11px" onclick="addSkill()">＋ 添加</button> <button class="btn-ghost" style="padding:1px 8px;font-size:11px" onclick="importSkillFromGit()">📥 Git导入</button></h3>' +
            '<div class="table-wrap"><table><thead><tr><th>#</th><th>名称</th><th>描述</th><th>触发词</th><th>操作</th></tr></thead><tbody>' +
            rows + '<tr><td colspan="5" style="text-align:center;color:var(--muted)">' + ((skills||[]).length ? '' : '暂无自定义技能，点击＋添加') + '</td></tr></tbody></table></div>' +
            '<small style="color:var(--muted)">自定义技能会注入大脑，命中触发词时 AI 按你的描述自动执行（按用户独立存储）</small>';
        }
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
    if(r.code === 0){ toast('✅ ' + r.msg, true); } else toast(r.msg, false);
  });
};
// ===== 大脑记忆操作 =====
window.addMemory = function(){
  openModal('添加记忆', '<div class="form-row"><label>标题</label><input id="mmTitle" class="input"></div><div class="form-row"><label>内容</label><textarea id="mmContent" class="input" rows="4"></textarea></div><div class="form-row"><label>情感</label><select id="mmEmotion" class="input"><option value="neutral">中性</option><option value="happy">开心</option><option value="sad">难过</option><option value="surprised">惊讶</option><option value="angry">生气</option></select></div><div class="form-row"><label>重要性(0-10)</label><input id="mmImp" class="input" type="number" min="0" max="10" value="5"></div>', function(){
    var title = $('mmTitle').value.trim();
    var content = $('mmContent').value.trim();
    if(!content){ toast('请输入内容', false); return; }
    api('/api/memory', { method:'POST', body: { title: title, content: content, emotion: $('mmEmotion').value, importance: parseInt($('mmImp').value)||5 } }).then(function(r){
      if(r.code === 0){ toast('✅ 记忆已保存', true); closeModal(); loaders.settings(); } else toast(r.msg, false);
    });
  });
};
window.delMemory = function(id){
  if(!confirm('删除这条记忆？')) return;
  api('/api/memory/' + id, { method:'DELETE' }).then(function(r){
    if(r.code === 0){ toast('✅ 已删除', true); loaders.settings(); } else toast(r.msg, false);
  });
};
window.clearMemories = function(){
  if(!confirm('清空全部记忆？')) return;
  api('/api/memory/clear', { method:'POST' }).then(function(r){
    if(r.code === 0){ toast('✅ 已清空', true); loaders.settings(); } else toast(r.msg, false);
  });
};
window.saveBrain = function(){
  var brain = $('qtBrain').value.trim();
  api('/api/qtai/brain', { method:'POST', body: { brain: brain } }).then(function(r){
    if(r.code === 0){ toast('✅ ' + r.msg, true); } else toast(r.msg, false);
  });
};
window.saveLang = function(){
  var lang = $('langSel').value;
  api('/api/me/language', { method:'POST', body: { language: lang } }).then(function(r){
    if(r.code === 0){ toast('✅ ' + r.msg, true); } else toast(r.msg, false);
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
    if(r.code === 0){ toast('✅ ' + r.msg, true); } else toast(r.msg, false);
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
    if(r.code === 0){ toast('✅ ' + r.msg, true); } else toast(r.msg, false);
  });
};
window.testNotifyCfg = function(){
  api('/api/notify/test', { method:'POST', body: { message: '【綦桐AI网关】测试通知，配置成功！' } }).then(function(r){
    if(r.code === 0){ toast('📨 ' + r.msg, true); } else toast(r.msg, false);
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
      toast('✅ 备份已导出', true);
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
          if(r.code === 0){ toast('✅ ' + r.msg, true); loaders.settings(); } else toast(r.msg, false);
        });
      } catch(e){ toast('❌ 文件格式错误', false); }
    };
    reader.readAsText(f);
  };
  inp.click();
};
// ===== 自定义技能（按用户隔离） =====
window.addSkill = function(){
  openModal('添加自定义技能', '<div class="form-row"><label>技能名称</label><input id="skName" class="input" placeholder="如：查天气"></div><div class="form-row"><label>触发词（逗号分隔）</label><input id="skTrig" class="input" placeholder="如：天气,今天天气"></div><div class="form-row"><label>执行描述</label><textarea id="skDesc" class="input" rows="3" placeholder="告诉大脑这个技能做什么"></textarea></div>', function(){
    var name = $('skName').value.trim();
    var trig = $('skTrig').value.trim();
    var desc = $('skDesc').value.trim();
    if(!name || !desc){ toast('请填写名称和描述', false); return; }
    api('/api/skills', { method:'POST', body: { name: name, description: desc, triggers: trig.split(',').map(function(s){return s.trim();}).filter(Boolean) } }).then(function(r){
      if(r.code === 0){ toast('✅ 技能已添加', true); closeModal(); loaders.settings(); } else toast(r.msg, false);
    });
  });
};
window.delSkill = function(idx){
  if(!confirm('删除该技能？')) return;
  api('/api/skills/' + idx, { method:'DELETE' }).then(function(r){
    if(r.code === 0){ toast('✅ 已删除', true); loaders.settings(); } else toast(r.msg, false);
  });
};
window.importSkillFromGit = function(){
  openModal('Git/URL 导入技能', '<div class="form-row"><label>技能文件 URL</label><input id="skUrl" class="input" placeholder="https://raw.githubusercontent.com/xxx/skills.json 或任意 JSON 技能地址"></div><small style="color:var(--muted)">支持 Git raw 链接或任意返回 JSON 数组的技能地址（每项含 name/description/triggers）</small>', function(){
    var url = $('skUrl').value.trim();
    if(!url){ toast('请输入URL', false); return; }
    toast('⏳ 正在导入...', true);
    api('/api/skills/import', { method:'POST', body: { url: url } }).then(function(r){
      if(r.code === 0){ toast('✅ ' + r.msg, true); closeModal(); loaders.settings(); } else toast(r.msg, false);
    });
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
      return '<tr><td>'+esc(u.username)+'</td><td>'+esc(u.displayName)+'</td><td><span class="badge '+(u.role==='admin'?'purple':'blue')+'">'+(u.role==='admin'?'管理员':'用户')+'</span></td><td>'+balTxt+' <button class="btn-ghost" style="padding:1px 8px;font-size:11px" onclick="rechargeUser('+u.id+',\''+esc(u.username)+'\')">充值</button> <button class="btn-ghost" style="padding:1px 8px;font-size:11px;color:var(--red)" onclick="deductUser('+u.id+',\''+esc(u.username)+'\')">扣款</button></td><td>'+quotaText+'</td><td>'+bindTxt+'</td><td style="font-size:11px">'+permTxt+'</td><td style="font-size:11px;color:var(--muted)">'+(u.email?esc(u.email):'—')+'</td><td><button class="btn-ghost" onclick="editUser('+u.id+')">编辑</button> <button class="btn-ghost" style="color:var(--red)" onclick="delUser('+u.id+')">删除</button></td></tr>';
    }).join('');
    box.innerHTML = [
      '<div class="card"><h3>👥 用户管理</h3><div class="table-wrap"><table><thead><tr><th>用户名</th><th>昵称</th><th>角色</th><th>余额</th><th>额度使用</th><th>绑定模型</th><th>系统权限</th><th>邮箱</th><th>操作</th></tr></thead><tbody>' +
      rows + '<tr><td colspan="9" style="text-align:center;color:var(--muted)">' + (users.length ? '' : '暂无用户') + '</td></tr>' +
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
// ===== 工单中心（用户提交，管理员反馈，聊天式） =====
var currentTicketId = 0;
loaders.tickets = function(){
  var box = $('view-tickets');
  box.innerHTML = '<div class="action-bar"><button class="btn" onclick="openNewTicket()">🎫 提交新工单</button><span style="color:var(--muted);font-size:13px">遇到问题提交工单，管理员会尽快回复</span></div><div class="card" id="ticketListCard"><div style="text-align:center;color:var(--muted);padding:30px">加载中...</div></div>';
  api('/api/tickets').then(function(r){
    if(r.code !== 0){ toast(r.msg, false); return; }
    var list = r.data || [];
    var rows = list.map(function(t){
      var badge = t.status === 'open' ? '<span class="badge green">进行中</span>' : (t.status === 'closed' ? '<span class="badge gray">已关闭</span>' : '<span class="badge blue">'+esc(t.status)+'</span>');
      return '<tr><td><b>'+esc(t.title)+'</b><div style="font-size:12px;color:var(--muted);margin-top:4px;max-width:300px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap">'+esc(t.lastMsg||'')+'</div></td><td>'+badge+'</td><td style="font-size:12px;color:var(--muted)">'+new Date(t.createdAt).toLocaleString('zh-CN',{hour12:false})+'</td><td><button class="btn-ghost" onclick="openTicket('+t.id+')">查看/回复</button> <button class="btn-ghost" style="color:var(--red)" onclick="delTicket('+t.id+')">删除</button></td></tr>';
    }).join('');
    $('ticketListCard').innerHTML = '<h3>🎫 我的工单</h3><div class="table-wrap"><table><thead><tr><th>标题</th><th>状态</th><th>时间</th><th>操作</th></tr></thead><tbody>' +
      rows + '<tr><td colspan="4" style="text-align:center;color:var(--muted)">' + (list.length ? '' : '暂无工单，点击上方按钮提交') + '</td></tr></tbody></table></div>';
  });
};
window.openNewTicket = function(){
  openModal('提交工单', '<div class="form-row"><label>工单标题</label><input id="tkTitle" class="input" placeholder="简要描述问题，如：某个模型不可用"></div>', function(){
    var title = $('tkTitle').value.trim();
    if(!title){ toast('请填写标题', false); return; }
    api('/api/tickets', { method:'POST', body: { title: title } }).then(function(r){
      if(r.code === 0){ toast('✅ 工单已提交', true); closeModal(); loaders.tickets(); } else toast(r.msg, false);
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
    openModal('工单对话', '<div class="chat-box" style="height:340px"><div class="chat-msgs" id="tkMsgs">' + (rows || '<div class="msg-row system"><div class="bubble">暂无消息</div></div>') + '</div><div class="chat-input"><input id="tkInput" class="input" placeholder="输入回复... (Enter发送)" onkeydown="if(event.key===EnterKey)sendTicketMsg()"><button class="btn" onclick="sendTicketMsg()">发送</button></div></div>' + (r.role==='admin' ? '<div style="margin-top:8px"><button class="btn-ghost" onclick="closeTicket('+id+')">🔒 关闭工单</button></div>' : ''), function(){});
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
    if(r.code === 0){ toast('✅ 工单已关闭', true); closeModal(); loaders.tickets(); } else toast(r.msg, false);
  });
};
window.delTicket = function(id){
  if(!confirm('删除该工单？')) return;
  api('/api/tickets/' + id + '/delete', { method:'POST' }).then(function(r){
    if(r.code === 0){ toast('✅ 工单已删除', true); loaders.tickets(); } else toast(r.msg, false);
  });
};
// ===== 全部操作日志（仅管理员） =====
loaders.logs = function(){
  var box = $('view-logs');
  box.innerHTML = '<div class="action-bar"><button class="btn-ghost" style="color:var(--red)" onclick="clearLogs()">🗑 清空日志</button><span style="color:var(--muted);font-size:13px">记录所有用户的关键操作（登录/发布公告/提交工单等）</span></div><div class="card" id="logsCard"><div style="text-align:center;color:var(--muted);padding:30px">加载中...</div></div>';
  api('/api/logs').then(function(r){
    if(r.code !== 0){ toast(r.msg, false); return; }
    var list = r.data || [];
    var rows = list.map(function(l){
      return '<tr><td>'+esc(l.username||'-')+'</td><td><span class="badge blue">'+esc(l.action)+'</span></td><td style="max-width:300px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap">'+esc(l.detail||'')+'</td><td style="font-size:12px;color:var(--muted)">'+esc(l.ip||'')+'</td><td style="font-size:12px;color:var(--muted)">'+new Date(l.createdAt).toLocaleString('zh-CN',{hour12:false})+'</td></tr>';
    }).join('');
    $('logsCard').innerHTML = '<h3>📜 全部操作日志</h3><div class="table-wrap"><table><thead><tr><th>用户</th><th>操作</th><th>详情</th><th>IP</th><th>时间</th></tr></thead><tbody>' +
      rows + '<tr><td colspan="5" style="text-align:center;color:var(--muted)">' + (list.length ? '' : '暂无日志') + '</td></tr></tbody></table></div>';
  });
};
window.clearLogs = function(){
  if(!confirm('清空全部操作日志？')) return;
  api('/api/logs/clear', { method:'POST' }).then(function(r){
    if(r.code === 0){ toast('✅ 已清空', true); loaders.logs(); } else toast(r.msg, false);
  });
};
// ===== 公告管理（仅管理员） =====
loaders.announcements = function(){
  var box = $('view-announcements');
  box.innerHTML = '<div class="action-bar"><button class="btn" onclick="openNewAnnouncement()">📢 发布公告</button><span style="color:var(--muted);font-size:13px">公告将显示在首页顶部</span></div><div class="card" id="annCard"><div style="text-align:center;color:var(--muted);padding:30px">加载中...</div></div>';
  api('/api/announcements').then(function(r){
    if(r.code !== 0){ toast(r.msg, false); return; }
    var list = r.data || [];
    var rows = list.map(function(a){
      return '<tr><td>'+(a.isPinned?'<span class="badge red">📌 置顶</span>':'')+' <b>'+esc(a.title)+'</b></td><td style="max-width:300px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap">'+esc(a.content)+'</td><td style="font-size:12px;color:var(--muted)">'+new Date(a.createdAt).toLocaleString('zh-CN',{hour12:false})+'</td><td><button class="btn-ghost" onclick="editAnnouncement('+a.id+')">编辑</button> <button class="btn-ghost" style="color:var(--red)" onclick="delAnnouncement('+a.id+')">删除</button></td></tr>';
    }).join('');
    $('annCard').innerHTML = '<h3>📢 公告管理</h3><div class="table-wrap"><table><thead><tr><th>标题</th><th>内容</th><th>时间</th><th>操作</th></tr></thead><tbody>' +
      rows + '<tr><td colspan="4" style="text-align:center;color:var(--muted)">' + (list.length ? '' : '暂无公告') + '</td></tr></tbody></table></div>';
  });
};
window.openNewAnnouncement = function(){
  openModal('发布公告', '<div class="form-row"><label>公告标题</label><input id="anTitle" class="input" placeholder="如：服务升级通知"></div><div class="form-row"><label>公告内容</label><textarea id="anContent" class="input" rows="4" placeholder="公告详情"></textarea></div><div class="form-row"><label><input type="checkbox" id="anPinned"> 置顶显示</label></div>', function(){
    var title = $('anTitle').value.trim();
    var content = $('anContent').value.trim();
    if(!title || !content){ toast('请填写标题和内容', false); return; }
    api('/api/announcements', { method:'POST', body: { title: title, content: content, isPinned: $('anPinned').checked ? 'true' : 'false' } }).then(function(r){
      if(r.code === 0){ toast('✅ 公告已发布', true); closeModal(); loaders.announcements(); } else toast(r.msg, false);
    });
  });
};
window.editAnnouncement = function(id){
  var all = null;
  api('/api/announcements').then(function(r){
    var a = (r.data || []).find(function(x){ return x.id === id; });
    if(!a){ toast('公告不存在', false); return; }
    openModal('编辑公告', '<div class="form-row"><label>公告标题</label><input id="anTitle" class="input" value="'+esc(a.title)+'"></div><div class="form-row"><label>公告内容</label><textarea id="anContent" class="input" rows="4">'+esc(a.content)+'</textarea></div><div class="form-row"><label><input type="checkbox" id="anPinned"'+(a.isPinned?' checked':'')+'> 置顶显示</label></div>', function(){
      var title = $('anTitle').value.trim();
      var content = $('anContent').value.trim();
      if(!title || !content){ toast('请填写标题和内容', false); return; }
      api('/api/announcements/update', { method:'POST', body: { id: id, title: title, content: content, isPinned: $('anPinned').checked ? 'true' : 'false' } }).then(function(rr){
        if(rr.code === 0){ toast('✅ 公告已更新', true); closeModal(); loaders.announcements(); } else toast(rr.msg, false);
      });
    });
  });
};
window.delAnnouncement = function(id){
  if(!confirm('删除该公告？')) return;
  api('/api/announcements/delete', { method:'POST', body: { id: id } }).then(function(r){
    if(r.code === 0){ toast('✅ 公告已删除', true); loaders.announcements(); } else toast(r.msg, false);
  });
};
// ===== 关于我们（对齐原APP AboutScreen） =====
loaders.about = function(){
  var box = $('view-about');
  var ver = '3.18.22-16';
  box.innerHTML = [
    '<div class="card" style="text-align:center;padding:30px">',
      '<div style="font-size:46px;margin-bottom:10px">⚡</div>',
      '<h2 style="font-size:20px;color:var(--text)">綦桐AI网关</h2>',
      '<p style="color:var(--cyan);margin:6px 0">Docker Server v' + ver + '</p>',
      '<p style="color:var(--muted);font-size:13px">AI 网关管理工具 · 多租户 · 商业化 · 公益站</p>',
      '<hr style="border-color:var(--border);margin:16px 0">',
      '<div style="text-align:left;font-size:13px;line-height:2;color:var(--text)">',
        '<div><b>📱 应用信息</b></div>',
        '<div>名称：綦桐AI网关 Docker版</div>',
        '<div>版本：v' + ver + '</div>',
        '<div>协议：Apache 2.0 开源</div>',
        '<div style="margin-top:10px"><b>✨ 核心功能</b></div>',
        '<div>· 多租户权限管控 / API密钥私有化</div>',
        '<div>· 商业化：余额/充值/扣款/模型定价</div>',
        '<div>· 三指标测速（TTFT/TPS/总耗时）+ 故障转移</div>',
        '<div>· AI大脑记忆 / 人格配置 / 技能系统</div>',
        '<div>· 分销返佣 / 公告 / 工单 / 操作日志</div>',
        '<div style="margin-top:10px"><b>💬 联系我们</b></div>',
        '<div>GitHub：github.com/qtgf520/Docker-qitong-ai-gateway</div>',
      '</div>',
    '</div>'
  ].join('');
};
// ===== 启动 =====
api('/api/auth/me').then(function(r){
  if(r.code === 0){
    var me = r.data;
    $('userInfo').textContent = me.displayName || me.username;
    var isAdmin = me.role === 'admin';
    // 管理员功能隐藏：普通用户看不到 admin-only 菜单
    var adminItems = document.querySelectorAll('.admin-only');
    for(var i=0;i<adminItems.length;i++){ adminItems[i].style.display = isAdmin ? '' : 'none'; }
    // 工单中心：管理员看到全部工单（含管理回复）
  } else { localStorage.removeItem('qt_token'); location.href='/login'; }
});
switchView('dashboard');
"""
}