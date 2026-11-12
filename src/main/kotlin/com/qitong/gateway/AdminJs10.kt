package com.qitong.gateway

/**
 * ★ v1.124 系统监控 + 备份中心 前端
 */
object AdminJs10 {
    fun js(): String = """

// ===== 模型价格表（全部模型默认价 + 管理员可维护自定义价，元/百万token） =====
loaders.prices = function(){
 var box = $('view-prices');
 if(!box) return;
 box.innerHTML = '<div class="card"><h3>💰 模型默认价格表</h3><div style="color:var(--muted);padding:10px;font-size:13px">加载中…</div></div>';
 api('/api/prices').then(function(r){
  if(r.code !== 0){ box.innerHTML = '<div class="card"><h3>💰 模型默认价格表</h3><div style="color:var(--red);padding:10px">加载失败</div></div>'; return; }
  var d = r.data || {};
  var tbl = d.table || {};
  var isAdmin = !!d.isAdmin;
  var rows = Object.keys(tbl).map(function(mid){
   var p = tbl[mid] || {};
   var isCustom = p.custom;
   var ops = '';
   if(isAdmin){
    ops = '<td style="white-space:nowrap">' +
     (isCustom ? '<button class="btn-ghost btn-sm" onclick="editPrice(\'' + esc(mid) + '\',' + p.input + ',' + p.output + ')">编辑</button> <button class="btn-ghost btn-sm" style="color:var(--red)" onclick="delPrice(\'' + esc(mid) + '\')">删</button>' : '<button class="btn-ghost btn-sm" onclick="editPrice(\'' + esc(mid) + '\',' + p.input + ',' + p.output + ')">设自定义</button>') +
     '</td>';
   }
   return '<tr>' +
    '<td style="font-family:ui-monospace,monospace">'+esc(mid)+'</td>' +
    '<td>¥'+p.input+'</td>' +
    '<td>¥'+p.output+'</td>' +
    '<td>'+(isCustom?'<span class="badge cyan">自定义</span>':(p.default?'<span class="badge gray">默认价</span>':'<span class="badge green">已收录</span>'))+'</td>' +
    ops +
   '</tr>';
  }).join('');
  var adminBar = isAdmin ?
   '<div class="action-bar" style="margin-bottom:12px">' +
    '<button class="btn" onclick="addPrice()">+ 添加自定义价格</button>' +
    '<button class="btn-ghost" style="color:var(--red)" onclick="clearAllPrices()">清空全部自定义</button>' +
    '<span style="color:var(--muted);font-size:12px">自定义价格全局生效；输入价填 0 = 该模型免费（公益）；未设置才走模型单价/内置价格表</span>' +
   '</div>' : '';
  var html = adminBar +
   '<div class="grid grid-3" style="margin-bottom:14px">' +
    '<div class="stat"><div class="num" style="font-size:20px">¥'+d.defaultPrice+'</div><div class="lbl">默认价（未收录模型，元/百万token 输入）</div></div>' +
    '<div class="stat"><div class="num" style="font-size:20px">'+d.outputMultiplier+'x</div><div class="lbl">输出价 = 输入价 × 倍数</div></div>' +
    '<div class="stat"><div class="num" style="font-size:20px">'+Object.keys(tbl).length+' 款</div><div class="lbl">已收录模型</div></div>' +
   '</div>' +
   '<div class="card"><h3>全部模型价格（按输入/输出计费）</h3><div class="table-wrap"><table><thead><tr><th>模型</th><th>输入价（元/百万token）</th><th>输出价（元/百万token）</th><th>状态</th>'+(isAdmin?'<th>操作</th>':'')+'</tr></thead><tbody>' +
    (rows || '<tr><td colspan="'+(isAdmin?'5':'4')+'" style="text-align:center;color:var(--muted)">价格表为空</td></tr>') +
   '</tbody></table></div>' +
'<div style="font-size:11px;color:var(--muted);margin-top:8px">💰 价格单位：元 / 百万 Token（输入价）。模型可单独设置自定义单价（编辑模型时设置）。<b style="color:var(--cyan)">自定义价格全局生效：填 0 = 该模型免费（公益），表内没设置的模型才按模型单价 → 内置默认价计费。</b>💸 消费分佣：下级/别人消费付费模型时，上级（邀请人）与模型归属者按佣金比例自动获得分成（余额账单可见）。</div></div>';
   box.innerHTML = html;
   // ★ v1.166 模型映射区（外部请求名 -> 内部模型有序列表，按序故障转移）
   if(isAdmin){
    api('/api/model-mappings').then(function(mr){
     var mapBox = document.createElement('div');
     mapBox.style.marginTop = '14px';
     var list = mr.data || [];
     var rows2 = list.map(function(mp){
      return '<tr>' +
       '<td style="font-family:ui-monospace,monospace">'+esc(mp.alias)+'</td>' +
       '<td style="font-family:ui-monospace,monospace;font-size:12px;word-break:break-all">'+esc(mp.targets)+'</td>' +
       '<td>'+(mp.enabled?'<span class="badge green">启用</span>':'<span class="badge gray">停用</span>')+'</td>' +
       '<td style="white-space:nowrap">' +
        '<button class="btn-ghost btn-sm" onclick="editMapping(\''+esc(mp.alias)+'\',\''+esc(mp.targets)+'\','+(mp.enabled?'true':'false')+')">编辑</button> ' +
        '<button class="btn-ghost btn-sm" style="color:var(--red)" onclick="delMapping(\''+esc(mp.alias)+'\')">删</button></td></tr>';
     }).join('');
     mapBox.innerHTML = '<div class="card" style="margin-top:14px"><h3>🧭 模型映射 <span style="font-size:12px;color:var(--muted)">外部请求名 → 内部模型列表（按序故障转移，如 alias=全部模型 → targets=模型Key1,模型Key2,...）</span></h3>' +
      '<div class="action-bar" style="margin-bottom:8px"><button class="btn" onclick="addMapping()">+ 添加映射</button>' +
      '<button class="btn-ghost" style="color:var(--red)" onclick="clearMappings()">清空全部</button></div>' +
      '<div class="table-wrap"><table><thead><tr><th>外部名</th><th>目标模型列表（逗号分隔）</th><th>状态</th><th>操作</th></tr></thead><tbody>' +
      (rows2 || '<tr><td colspan="4" style="text-align:center;color:var(--muted)">暂无映射，添加后外部客户端可用「映射名」当模型名请求，网关自动按序路由到目标模型并故障转移</td></tr>') +
      '</tbody></table></div>' +
      '<div style="font-size:11px;color:var(--muted);margin-top:6px">💡 例：映射名填 <b>fast-ai</b>，目标填 <b>deepseek-chat, kimi-k2.6, qwen-max</b> → 客户端请求 model=fast-ai 时，网关先试 deepseek-chat，失败自动切 kimi-k2.6，再失败切 qwen-max</div></div>';
     box.appendChild(mapBox);
     // ★ v1.166 计费规则区（条件：模型/角色/时段 → 覆盖价或倍率）
     api('/api/pricing-rules').then(function(pr){
      var ruleBox = document.createElement('div');
      ruleBox.style.marginTop = '14px';
      var plist = pr.data || [];
      var prows = plist.map(function(rl){
       return '<tr>' +
        '<td>'+esc(rl.name)+'</td>' +
        '<td style="font-size:12px">'+esc(rl.modelPattern||'-')+'</td>' +
        '<td style="font-size:12px">'+esc(rl.role||'-')+'</td>' +
        '<td style="font-size:12px">'+esc(rl.timeRange||'-')+'</td>' +
        '<td>'+(rl.price>0?'¥'+rl.price:(rl.multiplier!==1.0?'×'+rl.multiplier:'-'))+'</td>' +
        '<td>'+(rl.enabled?'<span class="badge green">启用</span>':'<span class="badge gray">停用</span>')+'</td>' +
        '<td style="white-space:nowrap"><button class="btn-ghost btn-sm" onclick="editRule('+rl.id+',\''+esc(rl.name)+'\',\''+esc(rl.modelPattern)+'\',\''+esc(rl.role)+'\',\''+esc(rl.timeRange)+'\','+rl.price+','+rl.multiplier+','+(rl.enabled?'true':'false')+')">编辑</button> ' +
        '<button class="btn-ghost btn-sm" style="color:var(--red)" onclick="delRule('+rl.id+')">删</button></td></tr>';
      }).join('');
      ruleBox.innerHTML = '<div class="card" style="margin-top:14px"><h3>⚖️ 计费规则（表达式计费） <span style="font-size:12px;color:var(--muted)">按 模型/角色/时段 差异化定价</span></h3>' +
       '<div class="action-bar" style="margin-bottom:8px"><button class="btn" onclick="addRule()">+ 添加规则</button>' +
       '<button class="btn-ghost" style="color:var(--red)" onclick="clearRules()">清空全部</button></div>' +
       '<div class="table-wrap"><table><thead><tr><th>规则名</th><th>模型匹配</th><th>角色</th><th>时段(HH:mm-HH:mm)</th><th>覆盖价/倍率</th><th>状态</th><th>操作</th></tr></thead><tbody>' +
       (prows || '<tr><td colspan="7" style="text-align:center;color:var(--muted)">暂无计费规则。例：晚高峰 20:00-23:00 模型 deepseek* ×1.5；或 agent 角色所有模型 ×0.8</td></tr>') +
       '</tbody></table></div>' +
       '<div style="font-size:11px;color:var(--muted);margin-top:6px">💡 命中规则时：覆盖价(price>0)优先，否则按倍率；条件空=不限。计费优先级：计费规则 → 自定义价格表 → 模型单价 → 内置表</div></div>';
      box.appendChild(ruleBox);
      // ★ v1.166 渠道分组区（用户选组不选具体模型）
      api('/api/channel-groups').then(function(cgr){
       var grpBox = document.createElement('div');
       grpBox.style.marginTop = '14px';
       var groups = (cgr.data && cgr.data.groups) || [];
       var gmodels = (cgr.data && cgr.data.models) || [];
       var grow = groups.map(function(g){
        var cnt = gmodels.filter(function(m){ return m.channelGroup === g.name; }).length;
        return '<tr>' +
         '<td style="font-family:ui-monospace,monospace"><b>'+esc(g.name)+'</b></td>' +
         '<td style="font-size:12px">'+esc(g.description||'-')+'</td>' +
         '<td style="font-size:12px">'+cnt+' 个模型</td>' +
         '<td style="white-space:nowrap"><button class="btn-ghost btn-sm" onclick="delGroup(\''+esc(g.name)+'\')">删</button></td></tr>';
       }).join('');
       // 模型归组下拉（每行一个选择器）
       var gmRow = gmodels.map(function(m){
        var opts = '<option value="">(不分组)</option>' + groups.map(function(g){ return '<option value="'+esc(g.name)+'"'+(m.channelGroup===g.name?' selected':'')+'>'+esc(g.name)+'</option>'; }).join('');
        return '<tr><td style="font-size:12px;font-family:ui-monospace,monospace">'+esc(m.modelId)+'</td><td><select class="input" style="padding:3px 8px;font-size:12px" onchange="setModelGroup('+m.id+',this.value)">'+opts+'</select></td></tr>';
       }).join('');
       grpBox.innerHTML = '<div class="card" style="margin-top:14px"><h3>🔀 渠道分组 <span style="font-size:12px;color:var(--muted)">用户请求 model=组名 时，只在该组内自动选模型</span></h3>' +
        '<div class="action-bar" style="margin-bottom:8px"><button class="btn" onclick="addGroup()">+ 添加分组</button></div>' +
        '<div class="table-wrap"><table><thead><tr><th>组名</th><th>描述</th><th>模型数</th><th>操作</th></tr></thead><tbody>' +
        (grow || '<tr><td colspan="4" style="text-align:center;color:var(--muted)">暂无分组。例：fast（低延迟模型）/ cheap（便宜模型）/ stable（稳定模型）</td></tr>') +
        '</tbody></table></div>' +
        '<h3 style="margin-top:10px">模型归组（选择后自动保存）</h3>' +
        '<div style="max-height:240px;overflow-y:auto"><table class="tb"><thead><tr><th>模型</th><th>所属分组</th></tr></thead><tbody>' +
        (gmRow || '<tr><td colspan="2" style="text-align:center;color:var(--muted)">暂无模型</td></tr>') +
        '</tbody></table></div>' +
        '<div style="font-size:11px;color:var(--muted);margin-top:6px">💡 客户端请求 <b>model=fast</b> → 网关只在 fast 组的模型里按权重/健康自动选择并故障转移；qtai-sj 配组名也能按组选</div></div>';
       box.appendChild(grpBox);
      });
     });
    });
   }
  });
 };
// ★ v1.166 价格表管理：添加/编辑/删除（仅管理员）
window.addPrice = function(){
 openModal('添加自定义价格', '<div class="form-row"><label>模型ID *</label><input id="prModel" class="input" placeholder="如 deepseek-v4-flash"></div>' +
  '<div class="form-row"><label>输入价（元/百万token）*</label><input id="prIn" class="input" type="number" step="0.01" min="0" value="2"></div>' +
  '<div class="form-row"><label>输出价（元/百万token，空=输入价×2）</label><input id="prOut" class="input" type="number" step="0.01" min="0" placeholder="留空自动×2"></div>', function(){
  var mid = ($('prModel').value||'').trim();
  var inP = parseFloat($('prIn').value)||0;
  var outP = $('prOut').value ? (parseFloat($('prOut').value)||0) : inP*2;
  if(!mid){ toast('请填写模型ID', false); return; }
  api('/api/prices', { method:'POST', body: { modelId: mid, inputPrice: inP, outputPrice: outP } }).then(function(r){
   toast(r.msg, r.code === 0);
   if(r.code === 0){ closeModal(); loaders.prices(); }
  });
 });
};
window.editPrice = function(mid, inP, outP){
 openModal('编辑价格：'+esc(mid), '<div class="form-row"><label>模型ID</label><input class="input" value="'+esc(mid)+'" disabled></div>' +
  '<div class="form-row"><label>输入价（元/百万token）</label><input id="prIn" class="input" type="number" step="0.01" min="0" value="'+inP+'"></div>' +
  '<div class="form-row"><label>输出价（元/百万token）</label><input id="prOut" class="input" type="number" step="0.01" min="0" value="'+outP+'"></div>', function(){
  var inP2 = parseFloat($('prIn').value)||0;
  var outP2 = parseFloat($('prOut').value)||0;
  api('/api/prices', { method:'POST', body: { modelId: mid, inputPrice: inP2, outputPrice: outP2 } }).then(function(r){
   toast(r.msg, r.code === 0);
   if(r.code === 0){ closeModal(); loaders.prices(); }
  });
 });
};
window.delPrice = function(mid){
 if(!confirm('确定删除「'+mid+'」的自定义价格？（删除后回退内置价格）')) return;
 api('/api/prices/' + encodeURIComponent(mid), { method:'DELETE' }).then(function(r){
  toast(r.msg, r.code === 0);
  if(r.code === 0) loaders.prices();
 });
};
window.clearAllPrices = function(){
  if(!confirm('确定清空全部自定义价格？（恢复为内置价格表）')) return;
  api('/api/prices/clear', { method:'POST' }).then(function(r){
   toast(r.msg, r.code === 0);
   if(r.code === 0) loaders.prices();
  });
};
// ★ v1.166 模型映射管理（外部请求名 -> 内部模型列表按序路由）
window.addMapping = function(){
 openModal('添加模型映射', '<div class="form-row"><label>外部模型名 *（客户端请求时用这个当 model）</label><input id="mpAlias" class="input" placeholder="如 fast-ai"></div>' +
  '<div class="form-row"><label>目标模型列表 *（逗号分隔，按顺序故障转移）</label><input id="mpTargets" class="input" placeholder="如 deepseek-chat, kimi-k2.6, qwen-max"></div>', function(){
  var alias = ($('mpAlias').value||'').trim();
  var tgts = ($('mpTargets').value||'').trim();
  if(!alias){ toast('请填写外部模型名', false); return; }
  if(!tgts){ toast('请填写目标模型列表', false); return; }
  api('/api/model-mappings', { method:'POST', body: { alias: alias, targets: tgts, enabled: true } }).then(function(r){
   toast(r.msg, r.code === 0);
   if(r.code === 0){ closeModal(); loaders.prices(); }
  });
 });
};
window.editMapping = function(alias, tgts, enabled){
 openModal('编辑映射：'+esc(alias), '<div class="form-row"><label>外部模型名</label><input class="input" value="'+esc(alias)+'" disabled></div>' +
  '<div class="form-row"><label>目标模型列表（逗号分隔）</label><input id="mpTargets" class="input" value="'+esc(tgts||'')+'"></div>' +
  '<div class="form-row"><label><input type="checkbox" id="mpEnabled"'+(enabled?' checked':'')+'>启用</label></div>', function(){
  var t2 = ($('mpTargets').value||'').trim();
  var en = $('mpEnabled') ? $('mpEnabled').checked : true;
  if(!t2){ toast('请填写目标模型列表', false); return; }
  api('/api/model-mappings', { method:'POST', body: { alias: alias, targets: t2, enabled: en } }).then(function(r){
   toast(r.msg, r.code === 0);
   if(r.code === 0){ closeModal(); loaders.prices(); }
  });
 });
};
window.delMapping = function(alias){
 if(!confirm('确定删除映射「'+esc(alias)+'」？')) return;
 api('/api/model-mappings/' + encodeURIComponent(alias), { method:'DELETE' }).then(function(r){
  toast(r.msg, r.code === 0);
  if(r.code === 0) loaders.prices();
 });
};
window.clearMappings = function(){
 if(!confirm('确定清空全部模型映射？')) return;
 api('/api/model-mappings/clear', { method:'POST' }).then(function(r){
  toast(r.msg, r.code === 0);
  if(r.code === 0) loaders.prices();
 });
};
// ★ v1.166 计费规则管理
window.addRule = function(){
 openModal('添加计费规则', '<div class="form-row"><label>规则名 *</label><input id="rlName" class="input" placeholder="如 晚高峰加价"></div>' +
  '<div class="form-row"><label>模型匹配（空=全部，支持模糊如 deepseek）</label><input id="rlModel" class="input" placeholder="如 deepseek / gpt-4 / 留空全部"></div>' +
  '<div class="form-row"><label>角色（空=全部，admin/agent/user）</label><input id="rlRole" class="input" placeholder="如 agent / 留空全部"></div>' +
  '<div class="form-row"><label>时段（HH:mm-HH:mm，空=全天）</label><input id="rlTime" class="input" placeholder="如 20:00-23:00 / 留空全天"></div>' +
  '<div class="form-row"><label>覆盖价（元/百万token，0=不用覆盖价）</label><input id="rlPrice" class="input" type="number" step="0.1" min="0" value="0"></div>' +
  '<div class="form-row"><label>倍率（如 1.5=加价50%，0.8=8折）</label><input id="rlMul" class="input" type="number" step="0.1" value="1"></div>', function(){
  var nm = ($('rlName').value||'').trim();
  if(!nm){ toast('请填写规则名', false); return; }
  api('/api/pricing-rules', { method:'POST', body: {
   name: nm, modelPattern: ($('rlModel').value||'').trim(), role: ($('rlRole').value||'').trim(),
   timeRange: ($('rlTime').value||'').trim(), price: parseFloat($('rlPrice').value)||0,
   multiplier: parseFloat($('rlMul').value)||1, enabled: true
  }}).then(function(r){
   toast(r.msg, r.code === 0);
   if(r.code === 0){ closeModal(); loaders.prices(); }
  });
 });
};
window.editRule = function(id, nm, mp, rl, tr, price, mul, en){
 openModal('编辑计费规则', '<div class="form-row"><label>规则名</label><input id="rlName" class="input" value="'+esc(nm)+'"></div>' +
  '<div class="form-row"><label>模型匹配</label><input id="rlModel" class="input" value="'+esc(mp||'')+'"></div>' +
  '<div class="form-row"><label>角色</label><input id="rlRole" class="input" value="'+esc(rl||'')+'"></div>' +
  '<div class="form-row"><label>时段</label><input id="rlTime" class="input" value="'+esc(tr||'')+'"></div>' +
  '<div class="form-row"><label>覆盖价</label><input id="rlPrice" class="input" type="number" step="0.1" min="0" value="'+(price>0?price:0)+'"></div>' +
  '<div class="form-row"><label>倍率</label><input id="rlMul" class="input" type="number" step="0.1" value="'+(mul||1)+'"></div>' +
  '<div class="form-row"><label><input type="checkbox" id="rlEnabled"'+(en?' checked':'')+'>启用</label></div>', function(){
  var nm2 = ($('rlName').value||'').trim();
  if(!nm2){ toast('请填写规则名', false); return; }
  api('/api/pricing-rules', { method:'POST', body: {
   id: id, name: nm2, modelPattern: ($('rlModel').value||'').trim(), role: ($('rlRole').value||'').trim(),
   timeRange: ($('rlTime').value||'').trim(), price: parseFloat($('rlPrice').value)||0,
   multiplier: parseFloat($('rlMul').value)||1, enabled: $('rlEnabled')?$('rlEnabled').checked:true
  }}).then(function(r){
   toast(r.msg, r.code === 0);
   if(r.code === 0){ closeModal(); loaders.prices(); }
  });
 });
};
window.delRule = function(id){
 if(!confirm('确定删除该计费规则？')) return;
 api('/api/pricing-rules/'+id, { method:'DELETE' }).then(function(r){
  toast(r.msg, r.code === 0);
  if(r.code === 0) loaders.prices();
 });
};
window.clearRules = function(){
 if(!confirm('确定清空全部计费规则？')) return;
 api('/api/pricing-rules/clear', { method:'POST' }).then(function(r){
  toast(r.msg, r.code === 0);
  if(r.code === 0) loaders.prices();
 });
};
// ★ v1.166 渠道分组管理
window.addGroup = function(){
 openModal('添加渠道分组', '<div class="form-row"><label>组名 *（客户端请求时用这个当 model）</label><input id="cgName" class="input" placeholder="如 fast / cheap / stable"></div>' +
  '<div class="form-row"><label>描述</label><input id="cgDesc" class="input" placeholder="如 低延迟优先"></div>', function(){
  var nm = ($('cgName').value||'').trim();
  if(!nm){ toast('请填写组名', false); return; }
  api('/api/channel-groups', { method:'POST', body: { name: nm, description: ($('cgDesc').value||'').trim() } }).then(function(r){
   toast(r.msg, r.code === 0);
   if(r.code === 0){ closeModal(); loaders.prices(); }
  });
 });
};
window.delGroup = function(name){
 if(!confirm('确定删除分组「'+esc(name)+'」？（组内模型自动取消分组）')) return;
 api('/api/channel-groups/' + encodeURIComponent(name), { method:'DELETE' }).then(function(r){
  toast(r.msg, r.code === 0);
  if(r.code === 0) loaders.prices();
 });
};
window.setModelGroup = function(id, group){
 api('/api/models/'+id+'/group', { method:'POST', body: { group: group } }).then(function(r){
  toast(r.msg, r.code === 0);
  if(r.code !== 0) loaders.prices();
 });
};
// ===== 备份中心（一键备份/恢复，仅管理员） =====
loaders.backups = function(){
 var box = $('view-backups');
 if(!box) return;
 box.innerHTML =
  '<div class="action-bar">' +
   '<button class="btn" onclick="doBackup()">💾 立即备份</button>' +
   '<button class="btn-ghost" onclick="loadBackups()">🔄 刷新</button>' +
   '<span style="color:var(--muted);font-size:13px">SQLite 在线一致性备份（不中断服务），默认保留最近 10 份</span>' +
  '</div>' +
  '<div class="grid grid-3" style="margin-bottom:14px">' +
   '<div class="stat"><div class="num" style="font-size:20px" id="bkCount">—</div><div class="lbl">备份总数</div></div>' +
   '<div class="stat"><div class="num" style="font-size:20px" id="bkSize">—</div><div class="lbl">总大小</div></div>' +
   '<div class="stat"><div class="num" style="font-size:20px;color:var(--green)">10 份</div><div class="lbl">自动保留上限</div></div>' +
  '</div>' +
  '<div class="card"><h3>备份列表</h3><div id="bkList" style="color:var(--muted);padding:10px">加载中…</div></div>';
 loadBackups();
};
window.doBackup = function(){
 api('/api/backups', {method:'POST'}).then(function(r){
  toast(r.msg || '备份完成', r.code===0);
  if(r.code===0) loadBackups();
 });
};
function loadBackups(){
 var el = $('bkList'); if(!el) return;
 api('/api/backups').then(function(r){
  if(r.code !== 0){ el.innerHTML = '<div style="color:var(--red)">加载失败</div>'; return; }
  var list = (r.data && r.data.list) || [];
  window._bkStore = list;
  var cntEl = $('bkCount'); if(cntEl) cntEl.textContent = list.length;
  var sizeEl = $('bkSize');
  if(sizeEl){
   var tot = 0;
   list.forEach(function(b){ tot += parseFloat(b.sizeMB) || 0; });
   sizeEl.textContent = tot.toFixed(2) + ' MB';
  }
  if(!list.length){ el.innerHTML = '<div style="color:var(--muted);padding:20px;text-align:center">暂无备份，点击「立即备份」创建第一份</div>'; return; }
  var rows = list.map(function(b, bi){
   var t = new Date(b.time);
   var ts = t.getFullYear()+'-'+String(t.getMonth()+1).padStart(2,'0')+'-'+String(t.getDate()).padStart(2,'0')+' '+String(t.getHours()).padStart(2,'0')+':'+String(t.getMinutes()).padStart(2,'0');
   var isLatest = bi === 0;
   return '<tr style="'+(isLatest?'background:rgba(var(--primary-rgb),.06)':'')+'">' +
    '<td style="font-family:ui-monospace,monospace">🗄 '+esc(b.name)+(isLatest?' <span class="badge green">最新</span>':'')+'</td>' +
    '<td>'+b.sizeMB+' MB</td><td>'+ts+'</td>' +
    '<td><button class="btn-ghost btn-sm" style="color:var(--red)" onclick="restoreBackup('+bi+')">↩ 恢复</button></td></tr>';
  }).join('');
  el.innerHTML = '<div class="table-wrap"><table><thead><tr><th>备份文件</th><th>大小</th><th>时间</th><th>操作</th></tr></thead><tbody>'+rows+'</tbody></table></div>' +
   '<div style="font-size:11px;color:var(--muted);margin-top:8px">⚠️ 恢复会覆盖当前数据库，需重启容器生效。操作前建议先执行一次新备份。</div>';
 });
}
window.restoreBackup = function(i){
 var b = (window._bkStore||[])[i];
 if(!b || !b.name) return;
 var name = b.name;
 if(!confirm('确认恢复备份 '+name+' ？\n此操作将覆盖当前数据库，重启后生效。')) return;
 api('/api/backups/restore', {method:'POST', body:{name:name}}).then(function(r){
  toast(r.msg || '已恢复', r.code===0);
  if(r.code===0) loadBackups();
 });
};

// ===== ★ v1.193 微信公众号管理页（多公众号 + 关键词 + webhook 地址 + 日志） =====
var mpCurBot = 0;
loaders.mp = function(){
 var box = $('view-mp');
 box.innerHTML = '<div class="card"><h3>📢 微信公众号 <span style="font-size:12px;color:var(--muted)">webhook 回调模式 · 多公众号管理 · 关键词回答 + AI 智能回答</span></h3>' +
  '<div class="action-bar"><button class="btn" onclick="mpOpenEdit(0)">+ 添加公众号</button><span id="mpWebHint" style="font-size:12px;color:var(--muted)">加载中…</span></div>' +
  '<div id="mpList" style="margin-top:12px"><div style="color:var(--muted);padding:16px">加载中…</div></div></div>' +
  '<div class="card" id="mpDetail" style="display:none"><h3 id="mpDetailTitle">公众号详情</h3><div id="mpDetailBody"></div></div>';
 api('/api/mp/bots').then(function(r){
  var list = r.data || [];
  var el = $('mpList'); if(!el) return;
  if(!list.length){ el.innerHTML = '<div style="color:var(--muted)">暂无公众号。点击「+ 添加公众号」填写 AppID/AppSecret/Token，然后在微信公众平台「服务器配置」填入下方 URL 即完成对接。</div>'; return; }
  var host = location.origin;
  var rows = list.map(function(b){
   var url = host + '/mp/webhook/' + b.id;
   return '<tr>' +
    '<td><b>'+esc(b.name)+'</b><div style="font-size:11px;color:var(--muted)">'+esc(b.appid)+'</div></td>' +
    '<td>'+(b.enabled?'<span class="badge green">启用</span>':'<span class="badge gray">停用</span>')+'</td>' +
    '<td>'+(b.keywordEnabled?'<span class="badge blue">关键词</span>':'<span class="badge gray">关</span>')+' '+(b.aiEnabled?'<span class="badge cyan">AI</span>':'<span class="badge gray">off</span>')+'</td>' +
    '<td style="font-size:12px;color:var(--muted)">'+esc(b.aiModel||'qtai-sj')+'</td>' +
    '<td style="font-size:11px" title="'+esc(url)+'"><code style="word-break:break-all">'+esc(url)+'</code></td>' +
    '<td style="white-space:nowrap">' +
     '<button class="btn-ghost btn-sm" onclick="mpShow('+b.id+',\''+esc(b.name)+'\')">详情</button> ' +
     '<button class="btn-ghost btn-sm" onclick="mpOpenEdit('+b.id+')">编辑</button> ' +
     '<button class="btn-ghost btn-sm" style="color:var(--red)" onclick="mpDel('+b.id+')">删</button></td></tr>';
  }).join('');
  el.innerHTML = '<div class="table-wrap"><table><thead><tr><th>公众号</th><th>状态</th><th>能力</th><th>AI模型</th><th>Webhook 地址（微信公众平台填这个）</th><th>操作</th></tr></thead><tbody>' + rows + '</tbody></table></div>' +
   '<div style="font-size:11px;color:var(--muted);margin-top:8px">📌 配置步骤：① 填好 AppID/AppSecret/Token → ② 复制 Webhook 地址到微信公众平台「设置→公众号设置→功能设置→服务器配置」→ ③ URL 填该地址、Token 填你上面填的、加密方式选「明文模式」（本期推荐）→ 提交即完成对接</div>';
  $('#mpWebHint').textContent = '已配置 ' + list.length + ' 个公众号';
 });
 loaders.mpLogs(0);
};
// 公众号详情（关键词 / 粉丝 / 日志）
window.mpShow = function(id, name){
 mpCurBot = id;
 var d = $('mpDetail'); if(d){ d.style.display = ''; }
 var t = $('mpDetailTitle'); if(t) t.textContent = '📢 ' + name + ' 详情';
 loadMpKeywords(id);
 loaders.mpLogs(id);
 api('/api/mp/fans?botId='+id).then(function(r){
  var fans = (r.data||[]);
  var fb = $('mpDetailBody');
  if(!fb) return;
  var fanRows = fans.map(function(f){
   return '<tr><td style="font-size:12px;font-family:monospace">'+esc(f.openid)+'</td><td>'+esc(f.displayName||'-')+'</td><td>'+(f.boundUserId>0?'<span class="badge green">#'+f.boundUserId+'</span>':'<span class="badge gray">未绑定</span>')+'</td><td>'+f.permLevel+'</td></tr>';
  }).join('');
  var kwHtml = '<div class="card" style="margin-top:12px"><h3>🎯 关键词回答 <span style="font-size:12px;color:var(--muted)">触发词 → 固定回复 / AI / 忽略（每个公众号独立）</span></h3>' +
   '<div class="action-bar"><button class="btn" onclick="mpOpenKw(0)">+ 添加关键词</button></div>' +
   '<div id="mpKwList" style="margin-top:8px"><div style="color:var(--muted);padding:12px">加载中…</div></div></div>' +
   '<div class="card" style="margin-top:12px"><h3>👥 粉丝（'+fans.length+'）</h3><div class="table-wrap" style="max-height:220px;overflow-y:auto"><table><thead><tr><th>openid</th><th>备注</th><th>绑定账号</th><th>权限</th></tr></thead><tbody>'+(fanRows||'<tr><td colspan="4" style="text-align:center;color:var(--muted)">暂无粉丝互动</td></tr>')+'</tbody></table></div></div>';
  // 先展示关键词+粉丝，日志单独卡片
  var logHtml = '<div class="card" style="margin-top:12px"><h3>📋 公众号日志</h3><div id="mpLogBox" style="color:var(--muted);font-size:12px">加载中…</div></div>';
  fb.innerHTML = kwHtml + logHtml;
  loadMpKeywords(id);
  loaders.mpLogs(id);
 });
};
// 关键词列表
function loadMpKeywords(botId){
 if(!botId) return;
 api('/api/mp/keywords?botId='+botId).then(function(r){
  var list = r.data || [];
  var el = $('mpKwList'); if(!el) return;
  var rows = list.map(function(k){
   var mt = {exact:'精确',contains:'包含',regex:'正则'}[k.matchType] || k.matchType;
   var act = {reply:'固定回复',ai:'AI回答',none:'忽略'}[k.action] || k.action;
   return '<tr><td style="font-family:ui-monospace,monospace">'+esc(k.trigger)+'</td><td>'+mt+'</td><td>'+act+'</td><td style="font-size:12px">'+esc((k.content||'').take(30))+'</td><td>'+(k.enabled?'<span class="badge green">开</span>':'<span class="badge gray">关</span>')+'</td>' +
    '<td><button class="btn-ghost btn-sm" onclick="mpOpenKw('+k.id+',\''+esc(k.trigger)+'\',\''+esc(k.matchType)+'\',\''+esc(k.action)+'\',\''+esc(k.content).replace(/'/g,"\\'")+'\')">编辑</button> <button class="btn-ghost btn-sm" style="color:var(--red)" onclick="mpDelKw('+k.id+')">删</button></td></tr>';
  }).join('');
  el.innerHTML = '<div class="table-wrap"><table><thead><tr><th>触发词</th><th>匹配</th><th>动作</th><th>内容</th><th>状态</th><th>操作</th></tr></thead><tbody>' + (rows||'<tr><td colspan="6" style="text-align:center;color:var(--muted)">暂无关键词规则</td></tr>') + '</tbody></table></div>';
 });
}
// 新增/编辑公众号
window.mpOpenEdit = function(id){
 api('/api/mp/bots').then(function(r){
  var list = r.data || [];
  var b = list.find(function(x){ return x.id===id; }) || {};
  openModal((id?'编辑公众号':'添加公众号'),
   '<div class="form-row"><label>名称</label><input id="mpName" class="input" value="'+esc(b.name||'')+'" placeholder="如：我的测试公众号"></div>' +
   '<div class="form-row"><label>AppID（公众号唯一标识，公众号后台「基本配置」）</label><input id="mpAppid" class="input" value="'+esc(b.appid||'')+'" placeholder="wx1234567890abcdef"></div>' +
   '<div class="form-row"><label>AppSecret（公众号后台「基本配置」，用于获取客服消息 access_token）</label><input id="mpSecret" class="input" type="password" value="'+esc(b.appSecret||'')+'" placeholder="留空=不修改（编辑时）"></div>' +
   '<div class="form-row"><label>Token（服务器配置里的自定义 Token，用于 URL 验签）</label><input id="mpToken" class="input" type="password" value="'+esc(b.token||'')+'" placeholder="自己随意填一串英数字"></div>' +
   '<div class="form-row"><label>EncodingAESKey（安全/兼容模式用，明文模式可留空）</label><input id="mpAes" class="input" value="'+esc(b.encodingAesKey||'')+'" placeholder="明文模式可留空"></div>' +
   '<div class="form-row"><label>加密模式</label><select id="mpEncrypt" class="input"><option value="plain"'+(b.encryptMode==='plain'?' selected':'')+'>明文模式（推荐）</option><option value="compatible"'+(b.encryptMode==='compatible'?' selected':'')+'>兼容模式</option><option value="safe"'+(b.encryptMode==='safe'?' selected':'')+'>安全模式</option></select></div>' +
   '<div class="form-row"><label>AI 模型（智能回答用）</label><input id="mpModel" class="input" value="'+esc(b.aiModel||'qtai-sj')+'" placeholder="默认 qtai-sj"></div>' +
   '<div class="form-row"><label>欢迎语（新粉丝关注时）</label><input id="mpWelcome" class="input" value="'+esc(b.welcome||'')+'" placeholder="留空用默认"></div>' +
   '<div class="form-row"><label>默认回复（关键词未命中且 AI 关闭时）</label><input id="mpDefault" class="input" value="'+esc(b.defaultReply||'')+'" placeholder="留空用默认提示"></div>' +
   '<div class="form-row"><label>AI 系统人设</label><textarea id="mpPrompt" class="input" rows="3" placeholder="可选：设定公众号 AI 的人格/语气">'+esc(b.systemPrompt||'')+'</textarea></div>' +
   '<div class="form-row"><label><input type="checkbox" id="mpEnabled"'+(b.enabled!==false?' checked':'')+'>启用</label> ' +
   '<label style="margin-left:12px"><input type="checkbox" id="mpKeyword"'+(b.keywordEnabled!==false?' checked':'')+'>关键词回答</label> ' +
   '<label style="margin-left:12px"><input type="checkbox" id="mpAi"'+(b.aiEnabled!==false?' checked':'')+'>AI 智能回答</label></div>',
   function(){
    var nm=$('mpName').value.trim(), appid=$('mpAppid').value.trim(), sec=$('mpSecret').value.trim(), tok=$('mpToken').value.trim();
    var aes=$('mpAes').value.trim(), enc=$('mpEncrypt').value;
    if(!appid){ toast('AppID 不能为空', false); return; }
    if(!nm){ toast('请填写公众号名称', false); return; }
    var body = { id: id, name: nm, appid: appid, appSecret: sec, token: tok, encodingAesKey: aes, encryptMode: enc,
     aiModel: $('mpModel').value.trim()||'qtai-sj', welcome: $('mpWelcome').value.trim(), defaultReply: $('mpDefault').value.trim(),
     systemPrompt: $('mpPrompt').value.trim(), enabled: $('mpEnabled').checked, keywordEnabled: $('mpKeyword').checked, aiEnabled: $('mpAi').checked };
    api('/api/mp/bots', { method:'POST', body: body }).then(function(rr){
     toast(rr.msg || '已保存', rr.code===0);
     if(rr.code===0){ closeModal(); loaders.mp(); }
    });
   });
 });
};
window.mpDel = function(id){
 if(!confirm('确认删除该公众号及其关键词？')) return;
 api('/api/mp/bots/'+id, { method:'DELETE' }).then(function(r){
  toast(r.msg||'已删除', r.code===0);
  if(r.code===0) loaders.mp();
 });
};
// 关键词新增/编辑
window.mpOpenKw = function(id, trig, mtype, act, content){
 openModal((id?'编辑关键词':'添加关键词'),
  '<div class="form-row"><label>触发词</label><input id="kwTrig" class="input" value="'+esc(trig||'')+'" placeholder="用户发这个词就命中（如：你好）"></div>' +
  '<div class="form-row"><label>匹配方式</label><select id="kwType" class="input"><option value="contains"'+(mtype==='contains'?' selected':'')+'>包含</option><option value="exact"'+(mtype==='exact'?' selected':'')+'>完全相等</option><option value="regex"'+(mtype==='regex'?' selected':'')+'>正则</option></select></div>' +
  '<div class="form-row"><label>动作</label><select id="kwAct" class="input"><option value="reply"'+(act==='reply'||!act?' selected':'')+'>固定回复</option><option value="ai"'+(act==='ai'?' selected':'')+'>AI 智能回答</option><option value="none"'+(act==='none'?' selected':'')+'>忽略（不回复）</option></select></div>' +
  '<div class="form-row"><label>回复内容（固定回复时的文本）</label><textarea id="kwContent" class="input" rows="3">'+esc(content||'')+'</textarea></div>',
  function(){
   var trig2=$('kwTrig').value.trim();
   if(!trig2){ toast('触发词不能为空', false); return; }
   api('/api/mp/keywords', { method:'POST', body: { id: id, botId: mpCurBot, trigger: trig2, matchType: $('kwType').value, action: $('kwAct').value, content: $('kwContent').value, enabled: true } }).then(function(r){
    toast(r.msg||'已保存', r.code===0);
    if(r.code===0){ closeModal(); loadMpKeywords(mpCurBot); }
   });
  });
};
window.mpDelKw = function(id){
 if(!confirm('确认删除该关键词？')) return;
 api('/api/mp/keywords/'+id, { method:'DELETE' }).then(function(r){
  toast(r.msg||'已删除', r.code===0);
  if(r.code===0) loadMpKeywords(mpCurBot);
 });
};
// 公众号日志
loaders.mpLogs = function(botId){
 api('/api/mp/logs?botId='+(botId||0)).then(function(r){
  var list = r.data || [];
  var el = $('mpLogBox'); if(!el) return;
  if(!list.length){ el.innerHTML = '<div style="color:var(--muted);padding:8px">暂无日志</div>'; return; }
  var rows = list.map(function(l){
   var ts = new Date(l.createdAt);
   var tstr = ts.getFullYear()+'-'+String(ts.getMonth()+1).padStart(2,'0')+'-'+String(ts.getDate()).padStart(2,'0')+' '+String(ts.getHours()).padStart(2,'0')+':'+String(ts.getMinutes()).padStart(2,'0')+':'+String(ts.getSeconds()).padStart(2,'0');
   var typeColor = {receive:'blue',keyword:'cyan',ai_reply:'green',progress:'amber',event:'gray'}[l.type] || 'gray';
   return '<tr><td style="font-size:11px;color:var(--muted)">'+tstr+'</td><td><span class="badge '+typeColor+'">'+esc(l.type)+'</span></td><td style="font-size:12px">'+esc(l.content)+'</td></tr>';
  }).join('');
  el.innerHTML = '<div class="table-wrap" style="max-height:240px;overflow-y:auto"><table><thead><tr><th>时间</th><th>类型</th><th>内容</th></tr></thead><tbody>'+rows+'</tbody></table></div>';
 });
};

// ===== ★ v1.194 知识库（RAG：多库管理 + 文档上传分块 + BM25 检索） =====
var kbCur = 0;
loaders.kb = function(){
 var box = $('view-kb');
 box.innerHTML = '<div class="card"><h3>📚 知识库 <span style="font-size:12px;color:var(--muted)">上传文档 → 自动分块 → AI 基于文档回答（RAG 检索增强）</span></h3>' +
  '<div class="action-bar"><button class="btn" onclick="kbOpenEdit(0)">+ 新建知识库</button><span id="kbHint" style="font-size:12px;color:var(--muted)">加载中…</span></div>' +
  '<div id="kbList" style="margin-top:12px"><div style="color:var(--muted);padding:16px">加载中…</div></div></div>' +
  '<div class="card" id="kbDetail" style="display:none"><h3 id="kbDetailTitle">知识库详情</h3><div id="kbDetailBody"></div></div>';
 api('/api/kb/bases').then(function(r){
  var list = r.data || [];
  var el = $('kbList'); if(!el) return;
  if(!list.length){ el.innerHTML = '<div style="color:var(--muted)">暂无知识库。点击「+ 新建知识库」创建，然后在详情里上传文档（txt/md 内容），AI 将能基于你的资料智能回答。</div>'; $('#kbHint').textContent='未创建'; return; }
  var rows = list.map(function(b){
   return '<tr>' +
    '<td><b>'+esc(b.name)+'</b><div style="font-size:11px;color:var(--muted)">'+esc(b.description||'')+'</div></td>' +
    '<td>'+(b.isPublic?'<span class="badge green">公开</span>':'<span class="badge gray">私有</span>')+'</td>' +
    '<td>'+b.docCount+' 篇</td><td>'+b.chunkCount+' 块</td>' +
    '<td style="white-space:nowrap"><button class="btn-ghost btn-sm" onclick="kbShow('+b.id+',\''+esc(b.name)+'\')">详情</button> ' +
    '<button class="btn-ghost btn-sm" onclick="kbOpenEdit('+b.id+',\''+esc(b.name)+'\',\''+esc(b.description)+'\','+(b.isPublic?'true':'false')+')">编辑</button> ' +
    '<button class="btn-ghost btn-sm" style="color:var(--red)" onclick="kbDel('+b.id+')">删</button></td></tr>';
  }).join('');
  el.innerHTML = '<div class="table-wrap"><table><thead><tr><th>知识库</th><th>可见性</th><th>文档</th><th>分块</th><th>操作</th></tr></thead><tbody>'+rows+'</tbody></table></div>' +
   '<div style="font-size:11px;color:var(--muted);margin-top:8px">💡 使用：上传你的资料（如产品说明/操作手册/业务规范）→ 在 QQ/微信/公众号/内置聊天里问相关问题时，智能体会自动检索知识库回答，基于文档内容精准回复。</div>';
  $('#kbHint').textContent = '共 ' + list.length + ' 个知识库';
 });
};
window.kbShow = function(id, name){
 kbCur = id;
 var d = $('kbDetail'); if(d) d.style.display = '';
 var t = $('kbDetailTitle'); if(t) t.textContent = '📚 ' + name;
 var fb = $('kbDetailBody');
 fb.innerHTML = '<div class="action-bar">' +
  '<button class="btn" onclick="kbOpenDoc(0)">+ 上传文档</button> ' +
  '<button class="btn-ghost" style="color:var(--red)" onclick="kbClear('+id+')">清空全部</button> ' +
  '<span style="font-size:12px;color:var(--muted)">检索测试：</span><input id="kbTestQuery" class="input" style="width:220px;display:inline-block" placeholder="输入检索词…"> <button class="btn-ghost" onclick="kbTestSearch('+id+')">检索</button></div>' +
  '<div id="kbSearchResult" style="margin:10px 0;font-size:12px;color:var(--muted)"></div>' +
  '<div class="card"><h3>📄 文档列表</h3><div class="table-wrap"><table><thead><tr><th>文档</th><th>大小</th><th>分块</th><th>状态</th><th>操作</th></tr></thead><tbody id="kbDocList"><tr><td colspan="5" style="text-align:center;color:var(--muted)">加载中…</td></tr></tbody></table></div></div>';
 loadKbDocs(id);
};
function loadKbDocs(id){
 api('/api/kb/docs?kbId='+id).then(function(r){
  var list = r.data || [];
  var tb = $('kbDocList'); if(!tb) return;
  if(!list.length){ tb.innerHTML = '<tr><td colspan="5" style="text-align:center;color:var(--muted)">暂无文档，点击「+ 上传文档」添加内容</td></tr>'; return; }
  tb.innerHTML = list.map(function(d){
   var size = d.fileSize>1048576 ? (d.fileSize/1048576).toFixed(1)+'MB' : (d.fileSize>1024 ? (d.fileSize/1024).toFixed(1)+'KB' : d.fileSize+'B');
   return '<tr><td>'+esc(d.title)+'</td><td>'+size+'</td><td>'+d.chunkCount+' 块</td><td>'+(d.status==='ok'?'<span class="badge green">ok</span>':'<span class="badge red">'+esc(d.error)+'</span>')+'</td>' +
    '<td><button class="btn-ghost btn-sm" style="color:var(--red)" onclick="kbDelDoc('+d.id+','+id+')">删</button></td></tr>';
  }).join('');
 });
}
window.kbOpenEdit = function(id, name, desc, isPublic){
 openModal((id?'编辑知识库':'新建知识库'),
  '<div class="form-row"><label>名称 *</label><input id="kbName" class="input" value="'+esc(name||'')+'" placeholder="如：产品手册"></div>' +
  '<div class="form-row"><label>描述</label><input id="kbDesc" class="input" value="'+esc(desc||'')+'" placeholder="这个知识库存什么"></div>' +
  '<div class="form-row"><label><input type="checkbox" id="kbPublic"'+(isPublic?' checked':'')+'>公开（所有人可用此知识库检索）</label></div>',
  function(){
   var nm=$('kbName').value.trim();
   if(!nm){ toast('名称不能为空', false); return; }
   api('/api/kb/bases'+(id?'/'+id:''), { method:'POST', body: { name: nm, description: $('kbDesc').value.trim(), isPublic: $('kbPublic').checked } }).then(function(r){
    toast(r.msg||'已保存', r.code===0);
    if(r.code===0){ closeModal(); loaders.kb(); }
   });
  });
};
window.kbOpenDoc = function(){
 openModal('上传文档到知识库',
  '<div class="form-row"><label>文档标题</label><input id="kbDocTitle" class="input" placeholder="如：API接入指南"></div>' +
  '<div class="form-row"><label>文档内容（支持换行/段落，自动分块）</label><textarea id="kbDocContent" class="input" rows="10" placeholder="粘贴文档内容…"></textarea></div>' +
  '<div style="font-size:11px;color:var(--muted)">提交后自动按 500 字/块切分并入库，AI 提问时按相关性检索。</div>',
  function(){
   var title=$('kbDocTitle').value.trim();
   var content=$('kbDocContent').value;
   if(!content.trim()){ toast('文档内容不能为空', false); return; }
   if(!title) title = '文档' + Date.now()%100000;
   api('/api/kb/docs', { method:'POST', body: { kbId: kbCur, title: title, content: content } }).then(function(r){
    toast(r.msg||'已上传', r.code===0);
    if(r.code===0){ closeModal(); loadKbDocs(kbCur); }
   });
  });
};
window.kbDel = function(id){
 if(!confirm('确认删除该知识库及全部文档？')) return;
 api('/api/kb/bases/'+id, { method:'DELETE' }).then(function(r){
  toast(r.msg||'已删除', r.code===0);
  if(r.code===0) loaders.kb();
 });
};
window.kbDelDoc = function(docId, kbId){
 if(!confirm('确认删除该文档？')) return;
 api('/api/kb/docs/'+docId+'?kbId='+kbId, { method:'DELETE' }).then(function(r){
  toast(r.msg||'已删除', r.code===0);
  if(r.code===0) loadKbDocs(kbId);
 });
};
window.kbClear = function(id){
 if(!confirm('确认清空该知识库全部文档？')) return;
 api('/api/kb/bases/'+id+'/clear', { method:'POST' }).then(function(r){
  toast(r.msg||'已清空', r.code===0);
  if(r.code===0) loadKbDocs(id);
 });
};
window.kbTestSearch = function(id){
 var q = ($('kbTestQuery')?$('kbTestQuery').value:'').trim();
 var el = $('kbSearchResult'); if(!el) return;
 if(!q){ el.innerHTML = '<span style="color:var(--red)">请输入检索词</span>'; return; }
 el.innerHTML = '🔍 检索中…';
 api('/api/kb/search?kbId='+id+'&query='+encodeURIComponent(q)).then(function(r){
  var list = r.data || [];
  if(!list.length){ el.innerHTML = '<span>未找到相关内容</span>'; return; }
  el.innerHTML = list.map(function(x){
   return '<div style="border:1px solid var(--border);border-radius:6px;padding:6px 10px;margin:4px 0"><b>'+esc(x.docTitle||'')+'</b> <span style="color:var(--muted)">得分 '+x.score+'</span><br><span style="font-size:12px">'+esc((x.content||'').take(200))+'</span></div>';
  }).join('');
 });
};
// ===== ★ v1.195 多子 Agent 调度任务查看页 =====
loaders.subagent = function(){
 var box = $('view-subagent');
 box.innerHTML = '<div class="card"><h3>🤖 子Agent 任务 <span style="font-size:12px;color:var(--muted)">主 Agent 派生的后台子任务 · 独立上下文 · 真工具调度</span></h3>' +
  '<div class="action-bar"><button class="btn-ghost" onclick="loadSubagentTasks()">🔄 刷新</button><span id="subAgentHint" style="font-size:12px;color:var(--muted)">加载中…</span></div>' +
  '<div id="subAgentList" style="margin-top:12px"><div style="color:var(--muted);padding:16px">加载中…</div></div></div>';
 loadSubagentTasks();
};
function loadSubagentTasks(){
 api('/api/subagent/tasks').then(function(r){
  var list = r.data || [];
  var el = $('subAgentList'); if(!el) return;
  $('#subAgentHint').textContent = '共 ' + list.length + ' 个任务';
  if(!list.length){ el.innerHTML = '<div style="color:var(--muted)">暂无子任务。主 Agent（智能体）在处理复杂任务时会派发子 Agent 后台并行执行，这里可查看结果。</div>'; return; }
  var rows = list.map(function(t){
   var stBadge = t.status==='done' ? '<span class="badge green">完成</span>' : (t.status==='failed' ? '<span class="badge red">失败</span>' : '<span class="badge cyan">运行中</span>');
   var ts = new Date(t.createdAt);
   var tstr = ts.getFullYear()+'-'+String(ts.getMonth()+1).padStart(2,'0')+'-'+String(ts.getDate()).padStart(2,'0')+' '+String(ts.getHours()).padStart(2,'0')+':'+String(ts.getMinutes()).padStart(2,'0');
   return '<tr>' +
    '<td>#'+t.id+'</td>' +
    '<td>'+esc((t.taskDesc||'').take(50))+'</td>' +
    '<td>'+stBadge+'</td>' +
    '<td style="font-size:12px;color:var(--muted)">'+tstr+'</td>' +
    '<td class="sa-result-cell" style="max-width:320px;font-size:12px;color:var(--muted)">'+esc((t.result||t.error||'').take(120))+'</td>' +
    '<td><button class="btn-ghost btn-sm" onclick="saExpand('+t.id+')">详情</button> <button class="btn-ghost btn-sm" style="color:var(--red)" onclick="saDel('+t.id+')">删</button></td></tr>';
  }).join('');
  el.innerHTML = '<div class="table-wrap"><table><thead><tr><th>ID</th><th>任务</th><th>状态</th><th>时间</th><th>结果</th><th>操作</th></tr></thead><tbody>'+rows+'</tbody></table></div>';
  window._saTaskStore = list;
 });
}
window.saExpand = function(id){
 var t = (window._saTaskStore||[]).find(function(x){ return x.id===id; });
 if(!t) return;
 openModal('子Agent #'+id+' 详情',
  '<div class="form-row"><label>任务描述</label><div style="font-size:13px;padding:8px;background:var(--inset);border-radius:6px">'+esc(t.taskDesc||'')+'</div></div>' +
  '<div class="form-row"><label>状态</label><div>'+t.status+'</div></div>' +
  '<div class="form-row"><label>结果</label><div style="font-size:12px;padding:8px;background:var(--inset);border-radius:6px;white-space:pre-wrap;max-height:240px;overflow-y:auto">'+esc(t.result||'')+'</div></div>' +
  (t.error ? '<div class="form-row"><label style="color:var(--red)">错误</label><div style="font-size:12px;padding:8px;background:var(--inset);border-radius:6px">'+esc(t.error)+'</div></div>' : ''), null, {hideFooter:true});
};
window.saDel = function(id){
 if(!confirm('确认删除子任务 #'+id+'？')) return;
 api('/api/subagent/tasks/'+id, {method:'DELETE'}).then(function(r){
  toast(r.msg||'已删除', r.code===0);
  if(r.code===0) loadSubagentTasks();
 });
};

// ★ v1.212 全局「加载中」守护：定时扫描页面里仍显示“加载中…”的占位区 → 超过 12s 自动替换为错误提示+重试
//   解决列表/详情页网络挂起时“加载中”永远显示（配合 api() 15s 超时，双重兜底）
(function(){
 var guardTimer = null;
 function scanLoading(){
  // 只扫当前可见视图
  var view = document.querySelector('.view.active');
  if(!view) return;
  var now = Date.now();
  view.querySelectorAll('div').forEach(function(d){
   var txt = d.textContent || '';
   if((txt.indexOf('加载中…') >= 0 || txt.indexOf('加载中') >= 0) && d.children.length <= 2){
    var key = 'lg_' + (d.id || (Math.random()*1e6|0));
    if(!d.getAttribute('data-lg-start')) d.setAttribute('data-lg-start', now);
    var start = parseInt(d.getAttribute('data-lg-start')||now, 10);
    if(now - start > 12000 && !d.getAttribute('data-lg-err')){
     d.setAttribute('data-lg-err', '1');
     var tip = document.createElement('div');
     tip.style.cssText = 'color:var(--red);padding:14px;text-align:center;font-size:12px';
     tip.innerHTML = '⚠️ 加载超时（12秒未响应） <button class="btn-ghost btn-sm" onclick="location.reload()">刷新页面</button>';
     d.innerHTML = '';
     d.appendChild(tip);
    }
   }
  });
 }
 function startGuard(){
  if(guardTimer) return;
  guardTimer = setInterval(scanLoading, 4000);
 }
 // 切页/打开弹窗后再扫描一轮
 var _origSwitch = window.switchView;
 window.switchView = function(pg){ if(_origSwitch) _origSwitch(pg); setTimeout(scanLoading, 1500); };
 document.addEventListener('click', function(){ setTimeout(scanLoading, 2000); });
 setInterval(scanLoading, 8000);  // 常驻兜底
})();

// ===== ★ v1.212 机器人设置（QQ/微信/公众号 三渠道平台设置面板：AI开关/消息记录/主动问候/欢迎语/模型/人设） =====
loaders.botsettings = function(){
 var box = $('view-botsettings');
 if(!box) return;
 box.innerHTML = '<div class="card"><h3>⚙️ 机器人平台设置</h3><div style="color:var(--muted);padding:10px;font-size:13px">加载中…</div></div>';
 api('/api/platform/settings?channel=qq').then(function(r){
  if(r.code !== 0){ box.innerHTML='<div class="card"><h3>⚙️ 机器人平台设置</h3><div style="color:var(--red);padding:10px">加载失败</div></div>'; return; }
  var chName = {qq:'QQ机器人', weixin:'微信机器人', mp:'公众号'};
  var chKey = {qq:'qq', weixin:'weixin', mp:'mp'};
  function loadChannel(ch){
   return api('/api/platform/settings?channel='+ch).then(function(r2){ return r2.data||{}; });
  }
  // 三个卡片：三渠道开关 + 配置项
  var html = '<div style="display:grid;grid-template-columns:repeat(auto-fit,minmax(300px,1fr));gap:14px;margin-top:8px" id="bsGrid"></div>';
  box.innerHTML = '<div class="card"><h3>⚙️ 机器人平台设置</h3><div style="color:var(--muted);padding:8px;font-size:12px">三渠道独立控制：AI智能回答 / 消息记录 / 主动问候 / 欢迎语 / 默认模型 / 人设。配置即时生效，也可对机器人说「把QQ的AI开关关掉」让智能体代改。</div>' + html + '</div>';
  var grid = $('bsGrid');
  chKey.qq = 'qq'; chKey.weixin = 'weixin'; chKey.mp = 'mp';
  Object.keys(chKey).forEach(function(k){
   var ch = chKey[k];
   loadChannel(ch).then(function(ps){
    ps = ps||{};
    var aiOn = ps.aiEnabled === true || ps.aiEnabled === undefined ? true : !!ps.aiEnabled;
    var recOn = ps.msgRecord === true || ps.msgRecord === undefined ? true : !!ps.msgRecord;
    var proOn = ps.proactiveEnabled === true || ps.proactiveEnabled === undefined ? true : !!ps.proactiveEnabled;
    var card = '<div class="card" style="box-shadow:none;border:1px solid var(--border)">'+
     '<h4>'+chName[k]+'</h4>'+
     '<div style="font-size:12px;color:var(--muted);margin-bottom:10px">'+ch.toUpperCase()+'</div>'+
     '<div class="form-row"><label><input type="checkbox" id="bs_'+ch+'_ai" '+(aiOn?'checked':'')+'> AI 智能回答</label></div>'+
     '<div class="form-row"><label><input type="checkbox" id="bs_'+ch+'_rec" '+(recOn?'checked':'')+'> 消息记录</label></div>'+
     '<div class="form-row"><label><input type="checkbox" id="bs_'+ch+'_pro" '+(proOn?'checked':'')+'> 主动问候</label></div>'+
     // ★ v1.212 唤醒/回话设置
     '<div class="form-row"><label><input type="checkbox" id="bs_'+ch+'_at" '+(ps.atReply!==false?'checked':'')+'> @机器人必回（默认开）</label></div>'+
     '<div class="form-row"><label>唤醒前缀（消息以它开头即触发，无需@；如「小桐」）</label><input class="input" id="bs_'+ch+'_wake" value="'+esc(ps.wakePrefix||'')+'" placeholder="留空=不启用"></div>'+
     '<div class="form-row"><label><input type="checkbox" id="bs_'+ch+'_c2c" '+(ps.c2cAuto!==false?'checked':'')+'> 私聊自动回复</label></div>'+
     '<div class="form-row"><label><input type="checkbox" id="bs_'+ch+'_self" '+(ps.ignoreSelf?'checked':'')+'> 忽略自己消息</label></div>'+
     '<div class="form-row"><label><input type="checkbox" id="bs_'+ch+'_quote" '+(ps.quoteReply!==false?'checked':'')+'> 引用回复（被引用时触发）</label></div>'+
     '<div class="form-row"><label>黑名单（逗号分隔，命中不回复）</label><input class="input" id="bs_'+ch+'_bl" value="'+esc(ps.blacklist||'')+'" placeholder="如：广告,代购"></div>'+
     '<div class="form-row"><label>白名单（逗号分隔，仅这些可触发；留空=不限制）</label><input class="input" id="bs_'+ch+'_wl" value="'+esc(ps.whitelist||'')+'" placeholder="如：openid1,openid2"></div>'+
     (ch==='qq' ? '<div class="form-row"><label><input type="checkbox" id="bs_qq_join" '+(ps.groupJoin?'checked':'')+'> 群聊自动参与（不@也接话）</label></div>'+
     '<div class="form-row"><label>群聊参与概率（1-100%，默认8%）</label><input class="input" type="number" min="0" max="100" id="bs_qq_prob" value="'+(ps.groupJoinProb||8)+'"></div>'+
     '<div class="form-row"><label>插话冷却秒数（默认20）</label><input class="input" type="number" min="0" max="600" id="bs_qq_cd" value="'+(ps.joinCooldownSec||20)+'"></div>'+
     '<div class="form-row"><label><input type="checkbox" id="bs_qq_atasker" '+(ps.atAsker!==false?'checked':'')+'> 群聊回复自动@提问者本人（像真人点名回答）</label></div>'+
     '<div style="font-size:11px;color:var(--muted);margin-bottom:8px">💡 需在 QQ 开放平台为该机器人开启「接收所有消息」权限，群里不@也能收到消息并自动参与讨论。</div>' : '')+
     '<div class="form-row"><label>默认欢迎语</label><input class="input" id="bs_'+ch+'_wel" value="'+esc(ps.welcome||'')+'" placeholder="留空=用机器人各自设置"></div>'+
     '<div class="form-row"><label>默认模型（留空=各机器人自己的）</label><input class="input" id="bs_'+ch+'_model" value="'+esc(ps.aiModel||'')+'" placeholder="如 qtai-sj"></div>'+
     '<div class="form-row"><label>默认人设（留空=各机器人自己的）</label><textarea class="input" id="bs_'+ch+'_sp" rows="2">'+esc(ps.systemPrompt||'')+'</textarea></div>'+
     '<button class="btn btn-sm" onclick="saveBotSettings(\''+ch+'\')">💾 保存 '+chName[k]+'</button>'+
     '</div>';
    grid.insertAdjacentHTML('beforeend', card);
   });
  });
 });
}
function saveBotSettings(ch){
 var body = {channel:ch};
 var aiEl=$('bs_'+ch+'_ai'), recEl=$('bs_'+ch+'_rec'), proEl=$('bs_'+ch+'_pro');
 if(aiEl) body.aiEnabled = aiEl.checked ? 'true':'false';
 if(recEl) body.msgRecord = recEl.checked ? 'true':'false';
 if(proEl) body.proactiveEnabled = proEl.checked ? 'true':'false';
 // ★ v1.212 唤醒/回话字段
 var atEl=$('bs_'+ch+'_at'); if(atEl) body.atReply = atEl.checked ? 'true':'false';
 var wakeEl=$('bs_'+ch+'_wake'); if(wakeEl && wakeEl.value.trim()) body.wakePrefix = wakeEl.value.trim();
 var c2cEl=$('bs_'+ch+'_c2c'); if(c2cEl) body.c2cAuto = c2cEl.checked ? 'true':'false';
 var selfEl=$('bs_'+ch+'_self'); if(selfEl) body.ignoreSelf = selfEl.checked ? 'true':'false';
var quoteEl=$('bs_'+ch+'_quote'); if(quoteEl) body.quoteReply = quoteEl.checked ? 'true':'false';
var blEl=$('bs_'+ch+'_bl'); if(blEl && blEl.value.trim()) body.blacklist = blEl.value.trim();
var wlEl=$('bs_'+ch+'_wl'); if(wlEl && wlEl.value.trim()) body.whitelist = wlEl.value.trim();
var cdEl=$('bs_qq_cd'); if(cdEl && cdEl.value.trim()!=='') body.joinCooldownSec = Math.max(0, Math.min(600, parseInt(cdEl.value)||20)).toString();
 var ataskerEl=$('bs_qq_atasker'); if(ataskerEl) body.atAsker = ataskerEl.checked ? 'true':'false';
 var joinEl=$('bs_qq_join'); if(joinEl) body.groupJoin = joinEl.checked ? 'true':'false';
 var probEl=$('bs_qq_prob'); if(probEl && probEl.value.trim()!=='') body.groupJoinProb = Math.max(0, Math.min(100, parseInt(probEl.value)||0)).toString();
 var wel=$('bs_'+ch+'_wel'); if(wel && wel.value.trim()) body.welcome = wel.value.trim();
 var md=$('bs_'+ch+'_model'); if(md && md.value.trim()) body.aiModel = md.value.trim();
 var sp=$('bs_'+ch+'_sp'); if(sp && sp.value.trim()) body.systemPrompt = sp.value.trim();
 api('/api/platform/settings',{method:'POST',body:body}).then(function(r){ toast(r.msg||'', r.code===0); });
}

// ===== ★ v1.212 统一消息记录中心（QQ/微信/公众号 收发记录查询/筛选/清空） =====
var msgCenterCh = '';
function loadMsgCenter(){
 var box = $('view-msgcenter');
 if(!box) return;
 box.innerHTML = '<div class="card"><h3>💬 消息记录中心</h3><div style="color:var(--muted);padding:10px;font-size:13px">加载中…</div></div>';
 api('/api/platform/messages?limit=50').then(function(r){
  var rows = r.data||[];
  var html = '<div class="action-bar">'+
   '<select class="input" style="max-width:160px" onchange="msgCenterCh=this.value;loadMsgCenter()"><option value="">全部渠道</option><option value="qq"'+(msgCenterCh==='qq'?' selected':'')+'>QQ</option><option value="weixin"'+(msgCenterCh==='weixin'?' selected':'')+'>微信</option><option value="mp"'+(msgCenterCh==='mp'?' selected':'')+'>公众号</option></select>'+
   '<button class="btn-ghost" onclick="loadMsgCenter()">刷新</button>'+
   '<button class="btn-ghost danger" onclick="clearMsgCenter()">🗑 清空记录</button></div>'+
   '<div style="overflow-x:auto"><table class="tb"><thead><tr><th>时间</th><th>渠道</th><th>方向</th><th>目标</th><th>内容</th><th>模型</th></tr></thead><tbody>';
  if(!rows.length) html += '<tr><td colspan="6" style="color:var(--muted);text-align:center;padding:18px">暂无消息记录</td></tr>';
  else rows.forEach(function(m){
   var ts = m.createdAt||0;
   var tstr = ts ? new Date(ts).toLocaleString('zh-CN',{hour12:false}) : '-';
   var chTxt = m.channel==='qq'?'QQ':(m.channel==='weixin'?'微信':(m.channel==='mp'?'公众号':m.channel));
   var dirTxt = m.direction==='out' ? '<span class="badge" style="color:var(--green)">出↖</span>' : '<span class="badge" style="color:var(--cyan)">入↙</span>';
   var target = (m.groupOpenid||'').slice(0,12) || (m.openid||'').slice(0,16) || '-';
   html += '<tr><td style="font-size:11px;white-space:nowrap">'+tstr+'</td><td>'+chTxt+'</td><td>'+dirTxt+'</td><td style="font-family:monospace;font-size:11px">'+esc(target)+'</td><td style="max-width:340px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap">'+esc((m.content||'').slice(0,80))+'</td><td style="font-size:11px">'+esc(m.model||'')+'</td></tr>';
  });
  html += '</tbody></table></div>';
  box.innerHTML = '<div class="card"><h3>💬 消息记录中心</h3><div style="color:var(--muted);padding:2px 0 8px;font-size:12px">三渠道收发消息全量留存（最多8000条自动滚动），智能体也可说「查下QQ最近消息」查看。</div>'+html+'</div>';
 });
}
function clearMsgCenter(){
 if(!confirm('确认清空全部消息记录？')) return;
 api('/api/platform/messages/clear',{method:'POST',body:{channel:''}}).then(function(r){
  toast(r.msg||'', r.code===0); if(r.code===0) loadMsgCenter();
 });
}
loaders.msgcenter = function(){ msgCenterCh=''; loadMsgCenter(); };

// ===== ★ v1.212 插件管理页（列表/热重载/启停/卸载/市场预留） =====
loaders.plugins = function(){
 var box = $('view-plugins');
 if(!box) return;
 box.innerHTML = '<div class="card"><h3>📦 插件管理</h3><div style="color:var(--muted);padding:10px">加载中…</div></div>';
 api('/api/plugins').then(function(r){
  if(r.code!==0){ box.innerHTML='<div class="card"><h3>📦 插件管理</h3><div style="color:var(--red);padding:10px">加载失败</div></div>'; return; }
  var list=(r.data&&r.data.plugins)||[];
  var html='<div class="action-bar"><button class="btn-ghost" onclick="reloadPlugins()">🔄 热重载</button><span style="font-size:12px;color:var(--muted)">把插件目录放进服务器 /app/plugins/ 后点热重载即可生效，无需重启！</span></div>';
  if(!list.length) html+='<div style="color:var(--muted);padding:18px">暂无插件。在服务器 /app/plugins/ 下建 插件名/plugin.json 即可（参考：{"name":"示例","version":"1.0.0","triggers":["关键词"],"action":"reply","content":"回复内容"}）</div>';
  else html+='<div class="table-wrap"><table class="tb"><thead><tr><th>名称</th><th>版本</th><th>触发词</th><th>动作</th><th>状态</th><th>操作</th></tr></thead><tbody>'+list.map(function(p){
   return '<tr><td><b>'+esc(p.name)+'</b><div style="font-size:11px;color:var(--muted)">'+esc(p.id||'')+(p.desc?' · '+esc(p.desc):'')+'</div></td><td>'+esc(p.version)+'</td><td style="font-size:11px">'+esc((p.triggers||[]).join(', '))+ '</td><td>'+esc(p.action)+'</td><td>'+(p.enabled?'<span class="badge green">启用</span>':'<span class="badge gray">停用</span>')+'</td><td style="white-space:nowrap"><button class="btn-ghost btn-sm" onclick="togglePlugin(\''+esc(p.id)+'\','+(p.enabled?'false':'true')+')">'+(p.enabled?'停用':'启用')+'</button> <button class="btn-ghost btn-sm danger" onclick="removePlugin(\''+esc(p.id)+'\')">卸载</button></td></tr>';
  }).join('')+'</tbody></table></div>';
  box.innerHTML='<div class="card"><h3>📦 插件管理</h3><div style="color:var(--muted);padding:2px 0 8px;font-size:12px">插件系统：目录扫描 + manifest 热重载 + on_message 钩子（触发词命中即回复/忽略）。</div>'+html+'</div>';
 });
}
function reloadPlugins(){ api('/api/plugins/reload',{method:'POST'}).then(function(r){ toast(r.msg||'',r.code===0); if(r.code===0) loaders.plugins(); }); }
function togglePlugin(id,en){ api('/api/plugins/toggle',{method:'POST',body:{id:id,enabled:en?'true':'false'}}).then(function(r){ toast(r.msg||'',r.code===0); if(r.code===0) loaders.plugins(); }); }
function removePlugin(id){ if(!confirm('确认卸载插件 '+id+'？')) return; api('/api/plugins/remove',{method:'POST',body:{id:id}}).then(function(r){ toast(r.msg||'',r.code===0); if(r.code===0) loaders.plugins(); }); }
"""
}